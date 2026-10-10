package com.opsdesk.ticket.support;

import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.ticket.entity.TicketHistory;
import com.opsdesk.ticket.service.TicketHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工单历史记录器（工单 D4-01）
 *
 * <p>规格依据：规格基线 §7.4「每次流转都要写 {@code ticket_history}」；
 * DDL {@code ticket_history}；§3.12 的动作枚举。
 *
 * <h2>为什么要单独封装</h2>
 * Day 4 有 8 个流转接口（assign/accept/start/transfer/hold/resume/resolve/close/reject/cancel/force-close），
 * 每个都手写一遍「new TicketHistory + set 六个字段 + save」，必然出现两种歪法：
 * <ul>
 *   <li>某个方法<b>漏写</b>（历史缺一条，事后查不到谁干的）</li>
 *   <li>某个方法把 {@code from} / {@code to} <b>填反</b>（静默错数据）</li>
 * </ul>
 * 收口到一处后，新增流转只传参数、不碰实体装配。
 *
 * <h2>⚠️ 只负责「写」</h2>
 * 本类<b>不做</b>权限 / 状态校验（那是 {@code TicketStateMachine} 的事），
 * 也<b>不发</b>通知 / 审计（Day 6）。它只把「发生了一次流转」如实落库。
 *
 * <p>⚠️ {@code created_at} 是 DB 默认值，MP 的 insert 不回填（known-traps #5）。
 * 历史表只写不读回，所以这里不需要 {@code getById} —— 与评论（要回显时间）不同。
 */
@Slf4j
@Component
public class TicketHistoryRecorder {

    private final TicketHistoryService ticketHistoryService;

    public TicketHistoryRecorder(TicketHistoryService ticketHistoryService) {
        this.ticketHistoryService = ticketHistoryService;
    }

    /**
     * 记一次工单流转。
     *
     * @param ticketId   工单 id
     * @param operatorId 操作人 id（当前登录用户）
     * @param action     动作（{@code TicketHistoryAction}）
     * @param from       变更前状态
     * @param to         变更后状态（动作不改变状态时，与 {@code from} 相同，如 COMMENT）
     * @param remark     备注，可为 null
     */
    public void record(Long ticketId, Long operatorId, TicketHistoryAction action,
                       TicketStatus from, TicketStatus to, String remark) {
        TicketHistory history = new TicketHistory();
        history.setTicketId(ticketId);
        history.setOperatorId(operatorId);
        history.setAction(action);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setRemark(remark);
        ticketHistoryService.save(history);

        log.info("[工单历史] ticketId={} operator={} action={} {} → {}",
                ticketId, operatorId, action, from, to);
    }
}
