package com.opsdesk.sla.service;

import com.opsdesk.common.enums.TicketPriority;

import java.time.LocalDateTime;

/**
 * 工单 SLA 计算（工单 D3-01 的「最小版」，Day 5 会重构为版本化 + 暂停顺延 + 定时扫描）
 *
 * <p>规格依据：规格基线 §6.1（创建时立即按 priority 算 SLA）、§9.2（起算点）、§9.1（策略版本化）。
 *
 * <p><b>「最小版」的边界</b>：本单只做「读当前 ACTIVE 策略 → created_at + minutes」。
 * 以下都留给 D5：
 * <ul>
 *   <li>SLA 状态计算（NORMAL / WARNING / BREACHED）</li>
 *   <li>挂起暂停与顺延（§9.4）、reopen 重算（§9.6）</li>
 *   <li>定时扫描 + 幂等标志（§9.5）</li>
 *   <li>策略版本化的完整语义（§9.7）</li>
 * </ul>
 * 但 {@code sla_policy_id} 这一条<b>现在就写</b> —— §9.1 要求记录「创建时适用的策略版本」用于追溯，
 * 事后补是补不回来的。
 */
public interface TicketSlaCalculator {

    /**
     * 按优先级取当前 ACTIVE 策略，算出两个 deadline。
     *
     * <pre>
     * response_deadline   = created_at + response_minutes
     * resolution_deadline = created_at + resolution_minutes
     * </pre>
     *
     * @param priority  工单优先级
     * @param createdAt 工单的创建时间（<b>必须传库里的 created_at</b>，§9.2 的权威口径是「从 created_at 起算」）
     * @return 命中的策略与 deadline；<b>该优先级没有 ACTIVE 策略时返回 {@code null}</b>
     *         —— 由调用方决定怎么办（D3-01 的选择是不阻断创建，见 {@code TicketCreateService}）
     */
    SlaSnapshot calculate(TicketPriority priority, LocalDateTime createdAt);

    /**
     * 一次 SLA 计算的产物。
     *
     * @param policyId           创建时适用的策略版本 id（§9.1 追溯用）
     * @param responseDeadline   响应截止
     * @param resolutionDeadline 解决截止
     */
    record SlaSnapshot(Long policyId, LocalDateTime responseDeadline, LocalDateTime resolutionDeadline) {
    }
}
