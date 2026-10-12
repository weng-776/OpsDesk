package com.opsdesk.mq;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.NotificationType;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.service.EventOutboxService;
import com.opsdesk.mq.support.OutboxPublisher;
import com.opsdesk.sla.service.SlaScanService;
import com.opsdesk.ticket.dto.CommentCreateDTO;
import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.service.TicketCommentBizService;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketFlowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * D9-03 验收：通知迁移到 MQ（触发点一行不改）+ 业务事件接线
 *
 * <h2>⚠️ 本类刻意不加 {@code @Transactional}（否则整类白测）</h2>
 * 通知链路是<b>异步跨事务</b>的：
 * <pre>
 * 业务事务内写 event_outbox(PENDING)  →  OutboxPublisher 自己开事务投递
 *     →  消费者容器线程自己开事务写 notification
 * </pre>
 * 若测试挂一个外层事务，outbox 行会被回滚掉、投递器根本看不到它，
 * 消费者写下的通知又在测试事务之外（回滚不了）—— 两头都不可控。
 * 所以照 {@code TicketCreateServiceTest} 的老办法：<b>非事务 + 按 id 基线自造自清</b>。
 *
 * <h2>⚠️ 继承 {@link AbstractMqIntegrationTest}</h2>
 * 消费者容器由基类显式启停（测试配置里 auto-startup=false，避免多个缓存上下文竞争消费）。
 *
 * <h2>为什么要显式调 {@code publishPending()} 而不是等定时任务</h2>
 * 定时投递间隔 10s（且测试配置里 {@code opsdesk.outbox.publish.enabled=false} 关掉了）。
 * 这里直接驱动一轮投递 → 断言确定、用例快，且验的正是「outbox → MQ → 消费者」这段链路。
 */
class NotificationMqMigrationTest extends AbstractMqIntegrationTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long EMP_WANG = 4L;

    /** 只服务于「业务回滚 → 通知不产生」那条用例 */
    @MockitoSpyBean
    private EventOutboxService eventOutboxService;

    @Autowired private TicketCreateService ticketCreateService;
    @Autowired private TicketFlowService ticketFlowService;
    @Autowired private TicketCommentBizService ticketCommentBizService;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private SlaScanService slaScanService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final Map<String, Long> baseline = new java.util.LinkedHashMap<>();

    /**
     * 被本类改过的工单的 SLA 列快照：{@code id -> 原始列值}。
     *
     * <h2>⚠️ 为什么必须快照<b>全部</b>候选工单，而不是只快照自己要动的那张</h2>
     * {@code slaScanService.scan()} 扫的是<b>全库未关闭工单</b>。种子工单的 deadline 是
     * 2026-10-06（跑测试时早已过期），所以每扫一轮都会把它们改成 {@code BREACHED} 并置
     * {@code sla_breach_notified = 1} —— 那会打红 {@code TicketQueryServiceTest} 里
     * 「种子把它设成了 WARNING」「详情正常返回 NORMAL」这类精确断言。
     * 只恢复自己动过的工单是不够的，必须把整批候选都还原。
     *
     * <p>同理，扫描前先把候选集<b>归一化</b>成「deadline 远在未来 + NORMAL + 标志 0」，
     * 这样一轮扫描只有本类刻意设成「即将到期」的那张工单会产生通知 ——
     * 否则其它工单的通知会被本类的投递一起投出去，污染 {@code notification} 表。
     */
    private final Map<Long, Map<String, Object>> slaSnapshot = new java.util.LinkedHashMap<>();

    /** 会被扫描的候选集：未关闭（非终态） */
    private static final String CANDIDATE_PREDICATE =
            "deleted = 0 AND status NOT IN ('CLOSED','CANCELLED')";

    private static final String SLA_COLUMNS =
            "response_deadline, resolution_deadline, sla_response_state, sla_resolution_state, "
                    + "sla_warning_notified, sla_breach_notified";

    private ListAppender<ILoggingEvent> appender;
    private Logger opsdeskLogger;

    @BeforeEach
    void captureBaselinesAndAttachAppender() {
        for (String table : List.of("ticket", "ticket_history", "event_outbox", "audit_log", "notification")) {
            baseline.put(table, maxId(table));
        }

        // 快照 + 归一化全部候选工单的 SLA 列（见 slaSnapshot 的注释）
        slaSnapshot.clear();
        for (Long id : jdbcTemplate.queryForList(
                "SELECT id FROM ticket WHERE " + CANDIDATE_PREDICATE, Long.class)) {
            slaSnapshot.put(id, jdbcTemplate.queryForMap(
                    "SELECT " + SLA_COLUMNS + " FROM ticket WHERE id = ?", id));
        }
        jdbcTemplate.update(
                "UPDATE ticket SET response_deadline = ?, resolution_deadline = ?, "
                        + "sla_response_state = 'NORMAL', sla_resolution_state = 'NORMAL', "
                        + "sla_warning_notified = 0, sla_breach_notified = 0 "
                        + "WHERE " + CANDIDATE_PREDICATE
                        + " AND status NOT IN ('WAITING_USER','WAITING_CONFIRM')",
                Timestamp.valueOf(LocalDateTime.now().plusDays(30)),
                Timestamp.valueOf(LocalDateTime.now().plusDays(30)));

        // 抓 com.opsdesk 全包日志：既要「写通知」那行（notification 包），
        // 也要消费者那行（mq 包）——验收 5 要断言它们与 HTTP 请求同一个 traceId
        opsdeskLogger = (Logger) LoggerFactory.getLogger("com.opsdesk");
        appender = new ListAppender<>();
        appender.start();
        opsdeskLogger.addAppender(appender);
    }

    @AfterEach
    void cleanUp() {
        UserContext.clear();
        MDC.clear();
        if (opsdeskLogger != null && appender != null) {
            opsdeskLogger.detachAppender(appender);
        }
        // 还原全部候选工单的 SLA 列（含扫描顺手改掉的那些）
        for (Map.Entry<Long, Map<String, Object>> entry : slaSnapshot.entrySet()) {
            Map<String, Object> row = entry.getValue();
            jdbcTemplate.update(
                    "UPDATE ticket SET response_deadline = ?, resolution_deadline = ?, "
                            + "sla_response_state = ?, sla_resolution_state = ?, "
                            + "sla_warning_notified = ?, sla_breach_notified = ? WHERE id = ?",
                    row.get("response_deadline"), row.get("resolution_deadline"),
                    row.get("sla_response_state"), row.get("sla_resolution_state"),
                    row.get("sla_warning_notified"), row.get("sla_breach_notified"), entry.getKey());
        }
        slaSnapshot.clear();
        // 先删子表再删主表（外键顺序），一律按 id 基线，绝不碰种子数据
        for (String table : List.of("notification", "event_outbox", "audit_log",
                "ticket_history", "ticket")) {
            Long id = baseline.get(table);
            if (id != null) {
                jdbcTemplate.update("DELETE FROM " + table + " WHERE id > ?", id);
            }
        }
    }

    // ==================== 验收 1：分派 → 通知只有一条（无重复路径）====================

    @Test
    @DisplayName("验收1：分派工单 → 处理人收到 1 条 TICKET_ASSIGNED，创建人收到 1 条 TICKET_STATUS_CHANGED")
    void 分派通知只发一条() {
        Long ticketId = createTicketAsEmployee();
        assignAsAdmin(ticketId, AGENT_ZHANG);

        deliverOutboxAndAwaitNotification(NotificationType.TICKET_ASSIGNED, AGENT_ZHANG, ticketId);
        deliverOutboxAndAwaitNotification(NotificationType.TICKET_STATUS_CHANGED, EMP_WANG, ticketId);

        assertThat(notificationCount(NotificationType.TICKET_ASSIGNED, AGENT_ZHANG, ticketId))
                .as("§13.3：分派通知只能有一条 —— 两条路径同时投递就会出现 2 条")
                .isEqualTo(1);
        assertThat(notificationCount(NotificationType.TICKET_STATUS_CHANGED, EMP_WANG, ticketId))
                .as("状态变更通知也只应有一条").isEqualTo(1);
    }

    // ==================== 验收 2：评论 + SLA 提醒都过 MQ 送达 ====================

    @Test
    @DisplayName("验收2：评论 → 对方收到 TICKET_COMMENT；SLA 扫描 → 收到 SLA_WARNING")
    void 评论与SLA提醒都能过MQ送达() {
        Long ticketId = createTicketAsEmployee();
        assignAsAdmin(ticketId, AGENT_ZHANG);
        deliverOutboxAndAwaitNotification(NotificationType.TICKET_ASSIGNED, AGENT_ZHANG, ticketId);

        // 评论：操作者是处理人 → 对方 = 创建人
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        CommentCreateDTO comment = new CommentCreateDTO();
        comment.setContent("D9-03 评论过 MQ");
        comment.setInternal(false);
        ticketCommentBizService.add(ticketId, comment);
        deliverOutboxAndAwaitNotification(NotificationType.TICKET_COMMENT, EMP_WANG, ticketId);

        // SLA：把种子工单 1 的解决时限设成「即将到期」→ 扫一轮 → 通知请求过 MQ 送达
        //    （候选集已在 @BeforeEach 归一化，所以这一轮只有工单 1 会产生通知；
        //      改动会在 @AfterEach 由 slaSnapshot 还原）
        Long recipientId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(assignee_id, creator_id) FROM ticket WHERE id = 1", Long.class);
        jdbcTemplate.update("UPDATE ticket SET resolution_deadline = ? WHERE id = 1",
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(1)));
        slaScanService.scan();
        deliverOutboxAndAwaitNotification(NotificationType.SLA_WARNING, recipientId, 1L);
    }

    // ==================== 验收 4：业务回滚 → 通知不产生 ====================

    @Test
    @DisplayName("验收4：写 outbox 失败 → 业务整体回滚，通知一条都不产生（不会出现假通知）")
    void 业务回滚时通知不产生() {
        Long ticketId = createTicketAsEmployee();

        // 让「写本地消息表」这一步炸掉 —— 它跑在业务事务里，失败必须整笔回滚
        doThrow(new IllegalStateException("模拟写 outbox 失败"))
                .when(eventOutboxService).save(any());

        setCurrentUser(ADMIN, Role.ADMIN);
        TicketAssignDTO assign = new TicketAssignDTO();
        assign.setAssigneeId(AGENT_ZHANG);
        assertThatThrownBy(() -> ticketFlowService.assign(ticketId, assign))
                .as("写 outbox 失败应让分派整体失败")
                .isInstanceOf(RuntimeException.class);

        // ① 业务回滚了：工单状态没变、没有处理人
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, assignee_id FROM ticket WHERE id = ?", ticketId);
        assertThat(row.get("status")).as("分派应整体回滚").isEqualTo("OPEN");
        assertThat(row.get("assignee_id")).as("处理人不该被写上").isNull();

        // ② 没有留下 outbox 行（与业务同事务，一起回滚了）
        assertThat(countSinceBaseline("event_outbox", "event_type = 'NotificationRequestedEvent'"))
                .as("outbox 行必须与业务一起回滚").isZero();

        // ③ 因此也绝不会产生通知 —— 这才是「业务回滚 → 通知不产生」的真正含义
        outboxPublisher.publishPending();
        assertThat(countSinceBaseline("notification", "1 = 1"))
                .as("§13.5：不会出现「工单没分派成功却收到通知」的假通知").isZero();
    }

    // ==================== 验收 5：整条链路 traceId 一致 ====================

    @Test
    @DisplayName("验收5：HTTP 请求的 traceId 经 outbox → MQ → 消费者，一路带到「写通知」的日志里")
    void traceId全链一致() {
        String httpTraceId = "d903-http-" + UUID.randomUUID().toString().substring(0, 8);
        Long ticketId = createTicketAsEmployee();

        // 模拟 TraceIdFilter：HTTP 线程的 MDC 里有 traceId，
        // NotificationEventListener 就发生在这个线程里（同步 @EventListener）
        MDC.put("traceId", httpTraceId);
        try {
            assignAsAdmin(ticketId, AGENT_ZHANG);
        }
        finally {
            MDC.clear();
        }

        // ① traceId 随 payload 进了 outbox（表里没有 traceId 列，只能走 payload）
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM event_outbox WHERE id > ? AND event_type = 'NotificationRequestedEvent' "
                        + "ORDER BY id LIMIT 1", String.class, baseline.get("event_outbox"));
        assertThat(payload).as("发布时的 MDC traceId 应写进 payload").contains(httpTraceId);

        // ② 消费端把它覆盖进 MDC → 「写通知」那行日志与 HTTP 请求同 traceId
        deliverOutboxAndAwaitNotification(NotificationType.TICKET_ASSIGNED, AGENT_ZHANG, ticketId);
        ILoggingEvent notified = awaitLogContaining("[通知] 已发送", 15_000);
        assertThat(notified.getMDCPropertyMap().get("traceId"))
                .as("§23.6：写通知的日志必须与最初那次 HTTP 请求同一个 traceId")
                .isEqualTo(httpTraceId);
    }

    // ==================== 工具 ====================

    private Long createTicketAsEmployee() {
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setTitle("D9-03 通知过 MQ 探针");
        dto.setDescription("验证通知经 outbox → MQ → 消费者落库。");
        dto.setType(TicketType.INCIDENT);
        dto.setCategory(TicketCategory.NETWORK);
        dto.setPriority(TicketPriority.P2);
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        return ticketCreateService.create(dto, null).getId();
    }

    private void assignAsAdmin(Long ticketId, Long assigneeId) {
        setCurrentUser(ADMIN, Role.ADMIN);
        TicketAssignDTO assign = new TicketAssignDTO();
        assign.setAssigneeId(assigneeId);
        ticketFlowService.assign(ticketId, assign);
    }

    /** 驱动一轮投递，然后等通知落库（消费者是异步的） */
    private void deliverOutboxAndAwaitNotification(NotificationType type, Long userId, Long bizId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            outboxPublisher.publishPending();
            if (notificationCount(type, userId, bizId) > 0) {
                return;
            }
            sleep();
        }
        assertThat(notificationCount(type, userId, bizId))
                .as("等待 %s（user=%s biz=%s）落库超时", type, userId, bizId)
                .isGreaterThan(0);
    }

    private int notificationCount(NotificationType type, Long userId, Long bizId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE id > ? AND type = ? AND user_id = ? AND biz_id = ?",
                Integer.class, baseline.get("notification"), type.name(), userId, bizId);
        return count == null ? 0 : count;
    }

    private int countSinceBaseline(String table, String where) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id > ? AND " + where,
                Integer.class, baseline.get(table));
        return count == null ? 0 : count;
    }

    private ILoggingEvent awaitLogContaining(String keyword, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (ILoggingEvent event : appender.list) {
                if (event.getFormattedMessage().contains(keyword)) {
                    return event;
                }
            }
            sleep();
        }
        throw new AssertionError("等待日志超时：" + keyword);
    }

    private long maxId(String table) {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return max == null ? 0L : max;
    }

    private void setCurrentUser(long userId, Role role) {
        Long departmentId = jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        UserContext.set(new UserContext.CurrentUser(userId, "d903-jti", Set.of(role), Set.of(), departmentId));
    }

    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(200);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", ex);
        }
    }
}
