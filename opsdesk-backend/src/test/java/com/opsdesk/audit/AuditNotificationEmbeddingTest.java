package com.opsdesk.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.audit.service.AuditLogService;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.common.enums.NotificationType;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.notification.dto.NotificationQuery;
import com.opsdesk.notification.service.NotificationBizService;
import com.opsdesk.notification.vo.NotificationVO;
import com.opsdesk.organization.dto.DepartmentCreateDTO;
import com.opsdesk.organization.dto.DepartmentUpdateDTO;
import com.opsdesk.organization.service.DepartmentManageService;
import com.opsdesk.sla.dto.SlaPolicyUpdateDTO;
import com.opsdesk.sla.service.SlaPolicyManageService;
import com.opsdesk.ticket.dto.CommentCreateDTO;
import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.dto.TicketCancelDTO;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.service.TicketCommentBizService;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketFlowService;
import com.opsdesk.ticket.service.TicketHistoryService;
import com.opsdesk.user.dto.RolePermissionDTO;
import com.opsdesk.user.dto.UserCreateDTO;
import com.opsdesk.user.dto.UserRoleDTO;
import com.opsdesk.user.dto.UserStatusDTO;
import com.opsdesk.user.dto.UserUpdateDTO;
import com.opsdesk.user.service.RoleManageService;
import com.opsdesk.user.service.RolePermissionService;
import com.opsdesk.user.service.UserManageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D6-04 回归：§3.11 可执行业务方法的审计覆盖 + §3.10 通知触发点 + §14.4 去重。
 *
 * <p>{@code @Transactional}：所有业务数据、审计行、通知行都在测试事务里，跑完回滚。
 * Redis 工单号日序列不是事务资源，必须手工保存/还原。
 *
 * <p>⚠️ 本类只验证真的存在的写操作；没有接口的方法见 D6-04 交接表的 N/A 项。
 */
@SpringBootTest
@Transactional
class AuditNotificationEmbeddingTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;
    private static final long DEPARTMENT_FINANCE = 5L;
    private static final long ROLE_EMPLOYEE = 1L;

    @Autowired private TicketCreateService ticketCreateService;
    @Autowired private TicketFlowService ticketFlowService;
    @Autowired private TicketCommentBizService ticketCommentBizService;
    @Autowired private TicketHistoryService ticketHistoryService;
    @Autowired private UserManageService userManageService;
    @Autowired private RoleManageService roleManageService;
    @Autowired private RolePermissionService rolePermissionService;
    @Autowired private DepartmentManageService departmentManageService;
    @Autowired private SlaPolicyManageService slaPolicyManageService;
    @Autowired private NotificationBizService notificationBizService;
    @Autowired private AuditLogService auditLogService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ObjectMapper objectMapper;

    private long auditIdBaseline;
    private String ticketSequenceKey;
    private String ticketSequenceBefore;

    @BeforeEach
    void setUp() {
        auditIdBaseline = maxId("audit_log");
        ticketSequenceKey = "seq:ticket:" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        ticketSequenceBefore = redisTemplate.opsForValue().get(ticketSequenceKey);
        setCurrentUser(ADMIN, Role.ADMIN);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        if (ticketSequenceBefore == null) {
            redisTemplate.delete(ticketSequenceKey);
        }
        else {
            redisTemplate.opsForValue().set(ticketSequenceKey, ticketSequenceBefore,
                    com.opsdesk.ticket.support.TicketNoGenerator.SEQ_TTL);
        }
    }

    @Test
    @DisplayName("审计：USER/ROLE/DEPARTMENT/SLA 与 TICKET_CREATE 均有非空前后快照，密码不泄露")
    void 资源操作审计覆盖与快照脱敏() throws Exception {
        // ---- §3.11 USER_CREATE / UPDATE / STATUS_CHANGE / ROLE_CHANGE / DELETE ----
        UserCreateDTO createUser = new UserCreateDTO();
        createUser.setUsername("d604user");
        createUser.setPassword("secret123");
        createUser.setNickname("D6审计探针");
        createUser.setEmail("d604@example.test");
        createUser.setDepartmentId(DEPARTMENT_FINANCE);
        createUser.setRoleIds(List.of(ROLE_EMPLOYEE));
        Long userId = userManageService.create(createUser);

        UserUpdateDTO updateUser = new UserUpdateDTO();
        updateUser.setUsername("d604user");
        updateUser.setNickname("D6审计探针-已更新");
        updateUser.setEmail("d604-updated@example.test");
        updateUser.setDepartmentId(DEPARTMENT_FINANCE);
        updateUser.setRoleIds(List.of(ROLE_EMPLOYEE));
        userManageService.update(userId, updateUser);

        UserStatusDTO status = new UserStatusDTO();
        status.setStatus(0);
        userManageService.updateStatus(userId, status);

        UserRoleDTO roles = new UserRoleDTO();
        roles.setRoleIds(List.of(ROLE_EMPLOYEE));
        userManageService.assignRoles(userId, roles);
        userManageService.delete(userId);

        for (AuditOperation operation : List.of(AuditOperation.USER_CREATE, AuditOperation.USER_UPDATE,
                AuditOperation.USER_STATUS_CHANGE, AuditOperation.USER_ROLE_CHANGE, AuditOperation.USER_DELETE)) {
            assertOneAudit(operation, AuditResourceType.USER, userId);
        }
        JsonNode userAfter = afterData(AuditOperation.USER_CREATE, AuditResourceType.USER, userId);
        assertThat(userAfter.has("password")).as("USER 快照绝不包含明文或 BCrypt 密码").isFalse();
        assertThat(userAfter.get("roleIds")).isNotNull();

        // ---- §3.11 ROLE_PERMISSION_CHANGE ----
        List<Long> oldPermissionIds = roleManageService.listRoles().stream()
                .filter(role -> role.getId().equals(ROLE_EMPLOYEE))
                .findFirst().orElseThrow().getPermissionIds();
        List<Long> newPermissionIds = oldPermissionIds.isEmpty()
                ? List.of(171L) : oldPermissionIds.subList(1, oldPermissionIds.size());
        RolePermissionDTO permissionDTO = new RolePermissionDTO();
        permissionDTO.setPermissionIds(newPermissionIds);
        roleManageService.assignPermissions(ROLE_EMPLOYEE, permissionDTO);
        assertOneAudit(AuditOperation.ROLE_PERMISSION_CHANGE, AuditResourceType.ROLE, ROLE_EMPLOYEE);
        JsonNode roleBefore = beforeData(AuditOperation.ROLE_PERMISSION_CHANGE, AuditResourceType.ROLE, ROLE_EMPLOYEE);
        JsonNode roleAfter = afterData(AuditOperation.ROLE_PERMISSION_CHANGE, AuditResourceType.ROLE, ROLE_EMPLOYEE);
        assertThat(roleBefore.get("permissionIds")).isNotEqualTo(roleAfter.get("permissionIds"));

        // ---- §3.11 DEPARTMENT_CREATE / UPDATE / DELETE ----
        DepartmentCreateDTO createDepartment = new DepartmentCreateDTO();
        createDepartment.setParentId(0L);
        createDepartment.setName("D6-04 审计探针部门");
        createDepartment.setSort(99);
        Long departmentId = departmentManageService.create(createDepartment);

        DepartmentUpdateDTO updateDepartment = new DepartmentUpdateDTO();
        updateDepartment.setParentId(0L);
        updateDepartment.setName("D6-04 审计探针部门-改名");
        updateDepartment.setSort(100);
        departmentManageService.update(departmentId, updateDepartment);
        departmentManageService.delete(departmentId);
        for (AuditOperation operation : List.of(AuditOperation.DEPARTMENT_CREATE,
                AuditOperation.DEPARTMENT_UPDATE, AuditOperation.DEPARTMENT_DELETE)) {
            assertOneAudit(operation, AuditResourceType.DEPARTMENT, departmentId);
        }
        assertThat(afterData(AuditOperation.DEPARTMENT_DELETE, AuditResourceType.DEPARTMENT, departmentId))
                .as("删除后的快照应为空").isNull();

        // ---- §3.11 SLA_POLICY_UPDATE：版本化修改前/后必须分别快照旧/新版本 ----
        SlaPolicyUpdateDTO updatePolicy = new SlaPolicyUpdateDTO();
        updatePolicy.setResponseMinutes(121);
        updatePolicy.setResolutionMinutes(481);
        slaPolicyManageService.update(2L, updatePolicy); // 种子 P2 ACTIVE 版本
        assertOneAudit(AuditOperation.SLA_POLICY_UPDATE, AuditResourceType.SLA_POLICY, 2L);
        JsonNode slaBefore = beforeData(AuditOperation.SLA_POLICY_UPDATE, AuditResourceType.SLA_POLICY, 2L);
        JsonNode slaAfter = afterData(AuditOperation.SLA_POLICY_UPDATE, AuditResourceType.SLA_POLICY, 2L);
        assertThat(slaBefore.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(slaAfter.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(slaBefore.get("responseMinutes").asInt()).isNotEqualTo(slaAfter.get("responseMinutes").asInt());
        assertThat(slaAfter.get("id").asLong()).isNotEqualTo(2L);

        // ---- §3.11 TICKET_CREATE：资源 id 从返回值解析，before=null、after=新工单快照 ----
        TicketCreateDTO ticketDTO = new TicketCreateDTO();
        ticketDTO.setTitle("D6-04 审计通知联调");
        ticketDTO.setDescription("验证创建、分派、评论通知及审计去重。");
        ticketDTO.setType(TicketType.INCIDENT);
        ticketDTO.setCategory(TicketCategory.NETWORK);
        ticketDTO.setPriority(TicketPriority.P2);
        Long ticketId = ticketCreateService.create(ticketDTO, null).getId();
        assertOneAudit(AuditOperation.TICKET_CREATE, AuditResourceType.TICKET, ticketId);
        assertThat(beforeData(AuditOperation.TICKET_CREATE, AuditResourceType.TICKET, ticketId)).isNull();
        assertThat(afterData(AuditOperation.TICKET_CREATE, AuditResourceType.TICKET, ticketId)).isNotNull();
    }

    @Test
    @DisplayName("通知：分派/转派/状态/评论事件落库，且 ticket_history 与 audit_log 不重复记")
    void 工单通知触发与审计不重复() {
        TicketCreateDTO create = new TicketCreateDTO();
        create.setTitle("D6-04 通知探针");
        create.setDescription("分派与评论事件端到端验证。");
        create.setType(TicketType.INCIDENT);
        create.setCategory(TicketCategory.NETWORK);
        create.setPriority(TicketPriority.P2);
        // 让种子员工当创建人，确保分派操作者与创建人不同，能验证状态通知的真实收件人。
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        Long ticketId = ticketCreateService.create(create, null).getId();

        TicketAssignDTO assign = new TicketAssignDTO();
        assign.setAssigneeId(AGENT_ZHANG);
        setCurrentUser(ADMIN, Role.ADMIN);
        ticketFlowService.assign(ticketId, assign);

        // 新处理人从自己的通知列表读取，而非第三方视角。
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        PageResult<NotificationVO> agentNotifications = notificationBizService.page(new NotificationQuery());
        assertThat(agentNotifications.getList()).anySatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.TICKET_ASSIGNED);
            assertThat(notification.getBizId()).isEqualTo(ticketId);
        });

        // 赋予受理人后，创建人也收到一次独立的状态变更提醒。
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        PageResult<NotificationVO> creatorStatusNotifications = notificationBizService.page(new NotificationQuery());
        assertThat(creatorStatusNotifications.getList()).anySatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.TICKET_STATUS_CHANGED);
            assertThat(notification.getBizId()).isEqualTo(ticketId);
        });

        // 同一 assign 不能在 audit_log 重复，ticket_history 的 ASSIGN 仍是业务历史，不算重复审计。
        assertThat(countAudit(AuditOperation.TICKET_ASSIGN, AuditResourceType.TICKET, ticketId)).isEqualTo(1);
        Integer assignHistory = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_history WHERE ticket_id = ? AND action = 'ASSIGN'", Integer.class, ticketId);
        assertThat(assignHistory).isEqualTo(1);

        // 转派也给新处理人一条 TICKET_ASSIGNED；操作码是 TICKET_TRANSFER 而非重复 ASSIGN。
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        TicketAssignDTO transfer = new TicketAssignDTO();
        transfer.setAssigneeId(AGENT_LI);
        ticketFlowService.transfer(ticketId, transfer);
        assertThat(countAudit(AuditOperation.TICKET_TRANSFER, AuditResourceType.TICKET, ticketId)).isEqualTo(1);

        // 真实状态变更（ASSIGNED → IN_PROGRESS）映射到 TICKET_STATUS_CHANGE。
        setCurrentUser(AGENT_LI, Role.AGENT);
        ticketFlowService.start(ticketId);
        assertThat(countAudit(AuditOperation.TICKET_STATUS_CHANGE, AuditResourceType.TICKET, ticketId)).isEqualTo(1);

        setCurrentUser(AGENT_LI, Role.AGENT);
        PageResult<NotificationVO> transferNotifications = notificationBizService.page(new NotificationQuery());
        assertThat(transferNotifications.getList()).anySatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.TICKET_ASSIGNED);
            assertThat(notification.getBizId()).isEqualTo(ticketId);
        });

        // 评论事件发给对方（创建人）；其与 COMMENT history 是不同职责，不新增同义 audit_log。
        Integer statusAuditsBeforeComment = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE resource_type = 'TICKET' AND resource_id = ? "
                        + "AND operation = 'TICKET_STATUS_CHANGE'", Integer.class, ticketId);
        CommentCreateDTO comment = new CommentCreateDTO();
        comment.setContent("D6-04 通知对方");
        comment.setInternal(false);
        ticketCommentBizService.add(ticketId, comment);

        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        PageResult<NotificationVO> creatorNotifications = notificationBizService.page(new NotificationQuery());
        assertThat(creatorNotifications.getList()).anySatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.TICKET_COMMENT);
            assertThat(notification.getBizId()).isEqualTo(ticketId);
        });
        Integer commentAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE resource_type = 'TICKET' AND resource_id = ? "
                        + "AND operation = 'TICKET_STATUS_CHANGE'", Integer.class, ticketId);
        assertThat(commentAudits).as("评论由 ticket_history 记录；不得新增同义状态审计").isEqualTo(statusAuditsBeforeComment);
        assertThat(countAudit(AuditOperation.TICKET_CREATE, AuditResourceType.TICKET, ticketId)).isEqualTo(1);

        // TICKET_CANCEL：现有 cancel 写入口有专属操作码，不应被归成泛化 STATUS_CHANGE。
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        TicketCreateDTO cancelTicketDTO = new TicketCreateDTO();
        cancelTicketDTO.setTitle("D6-04 cancel audit probe");
        cancelTicketDTO.setDescription("验证取消操作码。");
        cancelTicketDTO.setType(TicketType.INCIDENT);
        cancelTicketDTO.setCategory(TicketCategory.NETWORK);
        cancelTicketDTO.setPriority(TicketPriority.P2);
        Long cancelTicketId = ticketCreateService.create(cancelTicketDTO, null).getId();
        TicketCancelDTO cancelDTO = new TicketCancelDTO();
        cancelDTO.setReason("验收取消审计");
        ticketFlowService.cancel(cancelTicketId, cancelDTO);
        assertThat(countAudit(AuditOperation.TICKET_CANCEL, AuditResourceType.TICKET, cancelTicketId)).isEqualTo(1);
    }

    // ==================== helpers ====================

    private void assertOneAudit(AuditOperation operation, AuditResourceType resourceType, Long resourceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE id > ? AND operation = ? "
                        + "AND resource_type = ? AND resource_id = ?",
                Integer.class, auditIdBaseline, operation.name(), resourceType.name(), resourceId);
        assertThat(count).as("%s / %s / %s 应恰好一条审计", operation, resourceType, resourceId).isEqualTo(1);
        JsonNode before = beforeData(operation, resourceType, resourceId);
        JsonNode after = afterData(operation, resourceType, resourceId);
        if (operation == AuditOperation.USER_CREATE || operation == AuditOperation.DEPARTMENT_CREATE
                || operation == AuditOperation.TICKET_CREATE) {
            assertThat(before).as("create 操作 before 资源不存在").isNull();
            assertThat(after).as("create 操作 after 有资源快照").isNotNull();
        }
        else if (operation == AuditOperation.USER_DELETE || operation == AuditOperation.DEPARTMENT_DELETE) {
            assertThat(before).as("delete 操作 before 有资源快照").isNotNull();
            assertThat(after).as("delete 操作 after 资源不存在").isNull();
        }
        else {
            assertThat(before).as("%s before 快照", operation).isNotNull();
            assertThat(after).as("%s after 快照", operation).isNotNull();
        }
    }

    private int countAudit(AuditOperation operation, AuditResourceType resourceType, Long resourceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE id > ? AND operation = ? "
                        + "AND resource_type = ? AND resource_id = ?",
                Integer.class, auditIdBaseline, operation.name(), resourceType.name(), resourceId);
        return count == null ? 0 : count;
    }

    private JsonNode beforeData(AuditOperation operation, AuditResourceType resourceType, Long resourceId) {
        return snapshotColumn(operation, resourceType, resourceId, "before_data");
    }

    private JsonNode afterData(AuditOperation operation, AuditResourceType resourceType, Long resourceId) {
        return snapshotColumn(operation, resourceType, resourceId, "after_data");
    }

    private JsonNode snapshotColumn(AuditOperation operation, AuditResourceType resourceType,
                                   Long resourceId, String column) {
        String json = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM audit_log WHERE id > ? AND operation = ? "
                        + "AND resource_type = ? AND resource_id = ? ORDER BY id DESC LIMIT 1",
                String.class, auditIdBaseline, operation.name(), resourceType.name(), resourceId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        }
        catch (Exception ex) {
            throw new AssertionError("审计快照不是合法 JSON", ex);
        }
    }

    private long maxId(String table) {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return max == null ? 0L : max;
    }

    private void setCurrentUser(long userId, Role role) {
        Long departmentId = jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        UserContext.set(new UserContext.CurrentUser(userId, "d604-jti", Set.of(role), Set.of(), departmentId));
    }
}
