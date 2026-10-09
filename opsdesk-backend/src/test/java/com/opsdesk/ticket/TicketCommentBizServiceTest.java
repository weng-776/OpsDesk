package com.opsdesk.ticket;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.ticket.dto.CommentCreateDTO;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.entity.TicketHistory;
import com.opsdesk.ticket.service.TicketCommentBizService;
import com.opsdesk.ticket.service.TicketHistoryService;
import com.opsdesk.ticket.vo.TicketCommentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单评论验收测试（工单 D3-04，SOP §5 红区：数据范围 / 内部可见性）
 *
 * <p>规格依据：API 文档 §8.7（列表）/ §8.8（新增）/ §16.10（VO）、
 * DDL {@code ticket_comment.internal}（0 否 / 1 是）。
 *
 * <h2>种子评论（已核对）</h2>
 * <pre>
 * 评论 1: ticket=2 user=2(agent_zhang) internal=0  "已收到，正在联系人事部核对入职名单。"
 * 评论 2: ticket=3 user=3(agent_li)    internal=0  "已重置 Outlook 配置，请重新登录"
 * 评论 3: ticket=4 user=2(agent_zhang) internal=0  "卡纸已清理，打印队列已重置。"
 * 评论 4: ticket=5 user=3(agent_li)    internal=0  "请补充报错页面的截图……"
 * 评论 5: ticket=5 user=3(agent_li)    internal=1  "（内部）疑似报销模块的字典缓存问题……"
 * </pre>
 * ⚠️ <b>评论 5 是本单的关键探针</b>：它在工单 5 上，且 {@code internal=1}。
 * 工单 5 的可见者是 admin(1) / agent_li(3) / emp_wang(4)（§8.3 推算，见 D2-04 测试），
 * 所以「同一条评论 → 不同角色看到不同的条数」这个最强断言就靠它：
 * <pre>
 *   emp_wang 查工单 5 → 只看到评论 4（1 条），看不到 internal=1 的评论 5
 *   agent_li 查工单 5 → 评论 4 + 5（2 条）
 *   admin    查工单 5 → 评论 4 + 5（2 条）
 * </pre>
 *
 * <p>整类 {@link Transactional}：新增评论的用例会写 ticket_comment / ticket_history，
 * 跑完自动回滚，不污染种子数据。
 */
@SpringBootTest
@Transactional
class TicketCommentBizServiceTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;
    private static final long EMP_ZHAO = 5L;

    @Autowired
    private TicketCommentBizService commentBizService;

    @Autowired
    private TicketHistoryService ticketHistoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 验收 1：internal 过滤（本单核心） ====================

    @Test
    @DisplayName("验收1：同一条 internal=1 评论 —— EMPLOYEE 看不到，AGENT 看得到（工单 5）")
    void 内部评论对employee不可见对agent可见() {
        // 先证明探针存在：工单 5 上确实有 internal=1 的评论
        Integer internalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_comment WHERE ticket_id = 5 AND internal = 1",
                Integer.class);
        assertThat(internalCount).as("工单 5 上必须有 internal=1 的探针评论，否则本用例是空验证")
                .isEqualTo(1);

        // EMPLOYEE（emp_wang 是工单 5 的创建人，能看到这张工单）→ 只看到 1 条公开评论
        PageResult<TicketCommentVO> asEmployee = commentsAs(EMP_WANG, Role.EMPLOYEE, 5L);
        assertThat(asEmployee.getTotal()).as("EMPLOYEE 只该看到 internal=0 的评论").isEqualTo(1);
        assertThat(asEmployee.getList()).extracting(TicketCommentVO::getInternal)
                .as("EMPLOYEE 拿到的每一条 internal 都必须是 false").containsOnly(false);

        // AGENT（agent_li 是工单 5 的受理人）→ 看到 2 条（含内部备注）
        PageResult<TicketCommentVO> asAgent = commentsAs(AGENT_LI, Role.AGENT, 5L);
        assertThat(asAgent.getTotal()).as("AGENT 该看到全部 2 条").isEqualTo(2);
        assertThat(asAgent.getList()).extracting(TicketCommentVO::getInternal)
                .as("AGENT 该能看到那条内部备注").containsExactlyInAnyOrder(false, true);

        // ADMIN 同样能看全
        assertThat(commentsAs(ADMIN, Role.ADMIN, 5L).getTotal()).as("ADMIN 该看到全部 2 条").isEqualTo(2);
    }

    @Test
    @DisplayName("⚠️ internal 过滤在 SQL 层 —— total 必须只数可见的（不是查全表再筛）")
    void 内部评论过滤在SQL层total正确() {
        // 若实现是「先全查再在内存里过滤」，total 会是 2（全表条数）而 list 只有 1 条。
        // 这条断言把两者钉在一起，防止那种写法蒙混过关
        PageResult<TicketCommentVO> asEmployee = commentsAs(EMP_WANG, Role.EMPLOYEE, 5L);
        assertThat(asEmployee.getTotal())
                .as("total 必须等于可见条数（若在内存里过滤，这里会是 2）")
                .isEqualTo(asEmployee.getList().size())
                .isEqualTo(1);

        // 对照：AGENT 的 total 是 2
        assertThat(commentsAs(AGENT_LI, Role.AGENT, 5L).getTotal()).isEqualTo(2);
    }

    @Test
    @DisplayName("验收1：公开评论（internal=0）对所有可见者都在 —— 不会把公开的也过滤掉")
    void 公开评论所有人都能看到() {
        // 工单 2：creator=emp_zhao(5)、assignee=agent_zhang(2)、评论 1（internal=0）
        assertThat(commentsAs(ADMIN, Role.ADMIN, 2L).getTotal()).as("ADMIN 看得到").isEqualTo(1);
        assertThat(commentsAs(AGENT_ZHANG, Role.AGENT, 2L).getTotal()).as("受理人看得到").isEqualTo(1);
        assertThat(commentsAs(EMP_ZHAO, Role.EMPLOYEE, 2L).getTotal()).as("创建人看得到").isEqualTo(1);

        TicketCommentVO vo = commentsAs(EMP_ZHAO, Role.EMPLOYEE, 2L).getList().get(0);
        assertThat(vo.getInternal()).isFalse();
    }

    // ==================== 验收 2：写历史 ====================

    @Test
    @DisplayName("验收2：新增评论后 ticket_history 多一条 COMMENT（from/to = 当前状态）")
    void 新增评论写历史() {
        // 工单 1：OPEN，创建人 emp_wang(4)
        long before = countHistory(1L, TicketHistoryAction.COMMENT);

        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        TicketCommentVO vo = commentBizService.add(1L, dto("VPN 还是连不上，重启过两次了", null));

        assertThat(vo.getId()).as("评论已落库并回填 id").isNotNull();
        assertThat(vo.getUserId()).isEqualTo(EMP_WANG);
        assertThat(vo.getContent()).isEqualTo("VPN 还是连不上，重启过两次了");
        assertThat(vo.getInternal()).as("EMPLOYEE 不传 internal → false").isFalse();
        assertThat(vo.getUserName()).as("评论人姓名已装配").isEqualTo("王五");
        assertThat(vo.getCreatedAt()).as("created_at 由 DB 填，已查回来").isNotNull();

        assertThat(countHistory(1L, TicketHistoryAction.COMMENT))
                .as("历史多了一条 COMMENT").isEqualTo(before + 1);

        // 历史行的字段逐项核对
        List<TicketHistory> histories = ticketHistoryService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketHistory>()
                        .eq(TicketHistory::getTicketId, 1L)
                        .eq(TicketHistory::getAction, TicketHistoryAction.COMMENT)
                        .orderByDesc(TicketHistory::getId));
        TicketHistory latest = histories.get(0);
        assertThat(latest.getOperatorId()).isEqualTo(EMP_WANG);
        assertThat(latest.getFromStatus()).as("评论不流转状态：from = 当前状态")
                .isEqualTo(latest.getToStatus());
        assertThat(latest.getRemark()).as("备注带评论摘要").contains("重启过两次");
    }

    @Test
    @DisplayName("新增评论立刻能在列表里查到（先写后读一致）")
    void 新增后立即可查() {
        setCurrentUser(ADMIN, Role.ADMIN);
        commentBizService.add(1L, dto("管理员补充：已升级到网络组处理", true));

        PageResult<TicketCommentVO> page = commentsAs(ADMIN, Role.ADMIN, 1L);
        assertThat(page.getList()).extracting(TicketCommentVO::getContent)
                .as("刚写的评论应在列表里").contains("管理员补充：已升级到网络组处理");
    }

    // ==================== 验收 3：越权 → 40301 ====================

    @Test
    @DisplayName("验收3（§25.3 #2）：对无可见性的工单评论 → 40301；列表同理")
    void 越权评论40301() {
        // emp_wang(4) 看不到工单 2（emp_zhao 创建、技术部）
        assertThat(catchBiz(() -> {
            setCurrentUser(EMP_WANG, Role.EMPLOYEE);
            commentBizService.add(2L, dto("我不该能评论这张单", null));
        })).as("无可见性 → 40301").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        assertThat(catchBiz(() -> {
            setCurrentUser(EMP_WANG, Role.EMPLOYEE);
            commentBizService.list(2L, new TicketQuery());
        })).as("列表也要 40301").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        // 反证：能看的人可以评论
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        assertThat(commentBizService.add(2L, dto("我是受理人，可以评论", false)).getId()).isNotNull();
    }

    @Test
    @DisplayName("验收3：不存在的工单 → 40400（不是 40301 —— 不泄露存在性）")
    void 不存在工单40400() {
        for (Role role : new Role[]{Role.EMPLOYEE, Role.AGENT, Role.ADMIN}) {
            assertThat(catchBiz(() -> {
                setCurrentUser(EMP_WANG, role);
                commentBizService.list(999_999L, new TicketQuery());
            })).as("%s 查不存在工单 → 40400", role).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    // ==================== internal 的写入权限 ====================

    @Test
    @DisplayName("验收（§8.8）：AGENT/ADMIN 可设 internal=true；EMPLOYEE 传 true 静默降级为 false")
    void internal写入权限() {
        // AGENT 能设 true
        setCurrentUser(AGENT_LI, Role.AGENT);
        TicketCommentVO byAgent = commentBizService.add(5L, dto("（内部）已联系运维排查", true));
        assertThat(byAgent.getInternal()).as("AGENT 可设 internal=true").isTrue();

        // ADMIN 能设 true
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(commentBizService.add(5L, dto("（内部）管理员备注", true)).getInternal())
                .as("ADMIN 可设 internal=true").isTrue();

        // EMPLOYEE 传 true → 静默降级为 false（已与用户确认的行为）
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        TicketCommentVO byEmployee = commentBizService.add(5L, dto("我想发内部备注", true));
        assertThat(byEmployee.getInternal())
                .as("EMPLOYEE 传 internal=true 应被降级为 false，而不是报错")
                .isFalse();

        // 落库的也是 0（不是只在出参上假装 false）
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT internal FROM ticket_comment WHERE id = ?", Integer.class, byEmployee.getId());
        assertThat(stored).as("库里存的也必须是 0").isZero();

        // 且 EMPLOYEE 自己那几条降级评论，他能看到（是公开的）
        PageResult<TicketCommentVO> asEmployee = commentsAs(EMP_WANG, Role.EMPLOYEE, 5L);
        assertThat(asEmployee.getList()).extracting(TicketCommentVO::getContent)
                .as("自己发的公开评论看得到").contains("我想发内部备注");
    }

    @Test
    @DisplayName("降级后的评论对 EMPLOYEE 可见 —— 证明「降级」不是「偷偷藏起来」")
    void 降级评论不藏起来() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        Long id = commentBizService.add(5L, dto("员工发言", true)).getId();

        // 另一个能看到工单 5 的人（AGENT，受理人 agent_li）也能看到这条 —— 它是公开评论。
        // ⚠️ 这里不能用另一个 EMPLOYEE 来验：emp_wang 是工单 5 的创建人、agent_li 是受理人，
        //    admin/agent_li/emp_wang 三个是工单 5 的全部可见者（§8.3），
        //    emp_zhao 看不到工单 5（同理他也看不到工单 1 —— 这正是 §8.3 的意义）
        PageResult<TicketCommentVO> asAgent = commentsAs(AGENT_LI, Role.AGENT, 5L);
        assertThat(asAgent.getList()).extracting(TicketCommentVO::getId)
                .as("降级 = 公开，别人也能看到（不是隐藏）").contains(id);

        // 且这条的 internal 落库就是 0
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT internal FROM ticket_comment WHERE id = ?", Integer.class, id);
        assertThat(stored).isZero();
    }

    // ==================== VO 契约 ====================

    @Test
    @DisplayName("VO 与 §16.10 对齐：字段恰好 6 个、姓名已装配、不返回 Entity")
    void VO契约() {
        PageResult<TicketCommentVO> page = commentsAs(AGENT_LI, Role.AGENT, 5L);
        TicketCommentVO vo = page.getList().get(0);

        Set<String> fields = java.util.Arrays.stream(TicketCommentVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(fields).as("§16.10 恰好 6 个字段").containsExactlyInAnyOrder(
                "id", "userId", "userName", "content", "internal", "createdAt");
        assertThat(fields).as("Entity 内部字段不该出现（ticket_comment 无 version/deleted，但守住底线）")
                .doesNotContain("ticketId", "version", "deleted");

        assertThat(vo.getUserName()).as("评论人姓名已装配").isEqualTo("李四");
        assertThat(vo.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("列表排序：按 created_at 升序（先说的先出现）")
    void 列表按时间升序() {
        PageResult<TicketCommentVO> page = commentsAs(AGENT_LI, Role.AGENT, 5L);
        List<java.time.LocalDateTime> times = page.getList().stream()
                .map(TicketCommentVO::getCreatedAt).toList();
        assertThat(times).as("升序且长度 2").isSorted().hasSize(2);
    }

    @Test
    @DisplayName("分页：size 收敛到上限 50，total 正确")
    void 分页() {
        setCurrentUser(ADMIN, Role.ADMIN);
        TicketQuery q = new TicketQuery();
        q.setSize(999L);
        PageResult<TicketCommentVO> page = commentBizService.list(5L, q);
        assertThat(page.getSize()).as("size 被 PageQuery 收敛到 50").isEqualTo(50);
        assertThat(page.getTotal()).isEqualTo(2);
    }

    // ==================== 工具 ====================

    private CommentCreateDTO dto(String content, Boolean internal) {
        CommentCreateDTO dto = new CommentCreateDTO();
        dto.setContent(content);
        dto.setInternal(internal);
        return dto;
    }

    private PageResult<TicketCommentVO> commentsAs(long userId, Role role, Long ticketId) {
        setCurrentUser(userId, role);
        try {
            return commentBizService.list(ticketId, new TicketQuery());
        }
        finally {
            UserContext.clear();
        }
    }

    private long countHistory(Long ticketId, TicketHistoryAction action) {
        return ticketHistoryService.count(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketHistory>()
                        .eq(TicketHistory::getTicketId, ticketId)
                        .eq(TicketHistory::getAction, action));
    }

    private void setCurrentUser(long userId, Role role) {
        UserContext.set(new UserContext.CurrentUser(
                userId, "test-jti", Set.of(role), Set.of(), departmentIdOf(userId)));
    }

    private Long departmentIdOf(long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static ErrorCode catchBiz(Runnable action) {
        try {
            action.run();
        }
        catch (BizException ex) {
            return ex.getErrorCode();
        }
        throw new AssertionError("预期抛出 BizException，但调用正常返回了");
    }
}
