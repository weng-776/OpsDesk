package com.opsdesk.ai.config;

import com.opsdesk.ai.support.AiChatGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 调用基础设施装配（工单 D10-01，规格基线 §10.3 / §10.4 / §11.3 / §23.9）
 *
 * <h2>🔴 本类最重要的一件事：为什么注入的是 {@link DeepSeekChatModel} 而不是 {@code ChatClient.Builder}</h2>
 * <b>实测结论</b>：把自动配置的 {@code ChatClient.Builder} 注入进来会直接抛异常 ——
 * <pre>
 * No qualifying bean of type 'org.springframework.ai.chat.model.ChatModel' available:
 * expected single matching bean but found 2: deepSeekChatModel,openAiChatModel
 * </pre>
 * 根因：pom 里同时有 {@code spring-ai-starter-model-openai}（给通义 embedding 用）与
 * {@code spring-ai-starter-model-deepseek}，两者的 chat 自动配置都是
 * {@code @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "xx", matchIfMissing = true)}，
 * 而 yml 里<b>没有配</b> {@code spring.ai.model.chat} → <b>两边同时成立</b> → 上下文里出现两个 {@code ChatModel}。
 * 而 {@code ChatClientAutoConfiguration#chatClientBuilder} 是<b>按类型注入单个 ChatModel</b> 的。
 *
 * <h2>⚠️ 这个坑最阴的地方：启动时完全看不出来</h2>
 * {@code chatClientBuilder} 这个 bean 的 scope 是 <b>{@code prototype}</b> —— 注册时不实例化、依赖也不解析。
 * 于是「应用启动成功」+「条件评估报告里 {@code ChatClientAutoConfiguration matched}」会给人一切正常的错觉，
 * <b>直到真正注入它的那一刻才炸</b>。所以这里显式注入具体类型，绕开歧义。
 *
 * <p>可选的其它做法都被否掉了：
 * <ul>
 *   <li>配 {@code spring.ai.model.chat=deepseek} —— 要改 {@code application.yml}，超出本单允许改动；</li>
 *   <li>自定义一个 {@code @Primary ChatModel} bean —— 等于覆盖自动配置，副作用比收益大。</li>
 * </ul>
 * <p>那个多出来的 {@code openAiChatModel} <b>留着不动</b>：模型 bean 是懒的，不被调用就不会发网络请求
 * （本项目只拿 OpenAI 做 embedding），既不影响启动也不产生费用。
 */
@Configuration
public class AiClientConfiguration {

    /**
     * 全局唯一的 {@link ChatClient}。
     *
     * <p>用 {@code ChatClient.builder(model).build()} 这个静态入口自建 —— 与自动配置内部走的是同一条路，
     * 只是把「用哪个 model」这件事显式写出来。
     *
     * <p>⚠️ 这里<b>刻意不设</b> {@code defaultOptions(...)}：yml 里 0.8 的温度是给聊天助手用的全局默认，
     * 结构化输出要的低温度由 {@link AiChatGateway#structuredOptions()} 在调用处局部覆盖。
     */
    @Bean
    @ConditionalOnProperty(prefix = "opsdesk.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChatClient aiChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.builder(deepSeekChatModel).build();
    }

    /**
     * 统一调用出口（可用性 + 超时 + 异常转 50300）。
     *
     * <p>⚠️ 注入 {@link ObjectProvider} 而非 {@code ChatClient}：降级开关关掉时上面那个 bean 不存在，
     * 直接注入会让应用<b>启动失败</b> —— 而工单验收 3 明确要求「关掉开关应用照常启动、AI 接口降级」。
     */
    @Bean(destroyMethod = "close")
    public AiChatGateway aiChatGateway(ObjectProvider<ChatClient> chatClientProvider,
                                       AiProperties aiProperties) {
        return new AiChatGateway(chatClientProvider, aiProperties);
    }

    /**
     * 给 LLM 的 HTTP 客户端加上连接/读取超时（5s / 10s）。
     *
     * <h2>⚠️ 为什么不自己定义 {@code RestClient.Builder} bean（覆盖自动配置）</h2>
     * Spring AI 的 {@code DeepSeekChatAutoConfiguration#deepSeekApi(...)} 是从上下文里取
     * {@code ObjectProvider<RestClient.Builder>} 用的 —— 也就是 Boot 自动配置的那个 builder。
     * 自定义 builder bean 会把它整个顶掉（连带丢掉 Boot 的 requestFactory 探测、SSL 等设置），
     * 属于「为了一笔改动覆盖一整套自动配置」。
     *
     * <p>用 {@link RestClientCustomizer} 只是「在既有 builder 上再加一笔」，不动其它任何设置 ——
     * 与 D9-04 用 {@code RabbitTemplateCustomizer} 接 Confirm 回调是同一个模式。
     *
     * <p>已反编译核实 Boot 3.5.16 的 {@code RestClientBuilderConfigurer#configure} 顺序是
     * <b>先 {@code requestFactory(默认)}、后 {@code applyCustomizers(...)}</b>，
     * 所以这里再设一次 {@code requestFactory} <b>会覆盖生效</b>。
     *
     * <h2>⚠️ 影响面（已确认可控）</h2>
     * {@code RestClientCustomizer} 会作用于<b>所有</b> Boot 托管的 {@code RestClient}。
     * 已 grep 确认本项目目前只有 Spring AI 用（{@code DeadLetterServiceImpl} 用的是静态
     * {@code RestClient.builder()}，不经过 Boot 的 builder，不受影响）。
     */
    @Bean
    public RestClientCustomizer aiTimeoutCustomizer(AiProperties aiProperties) {
        return builder -> {
            ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                    .withConnectTimeout(aiProperties.getConnectTimeout())
                    .withReadTimeout(aiProperties.getReadTimeout());
            builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
        };
    }
}
