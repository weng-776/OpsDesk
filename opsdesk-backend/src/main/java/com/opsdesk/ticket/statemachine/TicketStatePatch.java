package com.opsdesk.ticket.statemachine;

import com.opsdesk.common.enums.SlaState;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 一次状态流转要写的「附加列」（工单 D4-04 引入）
 *
 * <p>背景：{@code ticket} 表上跟随状态一起变的列越来越多 ——
 * D4-01 是 {@code assignee_id} / {@code first_response_at}；
 * D4-03 加 {@code sla_paused_at} / {@code resolution_deadline} / {@code sla_paused_minutes}；
 * D4-04 再加 {@code resolved_at} / {@code closed_at} / {@code reopen_count} /
 * {@code sla_resolution_state} / {@code sla_warning_notified} / {@code sla_breach_notified}；
 * D4-05 还要 {@code cancel_reason}。
 *
 * <p>继续往 {@code updateStatusCas} 加位置参数会失控（十几个 {@code null} 连排，读不出谁是谁），
 * 所以这些「可选列」收进本对象，用 {@code @Builder} 具名赋值。
 *
 * <h2>约定：{@code null} = 不改这一列</h2>
 * XML 里每列都是 {@code <if test="patch.xxx != null">}，所以<b>没 set 的列不会被写</b>，
 * 保持原值。唯一的例外是 {@code clearSlaPausedAt} —— 它要表达「置 NULL」，
 * 而 {@code <if test="x != null">} 表达不了置空，所以单独用一个布尔标志位。
 *
 * <p>⚠️ 本对象<b>不含 {@code response_deadline}</b>：§9.4 明确响应时限不参与顺延。
 * 不提供这个入口，就不可能在流转时误伤它（结构性保证）。
 *
 * <p>⚠️ 本对象<b>不含 {@code status}</b>：状态由 {@link TicketStateMachine} 判定后单独传，
 * 不允许调用方绕过状态机自己写状态。
 */
@Getter
@Builder(toBuilder = true)
public class TicketStatePatch {

    // ---------- D4-01：分派 / 受理 / 开始 ----------

    /** 新的处理人（assign / accept / transfer） */
    private Long assigneeId;

    /** 首次响应时间（§9.3：仅「若空」时才传值） */
    private LocalDateTime firstResponseAt;

    // ---------- D4-03：SLA 暂停与顺延（§9.4） ----------

    /** 写入 {@code sla_paused_at}（hold / resolve） */
    private LocalDateTime slaPausedAt;

    /** 置 {@code sla_paused_at = NULL}（resume / close / reject —— 退出暂停态） */
    @Builder.Default
    private boolean clearSlaPausedAt = false;

    /** 新的解决截止（resume / close / reject） */
    private LocalDateTime resolutionDeadline;

    /** 新的累计暂停分钟（resume / close / reject） */
    private Integer slaPausedMinutes;

    // ---------- D4-04：解决 / 关闭 / 驳回 ----------

    /** 进入 WAITING_CONFIRM 的时间（resolve） */
    private LocalDateTime resolvedAt;

    /** 关闭时间（close） */
    private LocalDateTime closedAt;

    /** 重新打开次数（reject，绝对新值） */
    private Integer reopenCount;

    /** 解决 SLA 状态（reject 重置为 NORMAL，§9.6） */
    private SlaState slaResolutionState;

    /** 临近超时提醒标志（reject 归零，§9.6） */
    private Integer slaWarningNotified;

    /** 超时提醒标志（reject 归零，§9.6） */
    private Integer slaBreachNotified;

    // ---------- D4-05：撤销 ----------

    /** 撤销原因（cancel，**必填**） */
    private String cancelReason;

    /** 空补丁：什么都不改（理论上用不到，留作显式表达） */
    public static TicketStatePatch none() {
        return TicketStatePatch.builder().build();
    }
}
