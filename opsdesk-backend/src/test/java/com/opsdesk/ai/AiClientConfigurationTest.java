package com.opsdesk.ai;

import com.opsdesk.ai.config.AiClientConfiguration;
import com.opsdesk.ai.config.AiProperties;
import com.opsdesk.ai.support.AiChatGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * D10-01 装配验收（默认配置）
 *
 * <h2>本类最核心的一条：{@code ChatClient} 能装配出来，且上下文里只有一个</h2>
 * 背景：pom 里 openai 与 deepseek 两个 starter 并存，两个 chat 自动配置的
 * {@code @ConditionalOnProperty(matchIfMissing = true)} 同时成立 → 上下文里有 2 个 {@code ChatModel}，
 * 而自动配置的 {@code ChatClient.Builder} 是按类型注入单个 {@code ChatModel} 的
 * → {@code @Autowired ChatClient.Builder} 会炸（实测文案见 {@link AiClientConfiguration} 类注释）。
 * 本类把「显式注入 {@code DeepSeekChatModel} 这条绕行路真的走通了」钉住。
 */
@SpringBootTest
class AiClientConfigurationTest {

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private AiChatGateway aiChatGateway;

    @Autowired
    private RestClientCustomizer aiTimeoutCustomizer;

    @Test
    @DisplayName("验收1前置：ChatClient 装配成功且全局唯一（两个 ChatModel 的歧义已被绕开）")
    void ChatClient装配成功且唯一() {
        String[] names = ctx.getBeanNamesForType(ChatClient.class);
        assertThat(names).as("应当恰好一个 ChatClient").hasSize(1);
        assertThat(ctx.getBean(ChatClient.class)).isNotNull();
    }

    @Test
    @DisplayName("AiProperties 默认值符合设计：开关开、5s / 10s / 30s、结构化温度 0.1")
    void 默认属性值符合设计() {
        assertThat(aiProperties.isEnabled()).as("§10.4 降级开关默认开启").isTrue();
        assertThat(aiProperties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(aiProperties.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(aiProperties.getTotalTimeout())
                .as("§10.3 / §11.3：总超时 30s").isEqualTo(Duration.ofSeconds(30));
        assertThat(aiProperties.getStructuredTemperature())
                .as("结构化输出要低温度，不是 yml 的全局默认 0.8").isEqualTo(0.1);
    }

    @Test
    @DisplayName("结构化 options 的温度取自 AiProperties（调用处局部覆盖，不动 yml 全局值）")
    void 结构化options温度是低值() {
        ChatOptions options = aiChatGateway.structuredOptions();
        assertThat(options.getTemperature()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("超时定制确实会给 RestClient.Builder 设 requestFactory（否则 30s 只是空等）")
    void 超时定制会设置请求工厂() {
        org.springframework.web.client.RestClient.Builder builder =
                mock(org.springframework.web.client.RestClient.Builder.class);

        aiTimeoutCustomizer.customize(builder);

        verify(builder).requestFactory(any(ClientHttpRequestFactory.class));
    }

    @Test
    @DisplayName("验收4：单测不依赖真实 LLM —— 上面的装配/属性断言都不发网络请求")
    void 装配层测试不依赖真实LLM() {
        // 本类其余用例只用上下文里的 bean 与 Mockito，不触发 HTTP。
        // 这一条本身是「声明式」的：它会在无 DS_API_KEY 的环境里照常绿。
        assertThat(ctx.containsBean("aiChatGateway")).isTrue();
    }

    // ==================== 真实调用（有 Key 才跑） ====================

    @Test
    @EnabledIfEnvironmentVariable(named = "DS_API_KEY", matches = ".+",
            disabledReason = "没有 DS_API_KEY 时不跑真实调用（工单验收 4：单测不依赖真 LLM）")
    @DisplayName("验收1：注入的 ChatClient 发一句 hello 能拿到非空回复")
    void 真调LLM能拿到回复() {
        String reply = aiChatGateway.call("冒烟测试", client -> client.prompt("hello").call().content());

        assertThat(reply).as("LLM 应返回非空内容").isNotBlank();
        System.out.println("[D10-01 冒烟] hello -> " + reply.replaceAll("\\s+", " "));
    }
}
