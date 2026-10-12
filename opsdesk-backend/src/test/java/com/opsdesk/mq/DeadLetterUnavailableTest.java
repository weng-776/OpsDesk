package com.opsdesk.mq;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.mq.dto.DeadLetterQuery;
import com.opsdesk.mq.service.DeadLetterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验收 4：RabbitMQ 不可用时死信接口返回 {@code 50300}（不是 50000）
 *
 * <h2>怎么在不真停 broker 的情况下验</h2>
 * 把管理 API 的地址指向一个<b>必然连不上的端口</b>（{@code localhost:1}）。
 * 管理端口的连通性就是「MQ 可不可用」的判据 —— 连不上 → {@code RestClientException}
 * → 必须转成 {@code 50300}（§20.2：依赖服务不可用），而不是让它冒成 50000。
 *
 * <p>⚠️ 单独一个测试类 + {@code @TestPropertySource}：这个属性会改变
 * {@code DeadLetterServiceImpl} 的构造，不能污染其它用例的上下文。
 */
@SpringBootTest
@TestPropertySource(properties = "opsdesk.mq.management-base-url=http://localhost:1")
class DeadLetterUnavailableTest {

    @Autowired
    private DeadLetterService deadLetterService;

    @Test
    @DisplayName("验收4：管理接口连不上 → 50300（不是 50000），且异常信息能定位到原因")
    void MQ不可用返回50300() {
        assertThatThrownBy(() -> deadLetterService.page(new DeadLetterQuery()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .as("§20.2：依赖服务不可用 → 50300").isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessageContaining("RabbitMQ 管理接口不可用");
    }

    @Test
    @DisplayName("重投同样走 50300（定位也要读管理接口）")
    void 重投时MQ不可用也返回50300() {
        assertThatThrownBy(() -> deadLetterService.requeue(1))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
