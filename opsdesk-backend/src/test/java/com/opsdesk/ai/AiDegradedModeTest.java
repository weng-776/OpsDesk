package com.opsdesk.ai;

import com.opsdesk.ai.support.AiChatGateway;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D10-01 验收 3：关掉降级开关（{@code opsdesk.ai.enabled=false}）
 *
 * <h2>⚠️ 这里验的是「关掉开关后应用照常启动」，而不是「bean 直接消失导致启动失败」</h2>
 * 最直觉的写法是把 {@code ChatClient} 用 {@code @ConditionalOnProperty} 干掉就完事 ——
 * 但那样任何注入了 {@code ChatClient} 的服务（D10-02 起就有了）都会因为找不到 bean 而
 * <b>启动失败</b>，与「关掉后应用正常、AI 接口降级」的验收直接冲突。
 * 所以装配侧用 {@code ObjectProvider} 容忍缺失，由 {@link AiChatGateway} 在调用时返回 50300。
 */
@SpringBootTest(properties = "opsdesk.ai.enabled=false")
class AiDegradedModeTest {

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private AiChatGateway aiChatGateway;

    @Test
    @DisplayName("验收3：开关关闭 → 应用正常启动（本测试类能跑起来本身就是证据）")
    void 开关关闭后应用照常启动() {
        assertThat(ctx.containsBean("aiChatGateway"))
                .as("出口 bean 必须在，否则调用方注入失败 → 启动失败").isTrue();
        assertThat(ctx.getBeanNamesForType(ChatClient.class))
                .as("开关关闭时不装配 ChatClient（不碰 LLM）").isEmpty();
    }

    @Test
    @DisplayName("验收3：开关关闭 → AI 调用返回 50300（走降级，不是 50000）")
    void 开关关闭后调用返回50300() {
        assertThatThrownBy(() -> aiChatGateway.call("工单分析", client -> "不该走到这里"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .as("§10.4 降级 + §20.2 依赖不可用 → 50300").isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessageContaining("已关闭");
    }
}
