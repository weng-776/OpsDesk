package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 工单状态（规格基线 §3.5）
 *
 * <p>⚠️ 本枚举**只承载状态本身的数据**（code / 名称 / 是否终态）。
 * <b>状态流转逻辑一律不在这里</b> —— 流转规则由 {@code ticket} 模块的
 * {@code TicketStateMachine} 实现（§7.2 状态 × 角色 × 动作矩阵是权威）。
 * 这是 SOP §5 的红区约定，改动流转必须人工逐行审阅。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code WAITING_USER} / {@code WAITING_CONFIRM} 期间 **SLA 暂停并顺延**（§9.4）</li>
 *   <li>{@code REOPENED} 是**持久状态**，只能由 {@code WAITING_CONFIRM} 触发，
 *       且必须再执行一次 {@code start} 才回到 {@code IN_PROGRESS}（§7.3）</li>
 * </ul>
 */
@Getter
public enum TicketStatus implements CodeEnum {

    /** 待受理：已创建，等待 IT 受理（公共池） */
    OPEN("待受理", false),

    /** 已分派：已明确处理人，尚未开始处理 */
    ASSIGNED("已分派", false),

    /** 处理中：处理人已开始处理 */
    IN_PROGRESS("处理中", false),

    /** 待用户补充：等待员工补充信息，SLA 暂停 */
    WAITING_USER("待用户补充", false),

    /** 待确认：IT 已解决，等待员工确认，SLA 暂停 */
    WAITING_CONFIRM("待确认", false),

    /** 已重新打开：员工确认未解决，等待处理人再次接手 */
    REOPENED("已重新打开", false),

    /** 已关闭：员工确认解决或管理员强制关闭 —— 终态 */
    CLOSED("已关闭", true),

    /** 已撤销：员工撤销或管理员撤销 —— 终态 */
    CANCELLED("已撤销", true);

    private final String label;

    /** 是否终态。终态工单不允许任何流转，命中一律返回 40900（§25.4） */
    private final boolean terminal;

    TicketStatus(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }
}
