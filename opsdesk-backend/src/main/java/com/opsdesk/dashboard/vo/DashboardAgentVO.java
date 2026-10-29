package com.opsdesk.dashboard.vo;

import lombok.Data;

/**
 * AGENT 工作台出参（API 文档 §14.2）
 *
 * <pre>
 * GET /api/dashboard/agent  →  data: { "poolCount": 5, "myCount": 3, "warningCount": 2, "resolvedToday": 4 }
 * </pre>
 *
 * <h2>口径（补单文档 §3 定死；规格原文只列了名称）</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>口径</th></tr>
 *   <tr><td>{@code poolCount}</td>
 *       <td>{@code status = 'OPEN' AND assignee_id IS NULL} —— 公共待领取池，
 *           <b>所有 AGENT 看到的数字相同</b>（与「待受理池」页签一致）</td></tr>
 *   <tr><td>{@code myCount}</td>
 *       <td>{@code assignee_id = 我 AND status NOT IN ('CLOSED','CANCELLED')} —— 我手上还没结束的单</td></tr>
 *   <tr><td>{@code warningCount}</td>
 *       <td>{@code assignee_id = 我 AND sla_resolution_state = 'WARNING' AND status NOT IN ('CLOSED','CANCELLED')}</td></tr>
 *   <tr><td>{@code resolvedToday}</td>
 *       <td>{@code assignee_id = 我 AND resolved_at ∈ 今日}</td></tr>
 * </table>
 *
 * <p>⚠️ 这四条口径是「工作台语义」补的，<b>与工单列表的 §8.3 数据范围三段式不是一回事</b> ——
 * 不要为了「统一」把它们改成走 {@code TicketDataScopeHelper}，否则数字会和前端页签对不上
 * （补单文档第 1 点 / 禁止项第 3 条）。
 *
 * <p>字段同样是包装类型，理由见 {@link DashboardOverviewVO} 的类注释。
 */
@Data
public class DashboardAgentVO {

    /** 公共池待受理：{@code status='OPEN' AND assignee_id IS NULL}，与当前用户无关 */
    private Integer poolCount;

    /** 我的未结束工单：{@code assignee_id = 我 AND status NOT IN ('CLOSED','CANCELLED')} */
    private Integer myCount;

    /** 我名下的临近超时：{@code assignee_id = 我 AND sla_resolution_state='WARNING'} 且未结束 */
    private Integer warningCount;

    /** 我今日已解决：{@code assignee_id = 我 AND resolved_at ∈ 今日} */
    private Integer resolvedToday;
}
