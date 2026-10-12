package com.opsdesk.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 调用基础设施配置（工单 D10-01，规格基线 §10.3 / §10.4 / §11.3）
 *
 * <h2>⚠️ 默认值写在 Java 里，不在 yml 里</h2>
 * 本单的「允许改动」不含 {@code application.yml}，而 yml 里也没有 {@code opsdesk.ai} 前缀 ——
 * 所以这些默认值全部落在<b>字段初始化</b>上。要临时调整时用命令行 / 环境变量覆盖，例如：
 * <pre>
 * java -jar app.jar --opsdesk.ai.read-timeout=20s
 * OPSDESK_AI_ENABLED=false                    # 环境变量形式
 * </pre>
 *
 * <p>这样反而更贴合本项目的取向：yml 只留全局默认，模块级开关与调优参数就近放在模块自己的属性类里。
 */
@ConfigurationProperties(prefix = "opsdesk.ai")
@Component
@Data
public class AiProperties {

    /**
     * 降级开关（§10.4）。
     *
     * <p><b>默认开启</b>：{@code @ConditionalOnProperty(matchIfMissing = true)} 依赖这个默认。
     * 关掉后所有 AI 调用统一返回 {@code 50300}，用于「没有 API Key 的环境」与「不依赖真实 LLM 的单测」。
     */
    private boolean enabled = true;

    /**
     * 单次请求的<b>连接</b>超时。
     *
     * <p>连不上不该等满总超时 —— 30s 都耗在建连接上是纯浪费。
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * 单次请求的<b>读取</b>超时（等待首字节/响应）。
     *
     * <h2>⚠️ 为什么必须单独设它，而不是只靠总超时</h2>
     * 总超时是用 {@code CompletableFuture.orTimeout} 做的，它只是「<b>放弃等待</b>」——
     * 底层那个 HTTP 连接不会被取消，而 RestClient 默认<b>没有读取超时</b>。
     * LLM 假死（连上了但永不返回）时，连接会一直挂着直到操作系统 TCP 超时 → 线程与连接泄漏。
     * 所以必须有这一层，让底层自己先断开。
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * 单次 AI 调用的<b>总硬上限</b>（规格基线 §10.3「超时（&gt; 30s）→ 判定失败」、§11.3「总超时 30s」）。
     *
     * <h2>⚠️ 为什么「总超时」和「重试 3 次」不矛盾（本单要给的取舍）</h2>
     * {@code spring.ai.retry} 配的是 {@code max-attempts=3}（= 原始 1 次 + 重试 2 次），退避 1s → 2s。
     * 最坏耗时 = {@code 10 + 1 + 10 + 2 + 10 = 33s}，<b>确实会超 30s</b>。
     * <p>取舍是「<b>分两层管</b>」：单次 10s 由 HTTP 层管（让底层先断），
     * 30s 总上限由本值管（掐住最坏情况）。于是最坏 33s 被 30s 截断 → <b>永远不会突破规格的 30s</b>。
     * <p>不选「把重试次数降到 2 次」是因为那要改 yml，超出本单允许改动范围。
     */
    private Duration totalTimeout = Duration.ofSeconds(30);

    /**
     * 结构化输出（D10-02 工单分析）用的采样温度。
     *
     * <h2>⚠️ 为什么不在 yml 里改 temperature</h2>
     * yml 里的 {@code spring.ai.deepseek.chat.options.temperature: 0.8} 是<b>全局默认</b>，
     * 聊天助手（D11）靠它保持对话的多样性 —— 改了会一起影响。
     * 而「工单描述 → 结构化 JSON」要的是<b>稳定、可解析</b>，0.8 偏高会导致输出漂移、JSON 时好时坏。
     * <p>所以做法是：<b>不动全局默认</b>，在结构化调用处用
     * {@code DeepSeekChatOptions.builder().temperature(...)} 局部覆盖（§0 约束「不顺手改无关配置」）。
     */
    private double structuredTemperature = 0.1;
}
