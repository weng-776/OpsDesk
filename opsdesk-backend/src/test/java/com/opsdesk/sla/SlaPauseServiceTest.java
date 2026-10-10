package com.opsdesk.sla;

import com.opsdesk.sla.service.SlaPauseService;
import com.opsdesk.sla.service.impl.SlaPauseServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 暂停顺延计算验收测试（工单 D4-03，规格基线 §9.4）
 *
 * <p><b>纯单测</b>：{@link SlaPauseServiceImpl} 不碰 DB、不依赖 Spring ——
 * 顺延是本项目最容易算错的一处（精确时长 vs 分钟截断、null、负 delta），
 * 拆成纯函数后这些边界可以在这里钉死。
 *
 * <p>§9.4 原文：
 * <pre>
 * 退出暂停：delta = now - sla_paused_at
 *          resolution_deadline += delta
 *          sla_paused_minutes += delta（分钟）
 *          sla_paused_at = null
 * </pre>
 */
class SlaPauseServiceTest {

    private final SlaPauseService service = new SlaPauseServiceImpl();

    private static final LocalDateTime PAUSED_AT = LocalDateTime.of(2026, 10, 10, 10, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 12, 10, 0, 0);

    @Test
    @DisplayName("§9.4：暂停 2 分钟后恢复 → resolution_deadline 后移 2 分钟，累计分钟 +2")
    void 暂停两分钟顺延两分钟() {
        LocalDateTime now = PAUSED_AT.plusMinutes(2);

        SlaPauseService.ResumeOutcome outcome =
                service.resume(PAUSED_AT, DEADLINE, 0, now);

        assertThat(outcome.resolutionDeadline()).isEqualTo(DEADLINE.plusMinutes(2));
        assertThat(outcome.pausedMinutes()).isEqualTo(2);
        assertThat(outcome.paused()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("§9.4：deadline 用精确时长偏移，分钟数按分钟截断（90 秒 → +90 秒 / +1 分钟）")
    void 精确时长与分钟截断并存() {
        LocalDateTime now = PAUSED_AT.plusSeconds(90);

        SlaPauseService.ResumeOutcome outcome =
                service.resume(PAUSED_AT, DEADLINE, 0, now);

        assertThat(outcome.resolutionDeadline())
                .as("deadline 挪 90 秒，不截断").isEqualTo(DEADLINE.plusSeconds(90));
        assertThat(outcome.pausedMinutes())
                .as("分钟字段是统计口径，截断为 1").isEqualTo(1);
    }

    @Test
    @DisplayName("§9.4：累计暂停分钟是 +=（已有 5 分钟 + 本次 2 分钟 = 7）")
    void 累计暂停分钟累加() {
        SlaPauseService.ResumeOutcome outcome =
                service.resume(PAUSED_AT, DEADLINE, 5, PAUSED_AT.plusMinutes(2));

        assertThat(outcome.pausedMinutes()).isEqualTo(7);
    }

    @Test
    @DisplayName("边界：sla_paused_at 为 null（数据不一致）→ 按无暂停处理，deadline 不动、不抛异常")
    void 暂停起点为空时保守处理() {
        SlaPauseService.ResumeOutcome outcome =
                service.resume(null, DEADLINE, 3, PAUSED_AT.plusMinutes(10));

        assertThat(outcome.resolutionDeadline()).as("不移动 deadline").isEqualTo(DEADLINE);
        assertThat(outcome.pausedMinutes()).as("累计分钟保持原值").isEqualTo(3);
        assertThat(outcome.paused()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("边界：delta 为负（时钟回拨）→ 钳为 0，绝不把 deadline 往前挪")
    void 负时长钳为零() {
        LocalDateTime now = PAUSED_AT.minusMinutes(5);   // now 早于暂停起点

        SlaPauseService.ResumeOutcome outcome =
                service.resume(PAUSED_AT, DEADLINE, 1, now);

        assertThat(outcome.resolutionDeadline()).as("deadline 不能被往前挪").isEqualTo(DEADLINE);
        assertThat(outcome.pausedMinutes()).as("不扣减累计").isEqualTo(1);
        assertThat(outcome.paused()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("边界：resolution_deadline 为 null（该单无 SLA）→ 保持 null，不 NPE")
    void 无解决时限时不报错() {
        SlaPauseService.ResumeOutcome outcome =
                service.resume(PAUSED_AT, null, null, PAUSED_AT.plusMinutes(3));

        assertThat(outcome.resolutionDeadline()).isNull();
        assertThat(outcome.pausedMinutes()).as("null 按 0 起算").isEqualTo(3);
    }

    @Test
    @DisplayName("边界：sla_paused_minutes 为 null → 按 0 起算")
    void 累计分钟为空时按零起算() {
        SlaPauseService.ResumeOutcome outcome =
                service.resume(PAUSED_AT, DEADLINE, null, PAUSED_AT.plusMinutes(4));

        assertThat(outcome.pausedMinutes()).isEqualTo(4);
    }
}
