package com.opsdesk.ai;

import com.opsdesk.ai.support.AiChatGateway;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D10-01 验收 2 与超时策略的行为验证
 *
 * <h2>怎么在不依赖外网的前提下验「LLM 不可用」</h2>
 * 把 DeepSeek 的 base-url 指到一个<b>必然连不上的端口</b>（{@code localhost:1}）。
 * 同样是「LLM 不可用」，但不受外网、配额、Key 过期影响 —— 比真拿错 Key 去打 DeepSeek 稳定得多
 * （错 Key 验的是同一条代码路径：上游报错 → 50300）。
 *
 * <p>⚠️ 单独一个测试类 + 自己的 properties：这会改掉 {@code DeepSeekApi} 的 base-url，
 * 不能污染其它用例的上下文。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.ai.deepseek.chat.base-url=http://localhost:1",
        "spring.ai.deepseek.base-url=http://localhost:1",
        // 总超时压到 1s：本类只验「超时会不会被兜住」，不需要真等 30s（默认值由另一个测试类钉住）
        "opsdesk.ai.total-timeout=1s"
})
class AiUnavailableTest {

    @Autowired
    private AiChatGateway aiChatGateway;

    @Test
    @DisplayName("验收2：LLM 连不上 → BizException(50300)，不是 50000")
    void LLM不可达返回50300() {
        assertThatThrownBy(() -> aiChatGateway.call("工单分析",
                client -> client.prompt("hello").call().content()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    ErrorCode code = ((BizException) ex).getErrorCode();
                    assertThat(code).as("§20.2：依赖服务不可用 → 50300").isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(code).as("绝不能冒成 50000").isNotEqualTo(ErrorCode.SYSTEM_ERROR);
                });
    }

    @Test
    @DisplayName("超时策略：超过 totalTimeout 就放弃等待并返回 50300（不会一直挂着）")
    void 超过总超时则放弃等待() {
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> aiChatGateway.call("慢调用", client -> {
            try {
                Thread.sleep(10_000);   // 故意比 totalTimeout(1s) 慢得多
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "不该等到这行";
        }))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessageContaining("超时");

        long cost = System.currentTimeMillis() - start;
        assertThat(cost)
                .as("必须在 1s 总超时附近返回（实测 %dms），而不是跟着 action 等满 10s", cost)
                .isLessThan(3_000);
    }
}
