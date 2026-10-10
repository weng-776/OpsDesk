package com.opsdesk.sla;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.sla.dto.SlaPolicyUpdateDTO;
import com.opsdesk.sla.entity.SlaPolicy;
import com.opsdesk.sla.service.SlaPolicyManageService;
import com.opsdesk.sla.service.SlaPolicyService;
import com.opsdesk.sla.vo.SlaPolicyVO;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.vo.TicketCreatedVO;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SLA 策略版本化验收测试（工单 D5-01，SOP §5 红区：SLA）
 *
 * <p>规格依据：规格基线 §9.1、<b>§9.7（版本化而非原地修改）</b>；API 文档 §9.1 / §9.2 / §9.3。
 *
 * <h2>核心断言：修改 ≠ UPDATE</h2>
 * 改一次时限 = 旧版本 {@code INACTIVE} + 新增一条 {@code ACTIVE}。
 * 存量工单的 {@code ticket.sla_policy_id} 仍指向旧版本 id → 口径不变、可追溯。
 *
 * <p>整类 {@link Transactional}：跑完回滚，不污染种子数据。
 */
@SpringBootTest
@Transactional
class SlaPolicyManageServiceTest {

    private static final long EMP_WANG = 4L;

    @Autowired
    private SlaPolicyManageService slaPolicyManageService;

    @Autowired
    private SlaPolicyService slaPolicyService;

    @Autowired
    private TicketCreateService ticketCreateService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 只用于在「裸 JDBC 改库」之后清 MyBatis 一级缓存 */
    @Autowired
    private org.mybatis.spring.SqlSessionTemplate sqlSessionTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 验收 1 ====================

    @Test
    @DisplayName("验收1：改 P1 时限后旧记录 INACTIVE、新记录 ACTIVE，且存量工单的 sla_policy_id 未变")
    void 版本化修改且存量工单不受影响() {
        Long oldId = activePolicyId(TicketPriority.P1);
        Integer oldResponse = minutesOf(oldId, "response_minutes");

        // 记录**全部**存量工单的 sla_policy_id 快照（不假设「ACTIVE 版本一定被某张工单引用」——
        // 版本化之后新 ACTIVE 可能还没有任何工单，这是正常状态）
        List<java.util.Map<String, Object>> ticketsBefore = jdbcTemplate.queryForList(
                "SELECT id, sla_policy_id FROM ticket ORDER BY id");
        assertThat(ticketsBefore).as("种子里有工单").isNotEmpty();

        SlaPolicyVO fresh = slaPolicyManageService.update(oldId, dto(oldResponse + 10, 240));

        // 旧版本 → INACTIVE（但 id / 时限字段一个都没动）
        assertThat(slaPolicyService.getById(oldId).getStatus()).isEqualTo("INACTIVE");
        assertThat(slaPolicyService.getById(oldId).getResponseMinutes())
                .as("旧版本的时限字段保持原值（存量工单的口径靠它）").isEqualTo(oldResponse);

        // 新版本 → ACTIVE，且是新 id
        assertThat(fresh.getId()).isNotEqualTo(oldId);
        assertThat(fresh.getStatus()).isEqualTo("ACTIVE");
        assertThat(fresh.getPriority()).isEqualTo(TicketPriority.P1);
        assertThat(fresh.getResponseMinutes()).isEqualTo(oldResponse + 10);
        assertThat(fresh.getEffectiveFrom()).as("§9.7：effective_from = now").isNotNull();

        // ⭐ 存量工单不受影响：一张都没变
        List<java.util.Map<String, Object>> ticketsAfter = jdbcTemplate.queryForList(
                "SELECT id, sla_policy_id FROM ticket ORDER BY id");
        assertThat(ticketsAfter)
                .as("§9.7：改 SLA 策略不得改动任何存量工单的 sla_policy_id")
                .isEqualTo(ticketsBefore);
    }

    // ==================== 验收 2 ====================

    @Test
    @DisplayName("验收2：改完 P2 后新建工单指向新版本")
    void 新工单指向新版本() {
        Long oldId = activePolicyId(TicketPriority.P2);
        SlaPolicyVO fresh = slaPolicyManageService.update(oldId, dto(15, 120));

        setCurrentUser();
        TicketCreatedVO created = ticketCreateService.create(newTicket(TicketPriority.P2), null);

        assertThat(ticketService.getById(created.getId()).getSlaPolicyId())
                .as("新工单应指向新版本（TicketSlaCalculator 查 status='ACTIVE'）")
                .isEqualTo(fresh.getId());
    }

    // ==================== 验收 3 ====================

    @Test
    @DisplayName("验收3补充：正常路径下改完只剩一条 ACTIVE（不变量由「先停旧再插新」保证）")
    void 版本化后ACTIVE唯一() {
        Long oldId = activePolicyId(TicketPriority.P1);
        slaPolicyManageService.update(oldId, dto(30, 240));

        Integer actives = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sla_policy WHERE priority = 'P1' AND status = 'ACTIVE'",
                Integer.class);
        assertThat(actives).as("同一 priority 只允许一条 ACTIVE").isEqualTo(1);

        // 再改一次，仍然只有一条
        Long newId = jdbcTemplate.queryForObject(
                "SELECT id FROM sla_policy WHERE priority = 'P1' AND status = 'ACTIVE'", Long.class);
        slaPolicyManageService.update(newId, dto(31, 241));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sla_policy WHERE priority = 'P1' AND status = 'ACTIVE'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("验收3说明：数据库唯一索引让「两条 ACTIVE」无法被普通 SQL 造出来 —— 真正的冲突场景是并发 PUT")
    void 两条ACTIVE无法被SQL造出() {
        Long oldId = activePolicyId(TicketPriority.P1);
        slaPolicyManageService.update(oldId, dto(30, 240));

        // 试图把已归档的旧版本改回 ACTIVE → 直接被唯一索引挡住
        // （这条断言本身就说明：验收3 的「第二条 ACTIVE」不可能靠单线程流程出现，
        //   真正会触发它的是并发 PUT —— 见 SlaPolicyVersionConcurrencyTest）
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sla_policy SET status = 'ACTIVE' WHERE id = ?", oldId))
                .as("uk_sla_active_priority 挡住了第二条 ACTIVE")
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ==================== 参数校验与状态前置 ====================

    @Test
    @DisplayName("解决时限 < 响应时限 → 40001")
    void 跨字段校验() {
        Long id = activePolicyId(TicketPriority.P3);
        assertThat(catchBiz(() -> slaPolicyManageService.update(id, dto(120, 60))))
                .as("resolutionMinutes 必须 ≥ responseMinutes")
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("不能修改历史版本（INACTIVE）→ 40900；不存在的 id → 40400")
    void 状态前置与存在性() {
        Long oldId = activePolicyId(TicketPriority.P1);
        SlaPolicyVO fresh = slaPolicyManageService.update(oldId, dto(30, 240));

        // 旧版本已经是 INACTIVE —— 再拿它来改应被拒（否则版本链会分叉）
        assertThat(catchBiz(() -> slaPolicyManageService.update(oldId, dto(31, 241))))
                .as("历史版本只读").isEqualTo(ErrorCode.CONFLICT);

        // 新版本可以继续改（每次都会再产生一个版本）
        assertThat(slaPolicyManageService.update(fresh.getId(), dto(32, 242)).getId())
                .isNotEqualTo(fresh.getId());

        assertThat(catchBiz(() -> slaPolicyManageService.update(999_999L, dto(30, 240))))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("§9.1：列表可按 priority / status 过滤；版本化后同一 priority 有多行")
    void 列表与过滤() {
        Long oldId = activePolicyId(TicketPriority.P1);
        slaPolicyManageService.update(oldId, dto(30, 240));

        List<SlaPolicyVO> p1All = slaPolicyManageService.list(TicketPriority.P1, null);
        assertThat(p1All).as("版本化后 P1 至少两条（一条 ACTIVE + 一条历史）").hasSizeGreaterThanOrEqualTo(2);
        assertThat(p1All).allMatch(vo -> vo.getPriority() == TicketPriority.P1);

        List<SlaPolicyVO> p1Active = slaPolicyManageService.list(TicketPriority.P1, "ACTIVE");
        assertThat(p1Active).as("ACTIVE 只有一条").hasSize(1);
        assertThat(p1Active.get(0).getStatus()).isEqualTo("ACTIVE");

        List<SlaPolicyVO> p1Inactive = slaPolicyManageService.list(TicketPriority.P1, "INACTIVE");
        assertThat(p1Inactive).isNotEmpty();
        assertThat(p1Inactive).allMatch(vo -> "INACTIVE".equals(vo.getStatus()));

        // §16.13 的 6 个字段
        SlaPolicyVO one = slaPolicyManageService.detail(p1Active.get(0).getId());
        assertThat(one.getId()).isNotNull();
        assertThat(one.getPriority()).isNotNull();
        assertThat(one.getResponseMinutes()).isNotNull();
        assertThat(one.getResolutionMinutes()).isNotNull();
        assertThat(one.getStatus()).isNotNull();
        assertThat(one.getEffectiveFrom()).isNotNull();
    }

    @Test
    @DisplayName("详情不存在 → 40400")
    void 详情不存在() {
        assertThat(catchBiz(() -> slaPolicyManageService.detail(999_999L)))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== 工具 ====================

    private Long activePolicyId(TicketPriority priority) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sla_policy WHERE priority = ? AND status = 'ACTIVE'",
                Long.class, priority.name());
    }

    private Integer minutesOf(Long policyId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM sla_policy WHERE id = ?", Integer.class, policyId);
    }

    private SlaPolicyUpdateDTO dto(int responseMinutes, int resolutionMinutes) {
        SlaPolicyUpdateDTO dto = new SlaPolicyUpdateDTO();
        dto.setResponseMinutes(responseMinutes);
        dto.setResolutionMinutes(resolutionMinutes);
        return dto;
    }

    private TicketCreateDTO newTicket(TicketPriority priority) {
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setTitle("D5-01 版本化验收单");
        dto.setDescription("验证新工单指向新的 SLA 策略版本");
        dto.setType(TicketType.INCIDENT);
        dto.setCategory(TicketCategory.NETWORK);
        dto.setPriority(priority);
        return dto;
    }

    private void setCurrentUser() {
        Long deptId = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, EMP_WANG)
                .stream().findFirst().orElse(null);
        UserContext.set(new UserContext.CurrentUser(
                EMP_WANG, "test-jti", Set.of(Role.EMPLOYEE), Set.of(), deptId));
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
