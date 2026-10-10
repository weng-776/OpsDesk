package com.opsdesk.sla;

import com.opsdesk.common.enums.SlaState;
import com.opsdesk.sla.entity.SlaPolicy;
import com.opsdesk.sla.service.SlaCalculator;
import com.opsdesk.sla.service.SlaPolicyService;
import com.opsdesk.ticket.entity.Ticket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SLA 状态计算验收测试（工单 D5-02，规格基线 §9.5 / §9.3）
 *
 * <p><b>纯单测，不起 Spring 上下文</b>：§9.5 的判定收口在 {@link SlaCalculator#judge} /
 * {@link SlaCalculator#judgeResponse} 两个静态纯函数里；「按 {@code sla_policy_id} 取 minutes」
 * 这一层用 Mockito 把 {@link SlaPolicyService} 挡掉 —— 于是边界（恰好到期、恰好 20%、
 * 已响应后判定冻结、total 缺失）可以在这里逐条钉死。
 *
 * <p>§9.5 原文：
 * <pre>
 * total     = response_minutes 或 resolution_minutes
 * remaining = deadline - now
 * remaining &lt;= 0                → BREACHED
 * 0 &lt; remaining &lt;= 20% × total  → WARNING
 * 否则                           → NORMAL
 * </pre>
 */
class SlaCalculatorTest {

    /** 判定基准时刻：2026-10-10 12:00 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0, 0);

    /** P1 的解决时限：240 分钟 → 20% 带 = 48 分钟 */
    private static final int RESOLUTION_MINUTES = 240;
    /** P2 的响应时限：120 分钟 */
    private static final int RESPONSE_MINUTES = 120;

    private final SlaPolicyService slaPolicyService = mock(SlaPolicyService.class);
    private final SlaCalculator calculator = new SlaCalculator(slaPolicyService);

    // ==================== 验收 1：三种状态判定 ====================

    @Test
    @DisplayName("验收1：deadline 已过 → BREACHED")
    void 已超时判BREACHED() {
        LocalDateTime deadline = NOW.minusMinutes(1);
        assertThat(SlaCalculator.judge(RESOLUTION_MINUTES, deadline, NOW)).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("验收1：剩 10%（24 分钟）→ WARNING")
    void 剩百分之十判WARNING() {
        LocalDateTime deadline = NOW.plusMinutes(24);
        assertThat(SlaCalculator.judge(RESOLUTION_MINUTES, deadline, NOW)).isEqualTo(SlaState.WARNING);
    }

    @Test
    @DisplayName("验收1：剩 50%（120 分钟）→ NORMAL")
    void 剩百分之五十判NORMAL() {
        LocalDateTime deadline = NOW.plusMinutes(120);
        assertThat(SlaCalculator.judge(RESOLUTION_MINUTES, deadline, NOW)).isEqualTo(SlaState.NORMAL);
    }

    // ==================== 边界（§9.5 原文是 <=，两侧都含）====================

    @Test
    @DisplayName("边界：remaining 恰好为 0 → BREACHED（原文是 <=，恰好到期算超时）")
    void 恰好到期判BREACHED() {
        assertThat(SlaCalculator.judge(RESOLUTION_MINUTES, NOW, NOW)).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("边界：remaining 恰好 20%（48 分钟）→ WARNING；多 1 秒 → NORMAL")
    void 恰好百分之二十判WARNING() {
        assertThat(SlaCalculator.judge(RESOLUTION_MINUTES, NOW.plusMinutes(48), NOW))
                .as("原文是 <= 20%，恰好压线算 WARNING").isEqualTo(SlaState.WARNING);
        assertThat(SlaCalculator.judge(RESOLUTION_MINUTES, NOW.plusMinutes(48).plusSeconds(1), NOW))
                .as("越过 20% 带 → NORMAL").isEqualTo(SlaState.NORMAL);
    }

    @Test
    @DisplayName("边界：total 缺失/≤0 → 退化为 BREACHED/NORMAL 二态（算不出 WARNING 带）")
    void total缺失时退化二态() {
        LocalDateTime deadline = NOW.plusMinutes(24);
        assertThat(SlaCalculator.judge(null, deadline, NOW)).isEqualTo(SlaState.NORMAL);
        assertThat(SlaCalculator.judge(0, deadline, NOW)).isEqualTo(SlaState.NORMAL);
        // 超时那一侧不受影响
        assertThat(SlaCalculator.judge(null, NOW.minusMinutes(1), NOW)).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("边界：total 很小时 20% 带不因整数除法被截成 0（1 分钟 → 带 = 12 秒）")
    void 短时长不丢精度() {
        assertThat(SlaCalculator.judge(1, NOW.plusSeconds(12), NOW))
                .as("1 分钟的 20% = 12 秒，恰好压线 → WARNING").isEqualTo(SlaState.WARNING);
        assertThat(SlaCalculator.judge(1, NOW.plusSeconds(13), NOW)).isEqualTo(SlaState.NORMAL);
    }

    @Test
    @DisplayName("边界：deadline 为 null（该单无 SLA）→ 返回 null，不判定")
    void 无SLA不判定() {
        assertThat(SlaCalculator.judge(RESOLUTION_MINUTES, null, NOW)).isNull();
        assertThat(SlaCalculator.judgeResponse(null, null, RESPONSE_MINUTES, NOW)).isNull();
    }

    // ==================== 验收 2：响应线的非对称规则（§9.3）====================

    @Test
    @DisplayName("验收2：first_response_at 为空且响应 deadline 已过 → BREACHED")
    void 未响应且已超时判BREACHED() {
        LocalDateTime responseDeadline = NOW.minusMinutes(5);
        assertThat(SlaCalculator.judgeResponse(responseDeadline, null, RESPONSE_MINUTES, NOW))
                .isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("响应线：未响应但还剩 10% → WARNING（走 §9.5 倒计时）")
    void 未响应但临近超时判WARNING() {
        LocalDateTime responseDeadline = NOW.plusMinutes(12);   // 120 分钟的 10%
        assertThat(SlaCalculator.judgeResponse(responseDeadline, null, RESPONSE_MINUTES, NOW))
                .isEqualTo(SlaState.WARNING);
    }

    @Test
    @DisplayName("响应线：已响应且准时 → NORMAL；已响应但迟到 → BREACHED（§9.3 达标/未达标）")
    void 已响应按达标与否冻结() {
        LocalDateTime responseDeadline = NOW.plusMinutes(30);

        assertThat(SlaCalculator.judgeResponse(responseDeadline, NOW.minusMinutes(10), RESPONSE_MINUTES, NOW))
                .as("响应时间早于 deadline → 达标").isEqualTo(SlaState.NORMAL);
        assertThat(SlaCalculator.judgeResponse(responseDeadline, responseDeadline.plusMinutes(1), RESPONSE_MINUTES, NOW))
                .as("响应时间晚于 deadline → 未达标（迟到要留痕）").isEqualTo(SlaState.BREACHED);
        assertThat(SlaCalculator.judgeResponse(responseDeadline, responseDeadline, RESPONSE_MINUTES, NOW))
                .as("恰好压线 → 达标（§9.3 是 <=）").isEqualTo(SlaState.NORMAL);
    }

    @Test
    @DisplayName("⚠️ 响应线已响应后判定【冻结】：时间再流逝也不会翻成 BREACHED")
    void 已响应后不再倒计时() {
        LocalDateTime responseDeadline = NOW.plusMinutes(30);
        LocalDateTime firstResponseAt = NOW.minusMinutes(10);   // 准时响应

        // 响应发生之后的任何时刻，判定都不该变 —— 已达成的 deadline 不可能再超时
        assertThat(SlaCalculator.judgeResponse(responseDeadline, firstResponseAt, RESPONSE_MINUTES, NOW))
                .isEqualTo(SlaState.NORMAL);
        assertThat(SlaCalculator.judgeResponse(responseDeadline, firstResponseAt, RESPONSE_MINUTES,
                NOW.plusDays(3)))
                .as("三天后再判仍是 NORMAL（若照搬 remaining = deadline - now 会误判 BREACHED）")
                .isEqualTo(SlaState.NORMAL);
        assertThat(SlaCalculator.judgeResponse(responseDeadline, firstResponseAt, RESPONSE_MINUTES,
                NOW.plusYears(1)))
                .isEqualTo(SlaState.NORMAL);
    }

    // ==================== 验收 3：两条线互不影响 ====================

    @Test
    @DisplayName("验收3：响应线 BREACHED 而解决线 NORMAL（响应迟到、解决还很宽裕）")
    void 响应超时不影响解决线() {
        // P1：响应 30 分钟 / 解决 240 分钟
        SlaPolicy policy = policy(1L, RESPONSE_MINUTES, RESOLUTION_MINUTES);
        when(slaPolicyService.getById(1L)).thenReturn(policy);

        Ticket ticket = new Ticket();
        ticket.setId(100L);
        ticket.setSlaPolicyId(1L);
        ticket.setResponseDeadline(NOW.plusMinutes(10));
        ticket.setFirstResponseAt(NOW.plusMinutes(30));    // 迟到 → 响应 BREACHED
        ticket.setResolutionDeadline(NOW.plusMinutes(120)); // 剩 50% → NORMAL

        SlaCalculator.SlaOutcome outcome = calculator.compute(ticket, NOW);

        assertThat(outcome.responseState()).isEqualTo(SlaState.BREACHED);
        assertThat(outcome.resolutionState())
                .as("响应线超时不应把解决线也拉成 BREACHED").isEqualTo(SlaState.NORMAL);
    }

    @Test
    @DisplayName("验收3：响应线 NORMAL 而解决线 BREACHED（响应很及时、解决拖过了）")
    void 解决超时不影响响应线() {
        SlaPolicy policy = policy(1L, RESPONSE_MINUTES, RESOLUTION_MINUTES);
        when(slaPolicyService.getById(1L)).thenReturn(policy);

        Ticket ticket = new Ticket();
        ticket.setId(101L);
        ticket.setSlaPolicyId(1L);
        ticket.setResponseDeadline(NOW.plusMinutes(10));
        ticket.setFirstResponseAt(NOW.minusMinutes(5));     // 准时 → NORMAL
        ticket.setResolutionDeadline(NOW.minusMinutes(1));  // 已过 → BREACHED

        SlaCalculator.SlaOutcome outcome = calculator.compute(ticket, NOW);

        assertThat(outcome.responseState()).isEqualTo(SlaState.NORMAL);
        assertThat(outcome.resolutionState()).isEqualTo(SlaState.BREACHED);
    }

    // ==================== compute / computeBatch ====================

    @Test
    @DisplayName("compute：total 取自【冻结的策略版本】（sla_policy_id），而不是 deadline - created_at")
    void total取自冻结策略版本() {
        // 策略 1 是 P1 的旧版本：响应 30 / 解决 240
        when(slaPolicyService.getById(1L)).thenReturn(policy(1L, 30, 240));

        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setSlaPolicyId(1L);
        ticket.setResponseDeadline(NOW.plusMinutes(5));      // 30 分钟的 16.7% → WARNING
        ticket.setResolutionDeadline(NOW.plusMinutes(60));   // 240 分钟的 25% → NORMAL

        SlaCalculator.SlaOutcome outcome = calculator.compute(ticket, NOW);

        assertThat(outcome.responseState()).as("5/30 = 16.7% ≤ 20%").isEqualTo(SlaState.WARNING);
        assertThat(outcome.resolutionState()).as("60/240 = 25% > 20%").isEqualTo(SlaState.NORMAL);
    }

    @Test
    @DisplayName("compute：策略版本查不到 → 不抛异常，按无 total 退化（只判超时与否）")
    void 策略缺失时退化不抛异常() {
        when(slaPolicyService.getById(999L)).thenReturn(null);

        Ticket ticket = new Ticket();
        ticket.setId(2L);
        ticket.setSlaPolicyId(999L);
        ticket.setResolutionDeadline(NOW.minusMinutes(1));

        SlaCalculator.SlaOutcome outcome = calculator.compute(ticket, NOW);

        assertThat(outcome.responseState()).as("没有 response_deadline → null").isNull();
        assertThat(outcome.resolutionState()).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("compute(null) → 空结果（两条线都是 null），不抛异常")
    void 空工单返回空结果() {
        assertThat(calculator.compute(null, NOW)).isEqualTo(SlaCalculator.SlaOutcome.NONE);
    }

    @Test
    @DisplayName("computeBatch：一次取回所有策略版本，结果与逐条 compute 完全一致")
    void 批量与单条一致() {
        Map<Long, SlaPolicy> policies = Map.of(
                1L, policy(1L, 30, 240),
                2L, policy(2L, 120, 480));
        // 批量走 listByIds；单条走 getById —— 两边都要打桩，才能比对一致性
        when(slaPolicyService.listByIds(anyCollection()))
                .thenAnswer(inv -> List.copyOf(policies.values()));
        when(slaPolicyService.getById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> policies.get(inv.getArgument(0)));

        Ticket p1 = ticket(1L, 1L, NOW.plusMinutes(5), NOW.plusMinutes(60));
        Ticket p2 = ticket(2L, 2L, NOW.plusMinutes(10), NOW.minusMinutes(1));

        Map<Long, SlaCalculator.SlaOutcome> batch = calculator.computeBatch(List.of(p1, p2), NOW);

        assertThat(batch).containsOnlyKeys(1L, 2L);
        assertThat(batch.get(1L)).isEqualTo(calculator.compute(p1, NOW));
        assertThat(batch.get(2L)).isEqualTo(calculator.compute(p2, NOW));
        assertThat(batch.get(2L).resolutionState()).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("computeBatch：空列表 / null → 空 Map（且不去查策略）")
    void 批量空输入返回空Map() {
        assertThat(calculator.computeBatch(null, NOW)).isEmpty();
        assertThat(calculator.computeBatch(List.of(), NOW)).isEmpty();
    }

    // ==================== 工具 ====================

    private static SlaPolicy policy(Long id, Integer responseMinutes, Integer resolutionMinutes) {
        SlaPolicy policy = new SlaPolicy();
        policy.setId(id);
        policy.setResponseMinutes(responseMinutes);
        policy.setResolutionMinutes(resolutionMinutes);
        return policy;
    }

    private static Ticket ticket(Long id, Long policyId,
                                 LocalDateTime responseDeadline, LocalDateTime resolutionDeadline) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setSlaPolicyId(policyId);
        ticket.setResponseDeadline(responseDeadline);
        ticket.setResolutionDeadline(resolutionDeadline);
        return ticket;
    }
}
