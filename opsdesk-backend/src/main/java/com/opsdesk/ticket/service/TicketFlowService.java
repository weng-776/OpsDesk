package com.opsdesk.ticket.service;

import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.vo.TicketDetailVO;

/**
 * 工单状态流转（工单 D4-01 起）
 *
 * <p>规格依据：规格基线 §7.2 状态 × 角色 × 动作矩阵、§7.4；API 文档 §8.12。
 *
 * <h2>所有流转方法的统一契约（§8.12 抬头）</h2>
 * <pre>
 * 状态前置校验 → 权限校验 → 数据范围校验 → 写历史 → 写审计 → 改 SLA → 发事件
 * </pre>
 * 其中「写审计 / 发事件 / 改 SLA」分属 Day 6 / D4-03，本单只做前三步 + 写历史。
 *
 * <p>返回 {@link TicketDetailVO}（最新工单快照）—— §8.12「返回最新工单快照，便于前端直接刷新」。
 *
 * <p>本接口按<b>动作</b>命名方法，而不是按状态：状态机才是权威，
 * 「谁在什么状态能做什么」由 {@code TicketStateMachine} 决定，不在这里判断。
 */
public interface TicketFlowService {

    /**
     * 分派工单（矩阵 #2）：{@code OPEN → ASSIGNED}，可指定处理人。
     *
     * <p>权限：{@code ticket:assign}；角色 AGENT / ADMIN。
     *
     * @param ticketId 工单 id
     * @param dto      含 {@code assigneeId}；被分派人必须是启用的 AGENT / ADMIN
     * @return 流转后的工单快照
     */
    TicketDetailVO assign(Long ticketId, TicketAssignDTO dto);

    /**
     * 受理工单（矩阵 #3）：{@code OPEN → ASSIGNED}，{@code assignee_id = 当前用户}。
     *
     * <p>权限：{@code ticket:accept}；角色 AGENT / ADMIN。
     *
     * @return 流转后的工单快照
     */
    TicketDetailVO accept(Long ticketId);

    /**
     * 开始处理（矩阵 #5 与 #13）：{@code ASSIGNED → IN_PROGRESS}，或
     * {@code REOPENED → IN_PROGRESS}（驳回后处理人再次接手，沿用原 assignee）。
     *
     * <p>权限：{@code ticket:process}；仅当前 assignee 或 ADMIN。
     *
     * @return 流转后的工单快照
     */
    TicketDetailVO start(Long ticketId);

    /**
     * 转派工单（矩阵 #6）：{@code ASSIGNED → ASSIGNED}，只换处理人，<b>状态不变</b>。
     *
     * <p>权限：{@code ticket:transfer}；角色 AGENT / ADMIN（矩阵 #6 不要求「必须是当前处理人」）。
     *
     * <p>⚠️ {@code first_response_at} <b>保持不变</b> —— 工单已经响应过了，转派不重置响应时点
     * （§6.2 转派语义同分派，但用于已分派工单更换处理人；响应已发生，不该退回未响应）。
     *
     * @param ticketId 工单 id
     * @param dto      含 {@code assigneeId}；新处理人必须是启用的 AGENT / ADMIN
     * @return 流转后的工单快照
     */
    TicketDetailVO transfer(Long ticketId, TicketAssignDTO dto);

    /**
     * 挂起工单（矩阵 #8）：{@code IN_PROGRESS → WAITING_USER}，<b>SLA 进入暂停</b>。
     *
     * <p>权限：{@code ticket:process}；仅当前 assignee 或 ADMIN。无请求体。
     *
     * <p>副作用（§9.4）：{@code sla_paused_at = now}。
     *
     * <p>幂等：已在 {@code WAITING_USER} 时再次 hold → {@code 40900}
     * （状态机表里 {@code (WAITING_USER, HOLD)} 无登记，天然拒绝）。
     *
     * @return 流转后的工单快照
     */
    TicketDetailVO hold(Long ticketId);

    /**
     * 恢复工单（矩阵 #9）：{@code WAITING_USER → IN_PROGRESS}，<b>SLA 恢复并顺延</b>。
     *
     * <p>权限：{@code ticket:process}；仅当前 assignee 或 ADMIN。无请求体。
     *
     * <p>副作用（§9.4，逐字）：
     * <pre>
     * delta = now - sla_paused_at
     * resolution_deadline += delta          ← 只顺延解决时限
     * sla_paused_minutes  += delta（分钟）
     * sla_paused_at = null
     * </pre>
     * ⚠️ {@code response_deadline} <b>不顺延</b>。
     *
     * @return 流转后的工单快照
     */
    TicketDetailVO resume(Long ticketId);

    /**
     * 标记解决（矩阵 #10）：{@code IN_PROGRESS → WAITING_CONFIRM}。
     *
     * <p>权限：{@code ticket:resolve}；仅当前 assignee 或 ADMIN。无请求体。
     *
     * <p>副作用：写 {@code resolved_at}；<b>SLA 进入暂停</b>（{@code sla_paused_at = now}，§9.4
     * 规定 {@code WAITING_CONFIRM} 是暂停态 —— 等员工确认的时间不该由 IT 背锅）。
     *
     * @return 流转后的工单快照
     */
    TicketDetailVO resolve(Long ticketId);

    /**
     * 关闭工单（矩阵 #11）：{@code WAITING_CONFIRM → CLOSED}。
     *
     * <p>权限：{@code ticket:close}；<b>创建人或 ADMIN</b>（注意 creator 可能是任意角色，
     * 所以靠身份判而不是角色判）。无请求体。
     *
     * <p>副作用：写 {@code closed_at}；SLA <b>恢复</b>（退出 {@code WAITING_CONFIRM} 的暂停，
     * 顺延逻辑与 {@link #resume} 完全一致，§9.4）。
     *
     * @return 流转后的工单快照
     */
    TicketDetailVO close(Long ticketId);

    /**
     * 驳回（矩阵 #12）：{@code WAITING_CONFIRM → REOPENED}。
     *
     * <p>权限：{@code ticket:reopen}；<b>仅创建人</b>。无请求体。
     *
     * <p>副作用（§9.6，逐条）：
     * <pre>
     * resolution_deadline  = reopen 时间 + resolution_minutes   ← 新一轮（整轮重算，非累加）
     * sla_resolution_state = NORMAL
     * sla_warning_notified = 0
     * sla_breach_notified  = 0
     * reopen_count        += 1
     * first_response_at    保留首轮值，不重置
     * </pre>
     * 另外必须退出暂停态（{@code sla_paused_at = null} 并累加暂停分钟）——
     * §9.4 的状态表里 {@code REOPENED} <b>不是</b>暂停态。
     *
     * <p>⚠️ §7.3：<b>{@code REOPENED} 是持久状态</b>。本方法只走到 {@code REOPENED} 为止，
     * 绝不会顺手推进到 {@code IN_PROGRESS} —— 那要由处理人再调一次 {@link #start}（矩阵 #13）。
     *
     * @return 流转后的工单快照（状态为 {@code REOPENED}）
     */
    TicketDetailVO reject(Long ticketId);
}
