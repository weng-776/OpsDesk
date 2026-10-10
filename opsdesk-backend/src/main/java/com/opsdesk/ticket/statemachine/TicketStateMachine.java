package com.opsdesk.ticket.statemachine;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.ticket.entity.Ticket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工单状态机（工单 D4-01 起，SOP §5 红区：状态机是整个项目最核心的红区）
 *
 * <p>规格依据：规格基线 <b>§7.2 状态 × 角色 × 动作矩阵（权威）</b>、§7.3、§7.4、§6.2、§6.3；
 * API 文档 §8.12。
 *
 * <p>已登记：D4-01 的 #2 assign / #3 accept / #5 start；D4-02 的 #6 transfer；D4-03 的 #8 hold / #9 resume。
 *
 * <h2>为什么是「判定表」而不是 if-else</h2>
 * §7.2 是一张 15 行的表。写成 if-else 链会有两个后果：
 * <ol>
 *   <li><b>加一个状态就要翻遍所有分支</b>（D4-02~05 还要加 transfer/hold/resume/resolve/close/reject/cancel/force-close）</li>
 *   <li><b>漏登记某条边时不会报错</b>，只是「这个动作莫名其妙不能做」——静默出错</li>
 * </ol>
 * 表驱动后，新增流转 = 加一行；漏登记 = 查不到 = 明确抛 {@code 40900}。
 *
 * <h2>三层校验的顺序（不能反）</h2>
 * <pre>
 * ① 工单存在性 → 40400   （调用方 loadVisibleTicket 负责）
 * ② 数据范围   → 40301   （调用方 assertVisible 负责）
 * ③ 本状态机   → 40900 / 40300 / 40301
 *      ③.1 查 (from, action) 是否有登记 → 无 → 40900
 *      ③.2 角色是否在允许集合内         → 否 → 40300
 *      ③.3 附加约束（如「必须是当前处理人」）→ 否 → 40301
 * </pre>
 * ③ 必须在 ② 之后：先确认「看得见这张单」，才谈「能不能对它做这个动作」；
 * 反了会把「状态 + 动作」的信息泄露给无权者。
 *
 * <h2>终态为什么不用单独写分支（矩阵 #15）</h2>
 * {@code CLOSED} / {@code CANCELLED} 在表里<b>没有任何以它们为 from 的登记</b>，
 * 所以查表必然落空 → 天然抛 {@code 40900}。这就是「终态拒绝任何流转」的实现，
 * 不需要额外 if。新增状态时只要不给它登记入边，它就自动是终态。
 *
 * <h2>⚠️ 状态流转逻辑只在这里</h2>
 * {@link TicketStatus} 枚举只承载状态数据（code / label / 是否终态），
 * <b>不含任何流转规则</b>；业务层<b>禁止直接 set status</b>（§7.4）。
 */
@Slf4j
@Component
public class TicketStateMachine {

    /** §7.2「允许角色」列：AGENT / ADMIN */
    private static final Set<Role> AGENT_OR_ADMIN = Set.of(Role.AGENT, Role.ADMIN);

    /**
     * 附加约束（§7.2 的「附加约束」列里那些「只有某个人能做」的规则）。
     *
     * <p>与 {@link #roles} 的分工：角色是<b>粗筛</b>（你是 IT 人员吗），
     * 前置条件是<b>细筛</b>（你是这张单的处理人吗）。两者都过才放行。
     */
    public enum Precondition {
        /** 无附加约束（如 assign / accept） */
        NONE,
        /** 必须是当前处理人，或 ADMIN（矩阵 #5 start、#8 hold、#9 resume） */
        ASSIGNEE_OR_ADMIN,
        /** 必须是创建人，或 ADMIN（矩阵 #11 close、#4/#7 cancel） */
        CREATOR_OR_ADMIN
    }

    /**
     * 一条流转规则（§7.2 的一行）。
     *
     * @param from              来源状态
     * @param action            动作
     * @param to                目标状态
     * @param roles             允许角色（空集表示「不限角色，只看前置条件」）
     * @param precondition      附加约束
     * @param stampsFirstResponse 是否要写 {@code first_response_at}（若空）—— §9.3
     */
    public record Transition(
            TicketStatus from,
            TicketHistoryAction action,
            TicketStatus to,
            Set<Role> roles,
            Precondition precondition,
            boolean stampsFirstResponse) {
    }

    /**
     * §7.2 矩阵登记表 —— 已登记 D4-01 的 #2 / #3 / #5、D4-02 的 #6、D4-03 的 #8 / #9。
     *
     * <p>D4-04~05 会继续追加（resolve / close / reject / cancel / force-close），
     * 以及矩阵 #13 的 {@code REOPENED --start--> IN_PROGRESS} ——
     * 因为索引 key 是 {@code (from, action)}，同一个 {@code start} 天然支持两个来源状态。
     */
    private static final List<Transition> TRANSITIONS = List.of(
            // #2 OPEN --assign--> ASSIGNED：需 ticket:assign，可指定 assignee_id
            new Transition(TicketStatus.OPEN, TicketHistoryAction.ASSIGN, TicketStatus.ASSIGNED,
                    AGENT_OR_ADMIN, Precondition.NONE, true),
            // #3 OPEN --accept--> ASSIGNED：assignee_id = 当前用户
            new Transition(TicketStatus.OPEN, TicketHistoryAction.ACCEPT, TicketStatus.ASSIGNED,
                    AGENT_OR_ADMIN, Precondition.NONE, true),
            // #5 ASSIGNED --start--> IN_PROGRESS：仅 assignee / ADMIN
            new Transition(TicketStatus.ASSIGNED, TicketHistoryAction.START, TicketStatus.IN_PROGRESS,
                    AGENT_OR_ADMIN, Precondition.ASSIGNEE_OR_ADMIN, true),
            // #6 ASSIGNED --transfer--> ASSIGNED：只换处理人，状态不变
            //    ⚠️ stampsFirstResponse = false —— 已经响应过了，转派不得重置 first_response_at
            //    ⚠️ 前置条件 NONE：矩阵 #6 只约束角色（AGENT/ADMIN），
            //       没要求「必须是当前处理人」（与 #5 start 的区别就在这里）
            new Transition(TicketStatus.ASSIGNED, TicketHistoryAction.TRANSFER, TicketStatus.ASSIGNED,
                    AGENT_OR_ADMIN, Precondition.NONE, false),
            // #8 IN_PROGRESS --hold--> WAITING_USER：仅 assignee / ADMIN，SLA 暂停（§9.4）
            //    ⚠️ 「已在 WAITING_USER 再 hold 应 40900」不用写代码 ——
            //       (WAITING_USER, HOLD) 无登记，查表必然落空
            new Transition(TicketStatus.IN_PROGRESS, TicketHistoryAction.HOLD, TicketStatus.WAITING_USER,
                    AGENT_OR_ADMIN, Precondition.ASSIGNEE_OR_ADMIN, false),
            // #9 WAITING_USER --resume--> IN_PROGRESS：仅 assignee / ADMIN，SLA 恢复并顺延（§9.4）
            new Transition(TicketStatus.WAITING_USER, TicketHistoryAction.RESUME, TicketStatus.IN_PROGRESS,
                    AGENT_OR_ADMIN, Precondition.ASSIGNEE_OR_ADMIN, false)
    );

    /**
     * 索引：{@code "OPEN:ASSIGN" → Transition}。
     *
     * <p>用不可变 Map 且<b>只按 key 查</b>（不迭代），所以不关心顺序 ——
     * 这点与 {@code FileTypeValidator} 那个「需要稳定顺序」的场景不同。
     */
    private static final Map<String, Transition> INDEX = buildIndex();

    private static Map<String, Transition> buildIndex() {
        Map<String, Transition> map = new HashMap<>();
        for (Transition t : TRANSITIONS) {
            String key = key(t.from(), t.action());
            if (map.put(key, t) != null) {
                // 登记重复 = 开发期配置错误。必须启动即炸，不能等到线上某条流转行为不确定
                throw new IllegalStateException("状态机登记重复：" + key);
            }
        }
        return Map.copyOf(map);
    }

    private static String key(TicketStatus from, TicketHistoryAction action) {
        return from.name() + ':' + action.name();
    }

    // ==================== 对外 ====================

    /**
     * 校验一次流转是否允许，并返回命中的规则。
     *
     * <p><b>调用方必须已经做过存在性（40400）与数据范围（40301）校验</b> —— 见类注释的顺序说明。
     *
     * @return 命中的 {@link Transition}，调用方据此取 {@code to()} 与 {@code stampsFirstResponse()}
     * @throws BizException {@code 40900} 非法流转（含终态）；
     *                      {@code 40300} 角色不符；
     *                      {@code 40301} 前置条件不符（如非当前处理人）
     */
    public Transition check(Ticket ticket, TicketHistoryAction action, UserContext.CurrentUser user) {
        Objects.requireNonNull(ticket, "ticket 不能为空");
        TicketStatus from = ticket.getStatus();
        Transition transition = INDEX.get(key(from, action));
        if (transition == null) {
            // 终态也走这里：CLOSED / CANCELLED 没有任何入边登记（矩阵 #15）
            log.warn("[状态机] 非法流转被拒：ticketId={} from={} action={}",
                    ticket.getId(), from, action);
            throw BizException.conflict(
                    "当前状态（" + from.getLabel() + "）不允许执行该操作");
        }
        assertRole(transition, user);
        assertPrecondition(transition.precondition(), ticket, user);
        return transition;
    }

    /** 某状态 + 某动作 是否已登记（给测试与将来的 canOperate 用） */
    public boolean isRegistered(TicketStatus from, TicketHistoryAction action) {
        return INDEX.containsKey(key(from, action));
    }

    // ==================== 私有：两层校验 ====================

    /** ③.2 角色粗筛 */
    private void assertRole(Transition transition, UserContext.CurrentUser user) {
        if (user == null) {
            // 正常轮不到这里（AuthInterceptor 先返 40100），保守 fail-closed
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (transition.roles().isEmpty()) {
            return;
        }
        for (Role allowed : transition.roles()) {
            if (user.roles().contains(allowed)) {
                return;
            }
        }
        log.warn("[状态机] 角色不符被拒：action={} roles={} userRoles={}",
                transition.action(), transition.roles(), user.roles());
        throw BizException.forbidden("当前角色无权执行该操作");
    }

    /** ③.3 前置条件细筛 */
    private void assertPrecondition(Precondition precondition, Ticket ticket,
                                    UserContext.CurrentUser user) {
        if (precondition == Precondition.NONE) {
            return;
        }
        // ADMIN 一律放行（§7.2 每条约束都写了「或 ADMIN」）
        if (user.roles().contains(Role.ADMIN)) {
            return;
        }
        Long expected = switch (precondition) {
            case ASSIGNEE_OR_ADMIN -> ticket.getAssigneeId();
            case CREATOR_OR_ADMIN -> ticket.getCreatorId();
            case NONE -> null;
        };
        if (!Objects.equals(expected, user.userId())) {
            log.warn("[状态机] 前置条件不符被拒：ticketId={} precondition={} expected={} actual={}",
                    ticket.getId(), precondition, expected, user.userId());
            throw BizException.dataScopeDenied(precondition == Precondition.ASSIGNEE_OR_ADMIN
                    ? "仅当前处理人或管理员可执行该操作"
                    : "仅创建人或管理员可执行该操作");
        }
    }
}
