package com.opsdesk.common.datascope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.ticket.entity.Ticket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工单数据范围 Helper（工单 D2-04，SOP §5 红区）
 *
 * <p>规格依据：规格基线 §8.2（各角色数据范围）、<b>§8.3（AGENT 可见范围，权威定义）</b>、
 * §8.4（实现原则）、§25.3 用例 #2 / #5。
 *
 * <h2>§8.3 的 AGENT 三段式（逐字实现，缺一不可）</h2>
 * <pre>
 * EMPLOYEE → creator_id = 当前用户
 * ADMIN    → 无条件（ALL）
 * AGENT    → 三者并集：
 *              ① assignee_id = 当前用户                        -- 分配给我的
 *            ∪ ② status = 'OPEN' AND assignee_id IS NULL       -- 公共待领取池
 *            ∪ ③ department_id IN (我的部门子树)               -- 本部门（含子部门）创建的
 * </pre>
 *
 * <h2>⚠️ §8.1 的反例：绝不能写成 {@code assignee_id = ? OR department_id = ?}</h2>
 * {@code ticket.department_id} 记的是<b>创建人</b>所属部门。财务部王五提单 → {@code department_id = 财务部}；
 * 技术部张三（AGENT）按「自己所属部门」过滤就<b>看不到这张工单</b> → 跨部门工单无人处理。
 * <p>所以 ② 的<b>公共池</b>那一段是受理范围的命根子：它让「未分派的单」对所有 AGENT 可见，
 * 而不是靠部门匹配。漏掉 ② 或把 ③ 写成「按 AGENT 自己的部门精确匹配」，都会造成受理盲区。
 *
 * <h2>③ 为什么是 {@code department_id IN (子树 ids)} 而不是 §8.3 字面的 {@code LIKE}</h2>
 * §8.3 的示例 SQL 写 {@code creator_dept_path LIKE CONCAT(#{myDeptPath}, '%')}，
 * 但 {@code ticket} 表<b>没有 {@code creator_dept_path} 这一列</b>（只有 {@code department_id}），
 * 那是 JOIN {@code department} 出来的别名。照抄它必须 JOIN，而 MyBatis-Plus 表达不了 JOIN，
 * 只能 {@code wrapper.apply("EXISTS(...)")} 手写 SQL 片段 —— 撞 SOP §5 红区「手写 SQL 拼接」。
 * <p>改用「先解析出我的部门子树 id（{@link DepartmentScopeHelper#subtreeIds}），再
 * {@code department_id IN (…)}」：语义与 §8.3 <b>等价</b>（{@code department_id} 就是创建人部门快照）、
 * 命中 {@code idx_ticket_dept(department_id, created_at)}、且零手写 SQL。已与用户确认。
 *
 * <h2>两个出口：纯判定 + 抛异常</h2>
 * <ul>
 *   <li>{@link #isVisible} —— <b>纯判定</b>，不抛异常，好测（测试里可以直接断言真假）</li>
 *   <li>{@link #assertVisible} —— 不可见时抛 {@code 40301}，给详情 / 操作类接口用。
 *       把「用哪个错误码、文案怎么写」收口在这里，免得每个接口各写一遍、写歪了也没人发现</li>
 * </ul>
 * 列表类接口用 {@link #applyScope}（只过滤、不拒绝）；单条接口用 {@link #assertVisible}。
 *
 * <p>⚠️ 用 {@code assertVisible} 的调用方要注意 §2.7：越权返回 {@code 40301} 而<b>不是</b>
 * {@code 40400}，但实现上要保证<b>不泄露资源是否存在</b> —— 正确顺序是
 * 「先按 id 查，查不到抛 40400；查到了再 assertVisible」，不要用 40301 去盖住「不存在」。
 */
@Slf4j
@Component
public class TicketDataScopeHelper {

    private final DepartmentScopeHelper departmentScopeHelper;

    public TicketDataScopeHelper(DepartmentScopeHelper departmentScopeHelper) {
        this.departmentScopeHelper = departmentScopeHelper;
    }

    // ==================== 对外：列表查询追加条件 ====================

    /**
     * 把当前用户的数据范围条件追加到 wrapper 上。
     *
     * <p>ADMIN <b>不加任何条件</b>（§8.2 ALL）；其余角色追加一个 {@code AND (…)} 分组。
     *
     * @param wrapper 待追加的工单查询条件（就地修改）
     * @param user    当前登录用户；{@code null} 视为不可见任何数据（fail-closed）
     */
    public void applyScope(LambdaQueryWrapper<Ticket> wrapper, UserContext.CurrentUser user) {
        Objects.requireNonNull(wrapper, "wrapper 不能为空");
        Scope scope = resolveScope(user);
        switch (scope.kind()) {
            case ALL -> {
                // ADMIN：§8.2 ALL —— 刻意不加任何条件
            }
            case NONE -> {
                // 恒假条件：id 是主键 NOT NULL，`id IS NULL` 永远不成立。
                // 用「恒假」而不是「不加条件」—— 后者会把全部数据放出去（fail-open）
                wrapper.isNull(Ticket::getId);
            }
            case SELF -> applySelfScope(wrapper, scope.userId());
            case AGENT -> applyAgentScope(wrapper, scope);
        }
    }

    /**
     * §8.3 的 AGENT 三段式。
     *
     * <p>⚠️ 整个三段必须包在一个 {@code AND ( … )} 里，否则会与调用方已有的条件
     * 发生优先级错位（{@code a AND b OR c} ≠ {@code a AND (b OR c)}）。
     */
    private void applyAgentScope(LambdaQueryWrapper<Ticket> wrapper, Scope scope) {
        List<Long> subtreeIds = scope.deptSubtreeIds();
        wrapper.and(w -> {
            // ① 分配给我的
            w.eq(Ticket::getAssigneeId, scope.userId())
             // ② 公共待领取池 —— §8.3 明确「绝不能漏」
             .or(x -> x.eq(Ticket::getStatus, TicketStatus.OPEN).isNull(Ticket::getAssigneeId));
            // ③ 本部门（含子部门）创建的。
            //    ⚠️ 子树为空时必须整个跳过：MyBatis-Plus 的 in(col, 空集合) 会生成 `IN ()`，
            //    那是 SQL 语法错，请求直接 500
            if (!subtreeIds.isEmpty()) {
                w.or(x -> x.in(Ticket::getDepartmentId, subtreeIds));
            }
        });
    }

    /**
     * 强制按 SELF 追加条件：{@code creator_id = userId}（§8.2 的 EMPLOYEE 范围）。
     *
     * <p>给「我的工单」{@code GET /api/tickets/mine} 用 —— §8.2 抬头明确写「数据范围：SELF」，
     * 也就是说它<b>不按角色叠加</b>：ADMIN 调 {@code /mine} 也只能看到自己创建的。
     * 如果 {@code /mine} 也走 {@link #applyScope}，ADMIN 会拿到全部工单，与「我的工单」语义矛盾。
     *
     * <p>与 {@link #applyScope} 的 SELF 分支共用同一行逻辑 —— SELF 的定义只此一处。
     */
    public void applySelfScope(LambdaQueryWrapper<Ticket> wrapper, Long userId) {
        Objects.requireNonNull(wrapper, "wrapper 不能为空");
        if (userId == null) {
            // 未登录：恒假条件，什么都看不到（fail-closed）
            wrapper.isNull(Ticket::getId);
            return;
        }
        wrapper.eq(Ticket::getCreatorId, userId);
    }

    // ==================== 对外：单条可见性 ====================

    /**
     * 单条工单对当前用户是否可见（详情 / 操作类接口用）。
     *
     * <p>与 {@link #applyScope} <b>共用同一个 {@link Scope}</b>，所以两者语义必然一致
     * —— 不是「两套逻辑各写一遍」，测试里也交叉验证了这一点。
     *
     * @return 不可见时由调用方抛 {@code 40301}（§25.3 #2 / #5）
     */
    public boolean isVisible(Ticket ticket, UserContext.CurrentUser user) {
        if (ticket == null) {
            return false;
        }
        Scope scope = resolveScope(user);
        return switch (scope.kind()) {
            case ALL -> true;
            case NONE -> false;
            case SELF -> Objects.equals(ticket.getCreatorId(), scope.userId());
            case AGENT -> isVisibleToAgent(ticket, scope);
        };
    }

    /**
     * 单条工单不可见时抛 {@code 40301}（API 文档 §8.4 / 规格基线 §25.3 用例 #2、#5）。
     *
     * <p>与 {@link #isVisible} 共用同一个 {@link Scope} 解析，只是把「不可见」翻译成异常。
     * 详情、以及所有「按 id 操作单条工单」的接口都应该用它，而不是各自写一遍判断。
     *
     * @throws BizException {@code 40301}（{@code DATA_SCOPE_DENIED}）
     */
    public void assertVisible(Ticket ticket, UserContext.CurrentUser user) {
        if (isVisible(ticket, user)) {
            return;
        }
        log.warn("[数据范围] 越权访问被拒：userId={} ticketId={}",
                user == null ? null : user.userId(), ticket == null ? null : ticket.getId());
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    private boolean isVisibleToAgent(Ticket ticket, Scope scope) {
        // ① 分配给我的
        if (Objects.equals(ticket.getAssigneeId(), scope.userId())) {
            return true;
        }
        // ② 公共待领取池
        if (ticket.getStatus() == TicketStatus.OPEN && ticket.getAssigneeId() == null) {
            return true;
        }
        // ③ 本部门（含子部门）创建的
        return ticket.getDepartmentId() != null
                && scope.deptSubtreeIds().contains(ticket.getDepartmentId());
    }

    // ==================== 私有：范围解析 ====================

    /**
     * 把「用户的角色」解析成一份具体的数据范围。
     *
     * <p><b>多角色取最宽</b>：§8.2 是按角色定义范围的，没说一个用户同时有多个角色怎么办。
     * 这里取并集 —— 角色是<b>叠加授权</b>，多一个角色不该让可见范围变小。
     * 判定顺序 ADMIN → AGENT → EMPLOYEE 即「由宽到窄」，先命中先返回。
     * <p>（种子里每人只有一个角色，但 {@code PUT /api/users/{id}/roles} 允许配多个。）
     */
    private Scope resolveScope(UserContext.CurrentUser user) {
        if (user == null || user.userId() == null) {
            return new Scope(ScopeKind.NONE, null, List.of());
        }
        Set<Role> roles = user.roles();
        if (roles.contains(Role.ADMIN)) {
            return new Scope(ScopeKind.ALL, user.userId(), List.of());
        }
        if (roles.contains(Role.AGENT)) {
            return new Scope(ScopeKind.AGENT, user.userId(), myDepartmentSubtreeIds(user));
        }
        if (roles.contains(Role.EMPLOYEE)) {
            return new Scope(ScopeKind.SELF, user.userId(), List.of());
        }
        // 一个角色都没有 —— 正常不可达（没有角色就没有 ticket:* 权限，D2-02 的权限拦截器先拦了）。
        // 真到了这里必须 fail-closed，绝不能因为「拿不到角色」就放行全部
        log.warn("[数据范围] 用户没有任何角色，按不可见处理。userId={}", user.userId());
        return new Scope(ScopeKind.NONE, user.userId(), List.of());
    }

    /**
     * 把当前用户所属部门展开成子树 id 列表。
     *
     * <p>{@code departmentId} 直接从 {@code UserContext.CurrentUser} 取 —— 它由鉴权拦截器
     * 从权限缓存 {@code auth:perms:{userId}} 里带出来（D2-04 优化），
     * 所以这里<b>一次库都不用查</b>。
     * <p>只缓存 {@code departmentId}、<b>不缓存子树本身</b>：子树会随部门移动（D1-04）变化，
     * 而权限缓存只在角色/状态变更时失效 —— 缓存子树必然读到脏数据。子树每次现算（1 条，命中 idx_dept_path）。
     *
     * @return 未设部门 → 空列表（调用方 {@link #applyAgentScope} 会据此跳过 ③）
     */
    private List<Long> myDepartmentSubtreeIds(UserContext.CurrentUser user) {
        Long departmentId = user.departmentId();
        return departmentId == null ? List.of() : departmentScopeHelper.subtreeIds(departmentId);
    }

    /** 解析后的数据范围种类（由宽到窄：ALL > AGENT > SELF > NONE） */
    private enum ScopeKind {
        /** ADMIN：企业全部（§8.2 ALL） */
        ALL,
        /** AGENT：§8.3 三段式并集 */
        AGENT,
        /** EMPLOYEE：仅自己创建的（§8.2 SELF） */
        SELF,
        /** 无角色 / 未登录：什么都看不到（fail-closed） */
        NONE
    }

    /**
     * 一份具体的数据范围。
     *
     * @param kind           范围种类
     * @param userId         当前用户 id
     * @param deptSubtreeIds 仅 AGENT 用：我的部门子树 id（含自身）；可能为空
     */
    private record Scope(ScopeKind kind, Long userId, List<Long> deptSubtreeIds) {
    }
}
