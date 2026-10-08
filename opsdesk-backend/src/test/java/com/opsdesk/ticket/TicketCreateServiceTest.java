package com.opsdesk.ticket;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.service.EventOutboxService;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.support.IdempotencyGuard;
import com.opsdesk.ticket.support.TicketNoGenerator;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 创建工单验收测试（工单 D3-01，SOP §5 红区：幂等 + 事务边界）
 *
 * <p>规格依据：API 文档 §8.1、规格基线 §5.3 / §6.1 / §9.2 / §13.5 / §23.3。
 *
 * <h2>⚠️ 本类<b>刻意不加</b> {@code @Transactional}</h2>
 * 幂等结果是在**事务提交后**的 {@code afterCompletion} 里写进 Redis 的
 * （提前写的话，事务一但回滚，幂等记录里就留下了一张不存在的工单）。
 * 如果测试自己也挂一个外层事务，服务加入的是<b>测试的事务</b>，测试结束一滚回，
 * {@code afterCompletion} 拿到的是 {@code ROLLED_BACK} → 幂等结果永远写不进去，
 * 「重复提交返回首次结果」根本验不了。
 * <p>所以本类用<b>真提交</b>，并自己按「id &gt; 基线」清理自造数据（不碰种子数据）。
 *
 * <p>{@code @MockitoSpyBean} 只服务于「回滚」两条用例：默认转发真实实现，
 * 只在需要时 {@code doThrow} 让 outbox 写入失败。Mockito 会在每个用例后自动 reset。
 */
@SpringBootTest
class TicketCreateServiceTest {

    /** 种子账号 emp_wang：EMPLOYEE，财务部(id=5) */
    private static final Long EMP_WANG = 4L;
    private static final Long FINANCE_DEPT = 5L;

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");

    @MockitoSpyBean
    private EventOutboxService eventOutboxService;

    @Autowired
    private TicketCreateService ticketCreateService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private long ticketIdBaseline;
    private long historyIdBaseline;
    private long outboxIdBaseline;

    /**
     * 今天的日序列 key 及其原值。
     *
     * <p>「工单号冲突重试」用例会把序列推到一个高位（90000+），如果不还原，
     * 之后真实创建的工单号会莫名其妙从 90003 开始 —— 这是**往共享 Redis 里泄漏副作用**，
     * 所以 setUp 记下原值、tearDown 还原。
     */
    private String seqKeyToday;
    private String seqValueBefore;

    /** 本类用过的幂等键，跑完清掉（Redis 不参与回滚，必须自己清） */
    private final List<String> usedIdempotencyKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ticketIdBaseline = maxId("ticket");
        historyIdBaseline = maxId("ticket_history");
        outboxIdBaseline = maxId("event_outbox");
        seqKeyToday = TicketNoGenerator.SEQ_KEY_PREFIX + LocalDate.now().format(DATE_PART);
        seqValueBefore = redisTemplate.opsForValue().get(seqKeyToday);
        // 直接构造 CurrentUser（不走拦截器）：本类只测 service，
        // 拦截器那条链由 AuthInterceptorTest 守
        UserContext.set(new UserContext.CurrentUser(
                EMP_WANG, "test-jti", Set.of(Role.EMPLOYEE), Set.of(), FINANCE_DEPT));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        // 自造自清：只删本次用例新产生的行（按 id 基线），种子数据一行不动
        jdbcTemplate.update("DELETE FROM ticket_history WHERE id > ?", historyIdBaseline);
        jdbcTemplate.update("DELETE FROM event_outbox WHERE id > ?", outboxIdBaseline);
        jdbcTemplate.update("DELETE FROM ticket WHERE id > ?", ticketIdBaseline);
        usedIdempotencyKeys.forEach(key ->
                redisTemplate.delete(IdempotencyGuard.KEY_PREFIX + key));
        usedIdempotencyKeys.clear();
        // 还原日序列：不还原的话，本类跑完会把今天的工单号推到 90000+（副作用泄漏）
        if (seqValueBefore == null) {
            redisTemplate.delete(seqKeyToday);
        }
        else {
            redisTemplate.opsForValue().set(seqKeyToday, seqValueBefore, TicketNoGenerator.SEQ_TTL);
        }
    }

    // ==================== 验收 1 / 3：正常路径 + 三张表 ====================

    @Test
    @DisplayName("验收1+3：创建成功 → 工单号形如 OD{今天}NNNNN，三张表各 1 行")
    void 创建成功且三张表都有记录() {
        TicketCreatedVO created = ticketCreateService.create(dto(TicketPriority.P2), null);

        assertThat(created.getId()).isNotNull();
        String today = LocalDate.now().format(DATE_PART);
        assertThat(created.getTicketNo())
                .as("§5.3：OD + yyyyMMdd + 5 位日序列")
                .startsWith("OD" + today)
                .matches("OD\\d{13}");

        // ---- ticket 的字段按 §6.1 逐条核对 ----
        Ticket saved = ticketService.getById(created.getId());
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(saved.getAssigneeId()).as("未分派 → 进公共池（§8.3 的 ②）").isNull();
        assertThat(saved.getCreatorId()).isEqualTo(EMP_WANG);
        assertThat(saved.getDepartmentId()).as("创建人部门快照（§6.1）").isEqualTo(FINANCE_DEPT);
        assertThat(saved.getSource()).as("§6.1「按入口写入」→ Web 入口 = WEB").isEqualTo(TicketSource.WEB);
        assertThat(saved.getPriority()).isEqualTo(TicketPriority.P2);
        assertThat(saved.getType()).isEqualTo(TicketType.INCIDENT);
        assertThat(saved.getCategory()).isEqualTo(TicketCategory.NETWORK);
        assertThat(saved.getCreatedAt()).as("created_at 由 DB 默认值填").isNotNull();

        // ---- ticket_history：1 行，action = CREATE ----
        List<Map<String, Object>> histories = jdbcTemplate.queryForList(
                "SELECT action, from_status, to_status, operator_id FROM ticket_history WHERE ticket_id = ?",
                saved.getId());
        assertThat(histories).as("恰好 1 行历史").hasSize(1);
        assertThat(histories.get(0).get("action")).isEqualTo("CREATE");
        assertThat(histories.get(0).get("from_status")).as("创建没有前态").isNull();
        assertThat(histories.get(0).get("to_status")).isEqualTo("OPEN");
        assertThat(((Number) histories.get(0).get("operator_id")).longValue()).isEqualTo(EMP_WANG);

        // ---- event_outbox：1 行，status = PENDING ----
        List<Map<String, Object>> outboxes = jdbcTemplate.queryForList(
                "SELECT event_type, message_id, routing_key, status, payload FROM event_outbox WHERE id > ?",
                outboxIdBaseline);
        assertThat(outboxes).as("恰好 1 行 outbox").hasSize(1);
        Map<String, Object> outbox = outboxes.get(0);
        assertThat(outbox.get("event_type")).isEqualTo("TicketCreatedEvent");
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        assertThat(outbox.get("routing_key")).isEqualTo(RabbitMQConfig.RK_TICKET_CREATED);
        assertThat((String) outbox.get("message_id")).as("messageId 是消费端幂等键").isNotBlank();
        assertThat((String) outbox.get("payload"))
                .contains("\"ticketNo\"").contains(created.getTicketNo());
    }

    @Test
    @DisplayName("§9.2：SLA 在创建时就算好（从 created_at 起算），并记下策略版本 id")
    void SLA在创建时算好() {
        TicketCreatedVO created = ticketCreateService.create(dto(TicketPriority.P2), null);
        Ticket saved = ticketService.getById(created.getId());

        Long activePolicyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sla_policy WHERE priority = 'P2' AND status = 'ACTIVE'", Long.class);
        assertThat(saved.getSlaPolicyId()).as("§9.1：记录创建时适用的策略版本").isEqualTo(activePolicyId);
        assertThat(saved.getResponseDeadline())
                .as("P2 响应时限 120 分钟").isEqualTo(saved.getCreatedAt().plusMinutes(120));
        assertThat(saved.getResolutionDeadline())
                .as("P2 解决时限 480 分钟").isEqualTo(saved.getCreatedAt().plusMinutes(480));
    }

    @Test
    @DisplayName("§6.1：不传 priority 默认 P3（SLA 也随之按 P3 算）")
    void 缺省优先级是P3() {
        TicketCreatedVO created = ticketCreateService.create(dto(null), null);
        Ticket saved = ticketService.getById(created.getId());

        assertThat(saved.getPriority()).isEqualTo(TicketPriority.P3);
        assertThat(saved.getResponseDeadline()).isEqualTo(saved.getCreatedAt().plusMinutes(480));
        assertThat(saved.getResolutionDeadline()).isEqualTo(saved.getCreatedAt().plusMinutes(1440));
    }

    @Test
    @DisplayName("验收1：同一天连续创建，序列递增（差 1）")
    void 同一天序列递增() {
        int first = seqOf(ticketCreateService.create(dto(null), null).getTicketNo());
        int second = seqOf(ticketCreateService.create(dto(null), null).getTicketNo());
        assertThat(second).as("同一天第二个号应比第一个大 1").isEqualTo(first + 1);
    }

    @Test
    @DisplayName("没有 ACTIVE 策略时不阻断创建：工单照落库，SLA 字段留空（§6.1 不依赖配置）")
    void 没有ACTIVE策略时不阻断创建() {
        jdbcTemplate.update("UPDATE sla_policy SET status = 'INACTIVE' WHERE priority = 'P3'");
        try {
            TicketCreatedVO created = ticketCreateService.create(dto(TicketPriority.P3), null);
            Ticket saved = ticketService.getById(created.getId());

            assertThat(saved).as("工单仍然创建成功").isNotNull();
            assertThat(saved.getSlaPolicyId()).isNull();
            assertThat(saved.getResponseDeadline()).isNull();
            assertThat(saved.getResolutionDeadline()).isNull();
        }
        finally {
            jdbcTemplate.update("UPDATE sla_policy SET status = 'ACTIVE' WHERE priority = 'P3'");
        }
    }

    // ==================== 验收 2：幂等 ====================

    @Test
    @DisplayName("验收2：同一 Idempotency-Key 重复提交 → 只 1 张工单，两次响应体一致")
    void 幂等重复提交只产生一张工单() {
        String key = newKey();

        TicketCreatedVO first = ticketCreateService.create(dto(null), key);
        TicketCreatedVO second = ticketCreateService.create(dto(null), key);

        assertThat(second.getId()).as("第二次返回的是首次的 id").isEqualTo(first.getId());
        assertThat(second.getTicketNo()).as("两次响应体一致").isEqualTo(first.getTicketNo());

        assertThat(countNewTickets()).as("只多 1 张工单").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_history WHERE id > ?", Long.class, historyIdBaseline))
                .as("历史也只多 1 行（没有重复副作用）").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE id > ?", Long.class, outboxIdBaseline))
                .as("outbox 也只多 1 行").isEqualTo(1L);
    }

    @Test
    @DisplayName("幂等键已占位、首次还没写完结果（双击提交）→ 40900，且不建工单")
    void 幂等占位中返回40900() {
        String key = newKey();
        // 手工模拟「另一个请求抢了占位但结果还没写回」
        redisTemplate.opsForValue().set(
                IdempotencyGuard.KEY_PREFIX + key, "PENDING", IdempotencyGuard.TTL);

        assertThat(catchBiz(() -> ticketCreateService.create(dto(null), key)).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(countNewTickets()).as("不能建出工单").isZero();
    }

    @Test
    @DisplayName("不带 Idempotency-Key 时退化成普通创建：两次提交就是两张工单")
    void 不传幂等键时正常创建两张() {
        ticketCreateService.create(dto(null), null);
        ticketCreateService.create(dto(null), null);
        assertThat(countNewTickets()).isEqualTo(2L);
    }

    // ==================== 验收 3 后半：事务回滚 ====================

    @Test
    @DisplayName("验收3后半：Outbox 写入失败 → 工单也不该落库（三张表都不多行）")
    void outbox失败时工单整体回滚() {
        doThrow(new RuntimeException("模拟 outbox 写入失败")).when(eventOutboxService).save(any());

        assertThatThrownBy(() -> ticketCreateService.create(dto(null), null))
                .isInstanceOf(RuntimeException.class);

        assertThat(countNewTickets()).as("ticket 不该多行").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_history WHERE id > ?", Long.class, historyIdBaseline))
                .as("ticket_history 不该多行").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE id > ?", Long.class, outboxIdBaseline))
                .as("event_outbox 不该多行").isZero();
    }

    @Test
    @DisplayName("回滚后释放幂等占位：同一个 key 能重新提交（否则 24h 内重试全被挡死）")
    void 回滚后释放幂等占位() {
        String key = newKey();

        doThrow(new RuntimeException("第一次故意失败")).when(eventOutboxService).save(any());
        assertThatThrownBy(() -> ticketCreateService.create(dto(null), key))
                .isInstanceOf(RuntimeException.class);
        assertThat(redisTemplate.hasKey(IdempotencyGuard.KEY_PREFIX + key))
                .as("占位必须被释放").isFalse();

        // 恢复正常后，同一个 key 应该能成功创建（而不是被占位挡成 40900）
        doCallRealMethod().when(eventOutboxService).save(any());
        TicketCreatedVO created = ticketCreateService.create(dto(null), key);

        assertThat(created.getTicketNo()).isNotBlank();
        assertThat(countNewTickets()).isEqualTo(1L);
    }

    // ==================== 工单号冲突重试（§5.3） ====================

    @Test
    @DisplayName("§5.3：工单号撞唯一索引 → 自动重新取号重试（覆盖 Redis 丢数据导致序列归零）")
    void 工单号冲突时自动重试() {
        String datePart = LocalDate.now().format(DATE_PART);
        // 把今天的序列推到一个不会跟其他用例撞的高位
        redisTemplate.opsForValue().set(TicketNoGenerator.SEQ_KEY_PREFIX + datePart, "90000");
        // 预先占掉「下一个会生成的号」= 90001，逼出一次冲突
        insertProbeTicket("OD" + datePart + "90001");

        TicketCreatedVO created = ticketCreateService.create(dto(null), null);

        assertThat(created.getTicketNo())
                .as("第一次取到 90001 撞了唯一索引，重试后取到 90002")
                .isEqualTo("OD" + datePart + "90002");
        assertThat(countNewTickets()).as("最终只落 1 张（探针那张是手工插的）").isEqualTo(2L);
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("未登录（UserContext 为空）→ 40100，而不是 500")
    void 未登录返回40100() {
        UserContext.clear();
        assertThat(catchBiz(() -> ticketCreateService.create(dto(null), null)).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    // ==================== 工具 ====================

    private TicketCreateDTO dto(TicketPriority priority) {
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setTitle("公司 VPN 无法连接");
        dto.setDescription("今天上午开始 VPN 一直连接失败，浏览器和 Git 都访问不了公司内网。");
        dto.setType(TicketType.INCIDENT);
        dto.setCategory(TicketCategory.NETWORK);
        dto.setPriority(priority);
        return dto;
    }

    private String newKey() {
        String key = "d301-" + UUID.randomUUID();
        usedIdempotencyKeys.add(key);
        return key;
    }

    private int seqOf(String ticketNo) {
        return Integer.parseInt(ticketNo.substring(ticketNo.length() - 5));
    }

    private long countNewTickets() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE id > ?", Long.class, ticketIdBaseline);
        return count == null ? 0L : count;
    }

    private long maxId(String table) {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return max == null ? 0L : max;
    }

    /** 手工插一张占位工单，用来制造 uk_ticket_no 冲突 */
    private void insertProbeTicket(String ticketNo) {
        Ticket probe = new Ticket();
        probe.setTicketNo(ticketNo);
        probe.setTitle("工单号冲突探针");
        probe.setDescription("D3-01 验收用");
        probe.setType(TicketType.INCIDENT);
        probe.setCategory(TicketCategory.OTHER);
        probe.setPriority(TicketPriority.P3);
        probe.setStatus(TicketStatus.OPEN);
        probe.setSource(TicketSource.WEB);
        probe.setCreatorId(EMP_WANG);
        ticketService.save(probe);
    }

    private static BizException catchBiz(Runnable action) {
        try {
            action.run();
        }
        catch (BizException ex) {
            return ex;
        }
        throw new AssertionError("预期抛出 BizException，但调用正常返回了");
    }
}
