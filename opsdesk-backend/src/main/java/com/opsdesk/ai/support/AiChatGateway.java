package com.opsdesk.ai.support;

import com.opsdesk.ai.config.AiProperties;
import com.opsdesk.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * AI 调用的<b>唯一出口</b>（工单 D10-01，规格基线 §10.3 输出校验与兜底 / §10.4 降级方案）
 *
 * <h2>它解决什么问题</h2>
 * 「LLM 不可用」有五种来源：开关关掉、没配 Key、网络不通、超时、返回错误。
 * 如果让每个调用点自己 try/catch，就必然出现「有的地方转成了 50300、有的地方漏成了 50000」，
 * 甚至有的地方把异常吞掉让上层拿到 null。所以把「可用性判断 + 超时 + 异常语义」全部收敛到这一个类。
 *
 * <pre>
 * enabled=false（降级开关）  → 50300
 * ChatClient 没装配          → 50300
 * 超过 totalTimeout          → 50300（并放弃等待）
 * LLM 报错 / Key 无效 / 网络 → 50300（不是 50000）
 * 正常                       → 返回结果
 * </pre>
 *
 * <h2>⚠️ 为什么用线程池 + {@code orTimeout}，而不是直接阻塞调用</h2>
 * 想「30s 硬上限」就必须能把主线程从阻塞中解救出来 —— 而线程一旦阻塞在 socket 读上，
 * 只有它自己返回或超时才能退出。所以调用在一个独立线程上执行，主线程用
 * {@code orTimeout(...)} 限时等待。
 *
 * <p>⚠️ 但 {@code orTimeout} 只是「<b>放弃等待</b>」——它<b>不会</b>取消底层 HTTP 请求。
 * 真正让底层断开的是 {@link AiProperties#getReadTimeout()}（10s，HTTP 层的读超时）。
 * 两者是配合关系，缺一不可：只有总超时 → 连接泄漏；只有读超时 → 3 次重试叠加会突破 30s。
 *
 * <h2>⚠️ 为什么不用 {@code ForkJoinPool.commonPool}</h2>
 * 这里跑的是<b>阻塞 IO</b>，而 commonPool 是给 CPU 密集任务用的，且被整个 JVM 共享 ——
 * 几个卡住的 LLM 调用就能把公共池占满，连累到别处（并行流等）。所以自建专用池。
 */
@Slf4j
public class AiChatGateway implements AutoCloseable {

    /** 并发上限：超过 20/分钟/用户 的限流在 D11-03 做，这里只防「无限堆积」 */
    private static final int POOL_SIZE = 4;

    /** 有界队列：满了宁可拒绝（快速返回 50300），也不要无界积压把内存拖垮 */
    private static final int QUEUE_CAPACITY = 64;

    /**
     * ⚠️ 用 {@link ObjectProvider} 而不是直接注入 {@code ChatClient}：
     * 降级开关关掉时 {@code ChatClient} bean 根本不存在，直接注入会导致<b>启动失败</b> ——
     * 那正好违反工单验收 3「关掉开关应用照常启动」。
     */
    private final ObjectProvider<ChatClient> chatClientProvider;

    private final AiProperties properties;

    private final ExecutorService executor;

    public AiChatGateway(ObjectProvider<ChatClient> chatClientProvider, AiProperties properties) {
        this.chatClientProvider = chatClientProvider;
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "opsdesk-ai-call");
                    thread.setDaemon(true);   // 不阻止 JVM 退出
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 统一调用出口：把「拿 ChatClient 做一件事」包起来，负责可用性、超时与异常语义。
     *
     * <pre>{@code
     * String json = gateway.call("工单分析", client -> client.prompt()
     *         .user(description)
     *         .options(gateway.structuredOptions())
     *         .call()
     *         .content());
     * }</pre>
     *
     * @param scene  场景名，只用于日志与错误文案（如「工单分析」「知识库问答」）
     * @param action 真正要做的调用；可以正常抛 {@link BizException}（会被原样透传，不会被说成「AI 不可用」）
     * @return 调用结果
     * @throws BizException 50300 —— 开关关闭 / 客户端缺失 / 超时 / 上游报错
     */
    public <T> T call(String scene, Function<ChatClient, T> action) {
        ChatClient client = requireClient();
        long timeoutMillis = properties.getTotalTimeout().toMillis();
        long startNanos = System.nanoTime();
        try {
            T result = CompletableFuture
                    .supplyAsync(() -> action.apply(client), executor)
                    .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .join();
            log.debug("[AI 调用] scene={} 成功，耗时 {}ms", scene, elapsedMillis(startNanos));
            return result;
        }
        catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            // ⚠️ 业务异常要原样透传：调用方自己抛的 BizException（如参数校验失败）
            //    跟「AI 不可用」是两回事，混成 50300 会把排查方向带偏
            if (cause instanceof BizException bizException) {
                throw bizException;
            }
            if (cause instanceof TimeoutException) {
                log.error("[AI 调用] scene={} 超过总超时 {}ms，已放弃等待（底层连接由 {}ms 读超时回收）",
                        scene, timeoutMillis, properties.getReadTimeout().toMillis());
                throw BizException.serviceUnavailable(
                        "AI 调用超时（" + scene + "，上限 " + timeoutMillis + "ms）");
            }
            log.error("[AI 调用] scene={} 失败，耗时 {}ms", scene, elapsedMillis(startNanos), cause);
            throw BizException.serviceUnavailable("AI 服务不可用（" + scene + "）：" + rootMessage(cause));
        }
        catch (RejectedExecutionException ex) {
            // 有界队列满：快速失败，让调用方稍后重试，而不是无声积压
            log.error("[AI 调用] scene={} 被拒绝：AI 调用线程池已满（{}+{}）", scene, POOL_SIZE, QUEUE_CAPACITY);
            throw BizException.serviceUnavailable("AI 服务繁忙，请稍后重试");
        }
    }

    /**
     * 取一个可用的 {@link ChatClient}；不可用则抛 {@code 50300}。
     *
     * <p>⚠️ 这是给「需要 ChatClient 完整 API」的场景（如 D11 的多轮对话）留的口子。
     * 用它就意味着<b>绕过了 {@link #call} 的总超时保护</b> —— 除非确有需要，优先用 {@link #call}。
     */
    public ChatClient requireClient() {
        if (!properties.isEnabled()) {
            throw BizException.serviceUnavailable("AI 功能已关闭（opsdesk.ai.enabled=false）");
        }
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null) {
            throw BizException.serviceUnavailable("AI 客户端未装配（检查 opsdesk.ai.enabled 与 API Key 配置）");
        }
        return client;
    }

    /**
     * 结构化输出用的低温度选项（§10.3：工单分析要返回可解析的 JSON）。
     *
     * <p>刻意<b>不做成 bean</b>：{@code ChatOptions} 是 Spring AI 的公共类型，
     * 往上下文里注册一个同类型 bean 可能影响其它自动配置的按类型注入。做成普通方法取用最安全。
     */
    public ChatOptions structuredOptions() {
        return DeepSeekChatOptions.builder()
                .temperature(properties.getStructuredTemperature())
                .build();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // ==================== 私有 ====================

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 取最内层的原因 —— 外层异常（如 RestClientException）的 message 常常没有信息量 */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
