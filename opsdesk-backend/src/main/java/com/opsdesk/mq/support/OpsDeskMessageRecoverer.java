package com.opsdesk.mq.support;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 死信 recoverer：在 republish 时补一个 {@code x-retry-count} 头（工单 D9-04，规格基线 §13.4 约束 4）
 *
 * <h2>为什么需要它</h2>
 * §13.4 约束 4 指出：{@code RepublishMessageRecoverer} 默认只加
 * {@code x-exception-message} / {@code x-exception-stacktrace} / {@code x-original-*}，
 * <b>不带重试计数</b>。而 API §16.17 的 {@code DeadLetterVO.retryCount} 需要这个值 ——
 * D9-05 的死信管理接口要靠它显示「重试了几次」。
 *
 * <h2>🔴 覆写点：{@code additionalHeaders(Message, Throwable)}（签名必须逐字对齐）</h2>
 * <pre>
 * protected Map&lt;? extends String, ?&gt; additionalHeaders(Message message, Throwable cause)
 * </pre>
 * 已用 {@code javap -p} 核实真实签名 —— <b>不是</b> {@code (MessageProperties)}、<b>不是</b> {@code void}。
 *
 * <p>⚠️ <b>为什么这是本单最容易静默出错的地方</b>：签名写错<b>不会编译报错</b>，
 * 它只是一个「重载」而不是「覆写」—— 运行期框架调的仍是父类那个空实现，
 * {@code x-retry-count} 悄悄不出现，而所有日志看起来都正常。
 * 唯一的防法是<b>加 {@code @Override}</b>：签名一漂移立刻编译失败。
 *
 * <p>⚠️ {@code x-exception-*} / {@code x-original-*} 是父类 {@code recover()} 里加的
 * （已从字节码确认，不在 {@code additionalHeaders} 里），所以覆写本方法天然不会丢它们；
 * 这里仍 {@code super.additionalHeaders(...)} 合并一次，防将来版本把 header 挪进来。
 *
 * <h2>⚠️ 两条死信路径的重试次数来源不同（§13.4 约束 4，D9-05 要两条都兼容）</h2>
 * <table border="1">
 *   <tr><th>路径</th><th>重试次数来源</th></tr>
 *   <tr><td>走 recoverer（重试耗尽）</td><td>本类补的 {@code x-retry-count}，<b>恒 = maxAttempts</b></td></tr>
 *   <tr><td>原生死信（进入监听器前被拒，如反序列化失败）</td><td>broker 的 {@code x-death[0].count}</td></tr>
 * </table>
 *
 * <h2>⚠️ 必须保留「清掉 error. 前缀」这个修复（D9-02 发现的真 bug）</h2>
 * {@code RepublishMessageRecoverer} 默认给路由键加 {@code "error."} 前缀，
 * 而本项目的 DLQ 绑定用的是<b>原始 Routing Key</b> → 死信在 {@code opsdesk.dlx} 上匹配不到任何绑定，
 * 又因 {@code mandatory=true} 被 broker 退回，<b>消息凭空消失</b>。
 * 构造器里显式 {@code setErrorRoutingKeyPrefix("")}。
 */
public class OpsDeskMessageRecoverer extends RepublishMessageRecoverer {

    /**
     * 死信消息头：重试次数。
     *
     * <p>⚠️ 别与 broker 的 {@code x-death} 混了：{@code x-death} 是 broker 原生死信时自己加的，
     * 走 recoverer 这条路<b>不会有</b> {@code x-death}，所以两条路径的取值方式必须分开处理。
     */
    public static final String HEADER_RETRY_COUNT = "x-retry-count";

    /** 死信进入时刻；recoverer 路径没有 broker 的 x-death.time，故由 recoverer 自己补 */
    public static final String HEADER_FAILED_AT = "x-failed-at";

    /** 重试次数上限：由容器工厂的 AdviceChain 决定，本类只负责把它写进消息头 */
    private final int maxAttempts;

    public OpsDeskMessageRecoverer(RabbitTemplate rabbitTemplate, String exchange, int maxAttempts) {
        super(rabbitTemplate, exchange);
        this.maxAttempts = maxAttempts;
        // 见类注释：不清掉 "error." 前缀，死信就到不了 DLQ
        setErrorRoutingKeyPrefix("");
    }

    @Override
    protected Map<? extends String, ?> additionalHeaders(Message message, Throwable cause) {
        Map<String, Object> headers = new HashMap<>();
        Map<? extends String, ?> fromSuper = super.additionalHeaders(message, cause);
        if (fromSuper != null) {
            headers.putAll(fromSuper);
        }
        // recoverer 只在「重试耗尽」时才被调用 → 这个值恒等于 maxAttempts
        headers.put(HEADER_RETRY_COUNT, maxAttempts);
        // 该路径没有 broker 的 x-death.time；补一个失败时刻，DeadLetterVO.failedAt 才不为空
        headers.put(HEADER_FAILED_AT, Instant.now().toString());
        return headers;
    }
}
