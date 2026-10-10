package com.opsdesk.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.mapper.TicketMapper;
import com.opsdesk.ticket.service.TicketFlowService;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.statemachine.TicketStateMachine;
import com.opsdesk.ticket.support.TicketHistoryRecorder;
import com.opsdesk.ticket.vo.TicketDetailVO;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.service.RoleService;
import com.opsdesk.user.service.UserRoleService;
import com.opsdesk.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单状态流转实现（工单 D4-01 / D4-02，SOP §5 红区：状态机）
 *
 * <p>规格依据：规格基线 §7.2 矩阵 #2/#3/#5/#6、§7.3、§7.4、§6.2、§6.3、§9.3；API 文档 §8.12。
 *
 * <h2>每个流转方法的四步（§8.12 抬头的顺序，不能乱）</h2>
 * <pre>
 * ① 取工单：不存在 → 40400
 * ② 数据范围：不可见 → 40301
 * ③ 状态机：非法流转 → 40900 / 角色不符 → 40300 / 非处理人 → 40301
 * ④ CAS 更新 + 写历史（同事务）
 * </pre>
 * ①②的顺序与 D3-03 详情一致 —— 先判「不存在」再判「不可见」，否则会泄露 id 是否存在。
 *
 * <h2>⚠️ 状态改动的唯一出口是 CAS</h2>
 * 绝不 {@code ticket.setStatus(...)} + {@code updateById}：那样没有 {@code status} 前置条件，
 * 并发下会「在已经变了的状态上再流转一次」。见 {@code TicketMapper#updateStatusCas}。
 *
 * <h2>响应为什么用 {@code detailForFlow} 而不是 {@code detail}</h2>
 * 流转会改变归属（assign 给别人后离开公共池），操作者可能因此不再可见；
 * 若再校验一次数据范围，会出现「库里改成功、接口返 40301」。详见 {@code TicketQueryService#detailForFlow}。
 */
@Slf4j
@Service
public class TicketFlowServiceImpl implements TicketFlowService {

    /** 账号启用（与 {@code AuthServiceImpl} / {@code UserManageServiceImpl} 口径一致） */
    private static final int USER_STATUS_ENABLED = 1;

    private final TicketService ticketService;
    private final TicketQueryService ticketQueryService;
    private final TicketMapper ticketMapper;
    private final TicketStateMachine stateMachine;
    private final TicketHistoryRecorder historyRecorder;
    private final TicketDataScopeHelper dataScopeHelper;
    private final UserService userService;
    private final UserRoleService userRoleService;
    private final RoleService roleService;

    public TicketFlowServiceImpl(TicketService ticketService,
                                 TicketQueryService ticketQueryService,
                                 TicketMapper ticketMapper,
                                 TicketStateMachine stateMachine,
                                 TicketHistoryRecorder historyRecorder,
                                 TicketDataScopeHelper dataScopeHelper,
                                 UserService userService,
                                 UserRoleService userRoleService,
                                 RoleService roleService) {
        this.ticketService = ticketService;
        this.ticketQueryService = ticketQueryService;
        this.ticketMapper = ticketMapper;
        this.stateMachine = stateMachine;
        this.historyRecorder = historyRecorder;
        this.dataScopeHelper = dataScopeHelper;
        this.userService = userService;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
    }

    // ==================== 矩阵 #2 assign ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO assign(Long ticketId, TicketAssignDTO dto) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        // 被分派人必须是「启用的 AGENT / ADMIN」—— 分派给员工语义不成立（已与用户确认要校验）
        User assignee = requireAssignable(dto.getAssigneeId());

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.ASSIGN, user);
        apply(ticket, transition, user, assignee.getId(), "分派给 " + displayNameOf(assignee));
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #3 accept ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO accept(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.ACCEPT, user);
        // assignee_id = 当前用户（§7.2 矩阵 #3 的附加约束）
        apply(ticket, transition, user, user.userId(), "受理工单");
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #5 start ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO start(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.START, user);
        // 不改 assignee：矩阵 #5 只流转状态（矩阵 #13 REOPENED→IN_PROGRESS 同样沿用原 assignee）
        apply(ticket, transition, user, null, "开始处理");
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #6 transfer ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO transfer(Long ticketId, TicketAssignDTO dto) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        // 新处理人同样必须是「启用的 AGENT / ADMIN」（与 assign 同一把尺子）
        User assignee = requireAssignable(dto.getAssigneeId());

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.TRANSFER, user);
        // 矩阵 #6：只换 assignee_id，状态仍是 ASSIGNED。
        // ⚠️ stampsFirstResponse = false → apply() 传 null → SQL 跳过该列 → 保留首轮响应时间
        apply(ticket, transition, user, assignee.getId(), "转派给 " + displayNameOf(assignee));
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 私有 ====================

    /**
     * CAS 更新状态 + 写历史（同事务）。
     *
     * @param newAssigneeId 新的处理人；{@code null} 表示不改该列
     */
    private void apply(Ticket ticket, TicketStateMachine.Transition transition,
                       UserContext.CurrentUser user, Long newAssigneeId, String remark) {
        LocalDateTime now = LocalDateTime.now();

        // 「写 first_response_at（若空）」：已经响应过就传 null → SQL 跳过该列 → 保留首轮值（§9.6）
        LocalDateTime firstResponseAt =
                transition.stampsFirstResponse() && ticket.getFirstResponseAt() == null ? now : null;

        int rows = ticketMapper.updateStatusCas(ticket.getId(), ticket.getStatus(), transition.to(),
                ticket.getVersion(), newAssigneeId, firstResponseAt);
        if (rows == 0) {
            // status 或 version 与读取时不一致 → 别人抢先改过（§25.4 用例 #2「两人同时 accept」）
            log.warn("[状态流转] CAS 失败：ticketId={} from={} expectedVersion={} action={}",
                    ticket.getId(), ticket.getStatus(), ticket.getVersion(), transition.action());
            throw BizException.conflict("工单状态已被其他操作变更，请刷新后重试");
        }

        historyRecorder.record(ticket.getId(), user.userId(), transition.action(),
                transition.from(), transition.to(), remark);

        log.info("[状态流转] 成功：ticketId={} operator={} {} {} → {}",
                ticket.getId(), user.userId(), transition.action(),
                transition.from(), transition.to());

        // TODO(D6): 审计（AuditOperation.TICKET_ASSIGN 等）与通知（通知处理人 / 创建人）——
        //   审计与通知都是 Day 6 的交付物，本单只留调用点（§8.12 的「写审计 / 发事件」两步）。
    }

    /**
     * 按 id 取工单并做数据范围校验。
     *
     * <p>⚠️ 顺序与 D3-03 详情 / D3-04 评论完全一致，<b>不能反</b>：
     * 先 40400 再 40301，否则「不存在的 id」也会返回 40301，泄露 id 是否存在（§2.7）。
     */
    private Ticket loadVisibleTicket(Long ticketId) {
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            throw BizException.notFound("工单不存在");
        }
        dataScopeHelper.assertVisible(ticket, UserContext.get());
        return ticket;
    }

    /**
     * 校验被分派人可受理工单（已与用户确认：必须校验）。
     *
     * <p>三道：存在（40400）→ 启用（40001）→ 有 AGENT/ADMIN 角色（40001）。
     * 用 40001 而不是 40300：这是<b>请求参数不合法</b>（传了个不能当处理人的 id），
     * 不是「调用者没有权限」——40300 会让前端误以为是自己的权限问题。
     */
    private User requireAssignable(Long assigneeId) {
        if (assigneeId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "处理人不能为空");
        }
        User user = userService.getById(assigneeId);
        if (user == null) {
            throw BizException.notFound("指定的处理人不存在");
        }
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指定的处理人已被禁用");
        }
        if (!hasAgentOrAdminRole(assigneeId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "只能分派给 IT 服务人员或管理员");
        }
        return user;
    }

    /** 用户是否持有 AGENT 或 ADMIN 角色（经 {@code user_role} → {@code role.code}） */
    private boolean hasAgentOrAdminRole(Long userId) {
        List<Long> roleIds = userRoleService
                .list(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return false;
        }
        return roleService.listByIds(roleIds).stream()
                .anyMatch(role -> role.getCode() == Role.AGENT || role.getCode() == Role.ADMIN);
    }

    /** 显示名口径与 D3-02 列表 / D3-04 评论一致：昵称优先，无昵称回落用户名 */
    private String displayNameOf(User user) {
        return user.getNickname() == null ? user.getUsername() : user.getNickname();
    }
}
