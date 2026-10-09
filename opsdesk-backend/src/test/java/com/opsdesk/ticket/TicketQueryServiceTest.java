package com.opsdesk.ticket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.vo.TicketDetailVO;
import com.opsdesk.ticket.vo.TicketListVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单列表 / 我的工单验收测试（工单 D3-02，SOP §5 红区：数据范围）
 *
 * <p>规格依据：API 文档 §8.2 / §8.3 / §16.7、规格基线 §8.3（可见范围权威定义）。
 *
 * <h2>种子数据（已核对）</h2>
 * <pre>
 * 工单 1: OPEN           INCIDENT        NETWORK    P2  creator=4  assignee=null  NORMAL
 * 工单 2: IN_PROGRESS    SERVICE_REQUEST ACCOUNT    P1  creator=5  assignee=2     WARNING
 * 工单 3: WAITING_CONFIRM INCIDENT       EMAIL      P3  creator=4  assignee=3     NORMAL
 * 工单 4: CLOSED         INCIDENT        PERIPHERAL P3  creator=5  assignee=2     NORMAL
 * 工单 5: WAITING_USER   INCIDENT        SOFTWARE   P2  creator=4  assignee=3     NORMAL
 * ⚠️ 五张工单的 created_at **完全相同**（2026-10-07 14:47:50）—— 正好是「翻页必须有
 *    tiebreaker」的真实用例：只按 created_at 排序时 MySQL 不保证稳定顺序。
 * </pre>
 * 按 §8.3 推算的可见集合（与 D2-04 的 Helper 测试一致）：
 * <pre>
 * admin → {1,2,3,4,5}   agent_zhang → {1,2,4}   agent_li → {1,3,5}
 * emp_wang → {1,3,5}    emp_zhao → {2,4}
 * </pre>
 *
 * <p>整类 {@link Transactional}：只读，跑完自动回滚。
 */
@SpringBootTest
@Transactional
class TicketQueryServiceTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;
    private static final long EMP_ZHAO = 5L;

    @Autowired
    private TicketQueryService ticketQueryService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 验收 1：可见集合 ====================

    @Test
    @DisplayName("验收1：5 个种子账号的列表可见集合与 §8.3 一致")
    void 五个账号可见集合与规格一致() {
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN))).as("ADMIN = ALL")
                .containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(idsOf(pageAs(AGENT_ZHANG, Role.AGENT)))
                .as("agent_zhang：①assignee=2→{2,4}；②公共池→{1}；③技术部子树无命中")
                .containsExactly(4L, 2L, 1L);
        assertThat(idsOf(pageAs(AGENT_LI, Role.AGENT)))
                .as("agent_li：①assignee=3→{3,5}；②公共池→{1}")
                .containsExactly(5L, 3L, 1L);
        assertThat(idsOf(pageAs(EMP_WANG, Role.EMPLOYEE)))
                .as("emp_wang = SELF(creator=4)").containsExactly(5L, 3L, 1L);
        assertThat(idsOf(pageAs(EMP_ZHAO, Role.EMPLOYEE)))
                .as("emp_zhao = SELF(creator=5)").containsExactly(4L, 2L);
    }

    @Test
    @DisplayName("验收1重点：AGENT 看得到跨部门的公共池工单（§8.1 那个错误写法必漏）")
    void agent看得到跨部门公共池() {
        // 工单 1 是财务部(5) 的 emp_wang 提的、无人接单；agent_zhang 在技术部(2)
        List<TicketListVO> list = pageAs(AGENT_ZHANG, Role.AGENT).getList();
        assertThat(list).extracting(TicketListVO::getId).contains(1L);
        assertThat(list).filteredOn(vo -> vo.getId() == 1L).singleElement()
                .satisfies(vo -> {
                    assertThat(vo.getStatus()).isEqualTo(TicketStatus.OPEN);
                    assertThat(vo.getAssigneeName()).as("公共池 → 未分派").isNull();
                });
    }

    // ==================== §8.2 我的工单：强制 SELF ====================

    @Test
    @DisplayName("§8.2：/mine 强制 SELF —— ADMIN 调它也只能看到自己创建的（不是全部）")
    void mine强制只看自己() {
        // admin 在种子里没创建过工单
        assertThat(idsOf(mineAs(ADMIN, Role.ADMIN)))
                .as("ADMIN 若走了角色范围就会看到 5 张 —— 那与「我的工单」语义矛盾").isEmpty();
        // agent_zhang 也没创建过
        assertThat(idsOf(mineAs(AGENT_ZHANG, Role.AGENT))).isEmpty();
        // emp_wang 创建了 1/3/5
        assertThat(idsOf(mineAs(EMP_WANG, Role.EMPLOYEE))).containsExactly(5L, 3L, 1L);
        assertThat(idsOf(mineAs(EMP_ZHAO, Role.EMPLOYEE))).containsExactly(4L, 2L);
    }

    @Test
    @DisplayName("§8.2：/mine 与列表对同一用户可能不同（AGENT 列表能看到公共池，/mine 看不到）")
    void mine与列表口径不同() {
        List<Long> listIds = idsOf(pageAs(AGENT_ZHANG, Role.AGENT));
        List<Long> mineIds = idsOf(mineAs(AGENT_ZHANG, Role.AGENT));

        assertThat(listIds).as("列表按角色范围：能看到公共池的工单 1").contains(1L);
        assertThat(mineIds).as("/mine 只看自己创建的：一张都没有").isEmpty();
    }

    // ==================== 验收 2：筛选逐个生效 ====================

    @Test
    @DisplayName("验收2：status / priority / category / type 四个枚举筛选逐个生效")
    void 枚举筛选生效() {
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setStatus(TicketStatus.OPEN))))
                .containsExactly(1L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setStatus(TicketStatus.CLOSED))))
                .containsExactly(4L);

        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setPriority(TicketPriority.P1))))
                .containsExactly(2L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setPriority(TicketPriority.P2))))
                .containsExactly(5L, 1L);

        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setCategory(TicketCategory.NETWORK))))
                .containsExactly(1L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setCategory(TicketCategory.ACCOUNT))))
                .containsExactly(2L);

        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setType(TicketType.SERVICE_REQUEST))))
                .containsExactly(2L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setType(TicketType.INCIDENT))))
                .containsExactly(5L, 4L, 3L, 1L);
    }

    @Test
    @DisplayName("验收2：keyword 同时匹配 ticket_no 与 title")
    void 关键字筛选生效() {
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setKeyword("VPN"))))
                .as("匹配 title").containsExactly(1L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setKeyword("OD2026100600002"))))
                .as("匹配 ticket_no").containsExactly(2L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setKeyword("邮件"))))
                .as("title 里 3 是「Outlook 无法收发邮件」").containsExactly(3L);
    }

    @Test
    @DisplayName("验收2：keyword 里的 LIKE 通配符被转义（?keyword=% 不能变成「匹配所有」）")
    void 关键字通配符被转义() {
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setKeyword("%"))))
                .as("% 被当成字面量 → 没有工单标题含 %").isEmpty();
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setKeyword("_")))).isEmpty();
    }

    @Test
    @DisplayName("验收2：assigneeId 筛选生效（叠加在数据范围之上）")
    void 处理人筛选生效() {
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setAssigneeId(2L))))
                .containsExactly(4L, 2L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setAssigneeId(3L))))
                .containsExactly(5L, 3L);
        // 叠加在数据范围之上：emp_wang 看不到 assignee=2 的工单（不在他的可见集合里）
        assertThat(idsOf(pageAs(EMP_WANG, Role.EMPLOYEE, q -> q.setAssigneeId(2L))))
                .as("数据范围先过滤，筛选只能在可见集合里再缩小").isEmpty();
    }

    @Test
    @DisplayName("验收2：创建时间区间筛选生效")
    void 时间区间筛选生效() {
        LocalDateTime sameDay = LocalDateTime.of(2026, 10, 7, 0, 0, 0);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setStartTime(sameDay))))
                .as("全部工单都在 2026-10-07").hasSize(5);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setEndTime(
                LocalDateTime.of(2026, 10, 6, 23, 59, 59)))))
                .as("截止到前一天 → 0 张").isEmpty();
    }

    // ==================== 验收 2：分页 ====================

    @Test
    @DisplayName("验收2：分页 total 正确，翻页不重不漏（created_at 全同秒 → 靠 id tiebreaker）")
    void 分页正确且翻页不重不漏() {
        PageResult<TicketListVO> p1 = pageAs(ADMIN, Role.ADMIN, q -> { q.setPage(1L); q.setSize(2L); });
        PageResult<TicketListVO> p2 = pageAs(ADMIN, Role.ADMIN, q -> { q.setPage(2L); q.setSize(2L); });
        PageResult<TicketListVO> p3 = pageAs(ADMIN, Role.ADMIN, q -> { q.setPage(3L); q.setSize(2L); });

        assertThat(p1.getTotal()).as("总数 5").isEqualTo(5L);
        assertThat(p1.getPage()).isEqualTo(1L);
        assertThat(p1.getSize()).isEqualTo(2L);
        assertThat(p1.getList()).hasSize(2);
        assertThat(p2.getList()).hasSize(2);
        assertThat(p3.getList()).as("最后一页 1 条").hasSize(1);

        // 三页拼起来必须恰好是全部 5 张、且不重复 —— 这是 tiebreaker 的验收点
        List<Long> all = new java.util.ArrayList<>(idsOf(p1));
        all.addAll(idsOf(p2));
        all.addAll(idsOf(p3));
        assertThat(all).as("翻页不重不漏").containsExactly(5L, 4L, 3L, 2L, 1L);
    }

    @Test
    @DisplayName("size 超过 50 被收敛到 50（§20.1，由 PageQuery 兜）")
    void size上限收敛() {
        PageResult<TicketListVO> page = pageAs(ADMIN, Role.ADMIN, q -> q.setSize(999L));
        assertThat(page.getSize()).isEqualTo(50L);
    }

    // ==================== 排序白名单 ====================

    @Test
    @DisplayName("sort 白名单：createdAt,desc（默认）/ priority,asc 生效")
    void 排序白名单生效() {
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN))).as("默认 createdAt,desc + id DESC")
                .containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setSort("createdAt,desc"))))
                .containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(idsOf(pageAs(ADMIN, Role.ADMIN, q -> q.setSort("priority,asc"))))
                .as("P1(2) → P2(5,1) → P3(4,3)，同优先级按 id DESC")
                .containsExactly(2L, 5L, 1L, 4L, 3L);
    }

    @Test
    @DisplayName("sort 非白名单值 → 40001（绝不把客户端字符串拼进 ORDER BY）")
    void 非法排序被拒() {
        assertThat(catchBiz(() -> pageAs(ADMIN, Role.ADMIN, q -> q.setSort("id;DROP TABLE ticket"))))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(catchBiz(() -> pageAs(ADMIN, Role.ADMIN, q -> q.setSort("title,asc"))))
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    // ==================== VO 契约 ====================

    @Test
    @DisplayName("§16.7：TicketListVO 字段逐个对上，且姓名/枚举/SLA 状态都装配了")
    void 列表VO字段正确() {
        TicketListVO ticket2 = pageAs(ADMIN, Role.ADMIN, q -> q.setStatus(TicketStatus.IN_PROGRESS))
                .getList().get(0);

        assertThat(ticket2.getId()).isEqualTo(2L);
        assertThat(ticket2.getTicketNo()).isEqualTo("OD2026100600002");
        assertThat(ticket2.getTitle()).isEqualTo("新员工邮箱账号开通申请");
        assertThat(ticket2.getType()).isEqualTo(TicketType.SERVICE_REQUEST);
        assertThat(ticket2.getCategory()).isEqualTo(TicketCategory.ACCOUNT);
        assertThat(ticket2.getPriority()).isEqualTo(TicketPriority.P1);
        assertThat(ticket2.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket2.getCreatorName()).as("creator_id=5 → 赵六").isEqualTo("赵六");
        assertThat(ticket2.getAssigneeName()).as("assignee_id=2 → 张三").isEqualTo("张三");
        assertThat(ticket2.getSlaResolutionState()).as("种子把它设成了 WARNING")
                .isEqualTo(SlaState.WARNING);
        assertThat(ticket2.getResolutionDeadline()).isNotNull();
        assertThat(ticket2.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("不出现 SELECT *：wrapper 显式 select，未选的列不会被查出来")
    void 列表查询只取需要的列() {
        // 机制验证：MP 的 .select() 会把列清单写进 SQL；没 select 的列查出来就是 null。
        // service 里正是靠这个（而不是 SELECT *）—— 顺带避免把 version / deleted 捞出来
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Ticket::getId, Ticket::getTicketNo);
        List<Ticket> rows = ticketService.list(wrapper);

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).getId()).isNotNull();
        assertThat(rows.get(0).getDescription())
                .as("没 select description → 值为 null，说明生成的不是 SELECT *").isNull();
        assertThat(rows.get(0).getVersion()).as("同理 version").isNull();
        assertThat(rows.get(0).getDeleted()).as("同理 deleted").isNull();
    }

    // ==================== D3-03 工单详情 ====================

    @Test
    @DisplayName("验收1：可见范围内正常返回，字段与 §16.8 对齐（继承 §16.7 的 12 个 + 新增 15 个）")
    void 详情正常返回() {
        TicketDetailVO vo = detailAs(EMP_WANG, Role.EMPLOYEE, 1L);

        // ---- 继承自 TicketListVO 的 12 个 ----
        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getTicketNo()).isEqualTo("OD2026100600001");
        assertThat(vo.getTitle()).isEqualTo("公司 VPN 无法连接");
        assertThat(vo.getType()).isEqualTo(TicketType.INCIDENT);
        assertThat(vo.getCategory()).isEqualTo(TicketCategory.NETWORK);
        assertThat(vo.getPriority()).isEqualTo(TicketPriority.P2);
        assertThat(vo.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(vo.getCreatorName()).isEqualTo("王五");
        assertThat(vo.getAssigneeName()).as("未分派").isNull();
        assertThat(vo.getSlaResolutionState()).isEqualTo(SlaState.NORMAL);
        assertThat(vo.getResolutionDeadline()).isNotNull();
        assertThat(vo.getCreatedAt()).isNotNull();

        // ---- 本类新增的 15 个 ----
        assertThat(vo.getDescription()).startsWith("今天上午开始 VPN");
        assertThat(vo.getSource()).isEqualTo(TicketSource.WEB);
        assertThat(vo.getCreatorId()).isEqualTo(EMP_WANG);
        assertThat(vo.getDepartmentId()).as("创建人部门快照").isEqualTo(5L);
        assertThat(vo.getAssigneeId()).isNull();
        assertThat(vo.getDepartmentName()).isEqualTo("财务部");
        assertThat(vo.getSlaPolicyId()).as("§9.1 策略版本").isEqualTo(2L);
        assertThat(vo.getResponseDeadline()).isNotNull();
        assertThat(vo.getFirstResponseAt()).as("OPEN 工单还没被受理").isNull();
        assertThat(vo.getSlaResponseState()).isEqualTo(SlaState.NORMAL);
        assertThat(vo.getSlaPausedMinutes()).isZero();
        assertThat(vo.getReopenCount()).isZero();
        assertThat(vo.getCancelReason()).isNull();
        assertThat(vo.getResolvedAt()).isNull();
        assertThat(vo.getClosedAt()).isNull();
    }

    @Test
    @DisplayName("验收1：已关闭工单的 SLA 生命周期字段有值（firstResponseAt / resolvedAt / closedAt）")
    void 详情带SLA生命周期字段() {
        // 工单 4：CLOSED，assignee=agent_zhang(2)
        TicketDetailVO vo = detailAs(AGENT_ZHANG, Role.AGENT, 4L);

        assertThat(vo.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(vo.getFirstResponseAt()).isNotNull();
        assertThat(vo.getResolvedAt()).isNotNull();
        assertThat(vo.getClosedAt()).isNotNull();
        assertThat(vo.getAssigneeId()).isEqualTo(AGENT_ZHANG);
        assertThat(vo.getAssigneeName()).isEqualTo("张三");
        assertThat(vo.getDepartmentName()).isEqualTo("人事部");
    }

    @Test
    @DisplayName("不返回 Entity 内部字段（用反射看 VO 到底声明了什么，比「响应里没有」更直接）")
    void 详情VO不暴露内部字段() {
        Set<String> own = Arrays.stream(TicketDetailVO.class.getDeclaredFields())
                .map(Field::getName).collect(Collectors.toSet());
        Set<String> inherited = Arrays.stream(TicketListVO.class.getDeclaredFields())
                .map(Field::getName).collect(Collectors.toSet());

        assertThat(own).as("§16.8 新增的恰好这 15 个").containsExactlyInAnyOrder(
                "description", "source", "creatorId", "departmentId", "assigneeId", "departmentName",
                "slaPolicyId", "responseDeadline", "firstResponseAt", "slaResponseState",
                "slaPausedMinutes", "reopenCount", "cancelReason", "resolvedAt", "closedAt");
        assertThat(inherited).as("§16.7 的 12 个").hasSize(12);

        assertThat(own).as("§16.8 里这三个字段本单刻意不做（归 D3-05 / AI 模块 / D4-01）")
                .doesNotContain("attachments", "aiAnalysis", "canOperate");
        assertThat(own).as("Entity 的内部字段一个都不该出现")
                .doesNotContain("version", "deleted", "slaPausedAt",
                        "slaWarningNotified", "slaBreachNotified", "rawResponse");
    }

    @Test
    @DisplayName("验收1（§25.3 #2）：EMP_WANG 查他人工单 → 40301")
    void 详情employee查他人工单40301() {
        // 工单 2 是 emp_zhao(5) 创建的
        assertThat(catchBiz(() -> detailAs(EMP_WANG, Role.EMPLOYEE, 2L)))
                .as("数据范围外 → 40301（不是 40400）").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);
        // 自己的工单能看（反证不是恒拒）
        assertThat(detailAs(EMP_WANG, Role.EMPLOYEE, 1L).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("验收2（§25.3 #5）：AGENT 查「非本人/非公共池/非本部门」→ 40301")
    void 详情agent查范围外工单40301() {
        // 工单 3：assignee=agent_li(3)、WAITING_CONFIRM、财务部(5)
        assertThat(catchBiz(() -> detailAs(AGENT_ZHANG, Role.AGENT, 3L)))
                .isEqualTo(ErrorCode.DATA_SCOPE_DENIED);
        // 受理人本人可见
        assertThat(detailAs(AGENT_LI, Role.AGENT, 3L).getId()).isEqualTo(3L);
        // 公共池的工单 1 可见（§8.3 的 ②）
        assertThat(detailAs(AGENT_ZHANG, Role.AGENT, 1L).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("验收3：不存在的 id → 40400")
    void 详情不存在返回40400() {
        assertThat(catchBiz(() -> detailAs(ADMIN, Role.ADMIN, 999_999L)))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("⚠️ 顺序关键：数据范围受限的用户查不存在的 id → 40400 而不是 40301（不泄露存在性）")
    void 详情不存在与越权不能混() {
        // 若实现里「先判可见性、再判存在性」，一个不存在的 id 也会得到 40301 ——
        // 攻击者就能从「40301 而不是 40400」推断出这个 id 是存在的（§2.7 明确禁止）
        assertThat(catchBiz(() -> detailAs(EMP_WANG, Role.EMPLOYEE, 999_999L)))
                .as("不存在的 id 一律 40400").isEqualTo(ErrorCode.NOT_FOUND);
        // 对照：存在但越权 → 40301
        assertThat(catchBiz(() -> detailAs(EMP_WANG, Role.EMPLOYEE, 2L)))
                .as("存在但越权 → 40301").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);
    }

    @Test
    @DisplayName("ADMIN 能看全部工单的详情（§8.2 ALL）")
    void 详情admin看全部() {
        for (long id = 1L; id <= 5L; id++) {
            assertThat(detailAs(ADMIN, Role.ADMIN, id).getId()).isEqualTo(id);
        }
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("未登录：/mine 抛 40100；列表退化成空集（fail-closed，不是放行全部）")
    void 未登录时两个接口的行为() {
        // ⚠️ 这个场景实际不可达 —— AuthInterceptor 在 order 0 就先返回 40100 了。
        //    但 service 层的兜底行为仍然要正确，所以单独钉一下。
        UserContext.clear();

        // /mine 要把 userId 当筛选值用，拿不到 → 抛 40100
        assertThat(catchBiz(() -> ticketQueryService.mine(new TicketQuery())))
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        // 列表走 TicketDataScopeHelper：它把「没有用户」解析成 NONE → 恒假条件 → 空集。
        // 这是**有意的 fail-closed**：绝不能因为「拿不到身份」就把全部工单放出去
        PageResult<TicketListVO> page = ticketQueryService.page(new TicketQuery());
        assertThat(page.getList()).as("看不到任何工单，而不是看到全部").isEmpty();
        assertThat(page.getTotal()).isZero();
    }

    // ==================== 工具 ====================

    private PageResult<TicketListVO> pageAs(long userId, Role role) {
        return pageAs(userId, role, q -> {
        });
    }

    private PageResult<TicketListVO> pageAs(long userId, Role role, java.util.function.Consumer<TicketQuery> customizer) {
        TicketQuery query = new TicketQuery();
        customizer.accept(query);
        setCurrentUser(userId, role);
        try {
            return ticketQueryService.page(query);
        }
        finally {
            UserContext.clear();
        }
    }

    private TicketDetailVO detailAs(long userId, Role role, Long ticketId) {
        setCurrentUser(userId, role);
        try {
            return ticketQueryService.detail(ticketId);
        }
        finally {
            UserContext.clear();
        }
    }

    private PageResult<TicketListVO> mineAs(long userId, Role role) {
        setCurrentUser(userId, role);
        try {
            return ticketQueryService.mine(new TicketQuery());
        }
        finally {
            UserContext.clear();
        }
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

    private List<Long> idsOf(PageResult<TicketListVO> page) {
        return page.getList().stream().map(TicketListVO::getId).toList();
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
