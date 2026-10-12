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
     * 重新打开后的新一轮解决时限（工单 D4-04，规格基线 §9.6）。
     *
     * <pre>
     * resolution_deadline = reopen 时间 + resolution_minutes
     * </pre>
     *
     * <p>⚠️ 用的是 <b>{@code ticket.sla_policy_id} 指向的那一版策略</b>，不是「当前 ACTIVE 的那版」——
     * §9.1/§9.7 规定 SLA 策略版本化：存量工单指向旧版本、不受新版本影响、可追溯。
     *
     * <p>注意这<b>不是</b>「在旧 deadline 上顺延」：§9.6 是<b>整轮重算</b>，
     * 与 §9.4 的暂停顺延（累加）语义不同。
     *
     * @param slaPolicyId 工单创建时冻结的策略版本 id
     * @param reopenAt    重新打开的时刻
     * @return 新的解决截止；<b>策略不存在或缺 {@code resolution_minutes} 时返回 {@code null}</b>
     *         （由调用方决定——D4-04 的选择是保持原 deadline 不动并记 warn，不破坏数据）
     */
    LocalDateTime reopenResolutionDeadline(Long slaPolicyId, LocalDateTime reopenAt);

    /**
     * 一次 SLA 计算的产物。
     *
     * @param policyId           创建时适用的策略版本 id（§9.1 追溯用）
     * @param responseDeadline   响应截止
     * @param resolutionDeadline 解决截止
     */
    record SlaSnapshot(Long policyId, LocalDateTime responseDeadline, LocalDateTime resolutionDeadline) {
    }

    /**
     * 优先级变更后的解决时限重算（§9.9，D10-05 补单）。
     *
     * <p>场景：AI 建议被采纳、或将来任何入口改了 {@code ticket.priority} ——
     * §9 的 deadline 是「创建时按当时 priority 算并落库」的，不改就会出现
     * 「优先级 P1、时限却是 P3 的」这种自相矛盾的组合。
     *
     * <h2>⚠️ 为什么是「差值平移」而不是按 created_at 绝对重算</h2>
     * 绝对重算（{@code created_at + 新分钟数}）会抹掉两样已有的修正：
     * <ul>
     *   <li>§9.4 的<b>暂停顺延</b>（{@code sla_paused_minutes} 是累加到 deadline 上的）；</li>
     *   <li>§9.6 <b>reopen 的新基准</b>（重开后 deadline = 「reopen 时间 + 分钟数」，不再是 created_at）。</li>
     * </ul>
     * 差值平移是在现有 deadline 上整体挪动，两样修正都完整保留：
     * <pre>
     * new_resolution_deadline = old_resolution_deadline
     *                         + (新策略.resolution_minutes - 旧策略.resolution_minutes)
     * </pre>
     *
     * <p><b>{@code response_deadline} 不参与</b>：§9.4 已确立「响应发生在早期」的先例（暂停顺延也不动它），
     * 且工单若已响应，重算会改写「是否达标」这一历史事实。
     *
     * @param oldPolicyId                工单当前冻结的策略版本 id（{@code ticket.sla_policy_id}）
     * @param newPriority                变更后的优先级
     * @param currentResolutionDeadline  工单当前的解决截止
     * @return 新的策略 id 与解决截止；<b>以下情况返回 {@code null}</b>（由调用方决定怎么办）：
     *         当前 deadline 为空（创建时就没算出来，没有可平移的基准）、
     *         旧策略查不到、新优先级没有 ACTIVE 策略、或新旧命中同一版策略（无需挪动）
     */
    RescheduleResult rescheduleForPriorityChange(Long oldPolicyId, TicketPriority newPriority,
                                                 LocalDateTime currentResolutionDeadline);

    /**
     * 优先级变更重算的产物。
     *
     * @param newPolicyId           新的策略版本 id（应写回 {@code ticket.sla_policy_id}）
     * @param newResolutionDeadline 平移后的解决截止
     */
    record RescheduleResult(Long newPolicyId, LocalDateTime newResolutionDeadline) {
    }
}
