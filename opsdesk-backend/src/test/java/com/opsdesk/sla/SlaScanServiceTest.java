package com.opsdesk.sla;

import com.opsdesk.sla.service.SlaScanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 定时扫描验收测试（工单 D5-03，规格基线 §9.5 / §23.3 / §23.4）
 *
 * <h2>⚠️ 本类刻意不加 {@code @Transactional}</h2>
 * {@link SlaScanService#scan()} 走<b>自己的连接</b>（每条回写自动提交）。
 * 如果测试是 {@code @Transactional}，用 {@code jdbcTemplate} 造的场景数据是<b>未提交</b>的 ——
 * 扫描根本看不见它们，测试会变成「扫了个寂寞」。
 * 所以照 {@code TicketFlowConcurrencyTest} 的老办法：<b>非事务 + 快照还原</b>。
 *
 * <h2>为什么 {@code @BeforeEach} 要把候选工单全部归一化</h2>
 * 扫描是「扫全库候选集」，所以 {@code ScanResult} 的计数会被<b>别的工单</b>影响。
 * 先把所有未关闭且非暂停态的工单统一改成「deadline 远在未来 + NORMAL + 标志 0」，
 * 计数才确定（否则库里残留的工单会让断言随机红）。
 *
 * <h2>为什么测试不依赖 {@code @Scheduled}</h2>
 * 验收要的是「<b>扫描方法</b>」的行为，不是「调度器会不会触发」。
 * 所以直接调 {@code scan()}，并在 {@code src/test/resources/application.properties}
 * 里把 {@code opsdesk.sla.scan.enabled} 关掉（否则后台线程会改种子数据、打红既有断言）。
 */
@SpringBootTest
class SlaScanServiceTest {

    /** §23.4 固定的锁 key */
    private static final String LOCK_KEY = "lock:sla:scan";

    private static final String SLA_COLUMNS =
            "status, response_deadline, resolution_deadline, first_response_at, "
                    + "sla_response_state, sla_resolution_state, "
                    + "sla_warning_notified, sla_breach_notified";

    /** 会被扫描的候选集：未关闭（非终态）。暂停态也在其中，由 service 跳过 */
    private static final String CANDIDATE_PREDICATE =
            "deleted = 0 AND status NOT IN ('CLOSED','CANCELLED')";

    @Autowired
    private SlaScanService slaScanService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 被本类改过的工单快照：{@code id -> 原始列值}，{@code @AfterEach} 原样还原 */
    private final Map<Long, Map<String, Object>> before = new LinkedHashMap<>();

    @BeforeEach
    void snapshotAndNormalize() {
        // 上一轮若异常退出，锁可能还留着 —— 清掉，否则本轮全被跳过
        redisTemplate.delete(LOCK_KEY);
        before.clear();

        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ticket WHERE " + CANDIDATE_PREDICATE, Long.class);
        for (Long id : ids) {
            before.put(id, jdbcTemplate.queryForMap(
                    "SELECT " + SLA_COLUMNS + " FROM ticket WHERE id = ?", id));
        }

        // 归一化候选集 → 计数确定（暂停态的工单不参与，本语句也不含它们）
        LocalDateTime far = LocalDateTime.now().plusDays(30);
        jdbcTemplate.update(
                "UPDATE ticket SET response_deadline = ?, resolution_deadline = ?, "
                        + "sla_response_state = 'NORMAL', sla_resolution_state = 'NORMAL', "
                        + "sla_warning_notified = 0, sla_breach_notified = 0 "
                        + "WHERE " + CANDIDATE_PREDICATE
                        + " AND status NOT IN ('WAITING_USER','WAITING_CONFIRM')",
                Timestamp.valueOf(far), Timestamp.valueOf(far));
    }

    @AfterEach
    void restore() {
        for (Map.Entry<Long, Map<String, Object>> entry : before.entrySet()) {
            restoreRow(entry.getKey(), entry.getValue());
        }
        before.clear();
        redisTemplate.delete(LOCK_KEY);
    }

    // ==================== 验收 1：WARNING 通知且幂等 ====================

    @Test
    @DisplayName("验收1：即将超时 → sla_warning_notified=1 且只通知一次；再扫一次不重复")
    void 预警通知只发一次() {
        long ticketId = 1L;
        makeResolutionWarningSoon(ticketId);

        SlaScanService.ScanResult first = slaScanService.scan();

        assertThat(first.lockAcquired()).as("锁空闲时应该抢到").isTrue();
        assertThat(first.warningNotified()).as("第一轮：新触发 1 条 SLA_WARNING").isEqualTo(1);
        assertThat(warningFlag(ticketId)).as("§9.5：到达 WARNING 置 sla_warning_notified = 1")
                .isEqualTo(1);
        assertThat(resolutionState(ticketId)).isEqualTo("WARNING");

        // ⭐ 第二轮：锁已释放，所以这一轮是真跑的 —— 挡住的只能是幂等标志位
        SlaScanService.ScanResult second = slaScanService.scan();

        assertThat(second.lockAcquired()).as("锁已在 finally 里释放，第二轮必须能跑").isTrue();
        assertThat(second.warningNotified())
                .as("第二轮：标志位挡住，不重复发通知").isZero();
        assertThat(warningFlag(ticketId)).as("标志位保持 1（扫描绝不清零）").isEqualTo(1);
        assertThat(resolutionState(ticketId)).as("状态没变 → 第二轮不写库").isEqualTo("WARNING");
    }

    // ==================== 验收 2：BREACHED 通知与状态落库 ====================

    @Test
    @DisplayName("验收2：已超时 → sla_breach_notified=1、sla_resolution_state=BREACHED")
    void 超时通知与状态落库() {
        long ticketId = 1L;
        jdbcTemplate.update("UPDATE ticket SET resolution_deadline = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), ticketId);

        SlaScanService.ScanResult first = slaScanService.scan();

        assertThat(first.breachNotified()).as("第一轮：新触发 1 条 SLA_BREACHED").isEqualTo(1);
        assertThat(breachFlag(ticketId)).isEqualTo(1);
        assertThat(resolutionState(ticketId)).isEqualTo("BREACHED");
        assertThat(warningFlag(ticketId))
                .as("按 §9.5 字面：BREACHED 只置 breach 标志，不补置 warning 标志").isZero();

        assertThat(slaScanService.scan().breachNotified())
                .as("第二轮不重复发").isZero();
    }

    // ==================== 验收 3：分布式锁 ====================

    @Test
    @DisplayName("验收3：锁已被占用 → 扫描直接跳过，且【一行都不写】")
    void 锁被占用时跳过且不写库() {
        long ticketId = 1L;
        makeResolutionWarningSoon(ticketId);   // 若真跑了，这条必然被置标志

        // 模拟「另一个实例正在扫描」：主线程先占住锁
        redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "held-by-another-instance",
                Duration.ofMinutes(4));

        SlaScanService.ScanResult result = slaScanService.scan();

        assertThat(result.lockAcquired()).isFalse();
        assertThat(result).as("没抢到锁 → 统计全 0").isEqualTo(SlaScanService.ScanResult.lockBusy());
        assertThat(warningFlag(ticketId)).as("跳过时不产生任何写入").isZero();
        assertThat(resolutionState(ticketId)).as("状态也不该被改").isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("验收3补充：两个实例同时抢锁 → 恰好一个执行")
    void 两实例同时抢锁只有一个执行() throws Exception {
        int instances = 2;
        CyclicBarrier barrier = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < instances; i++) {
                futures.add(pool.submit(() -> {
                    // 两个线程对齐起跑，最大化重叠（先到的会持有锁直到本轮扫描结束）
                    barrier.await(10, TimeUnit.SECONDS);
                    return slaScanService.scan().lockAcquired();
                }));
            }

            long acquired = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(30, TimeUnit.SECONDS))) {
                    acquired++;
                }
            }

            assertThat(acquired).as("§23.4：多实例同时扫描时只有一个能执行").isEqualTo(1);
        }
        finally {
            pool.shutdownNow();
        }
    }

    // ==================== 扫描范围 ====================

    @Test
    @DisplayName("扫描范围：暂停态（WAITING_USER / WAITING_CONFIRM）与终态一律不参与")
    void 暂停态与终态不参与扫描() {
        // 种子 3 = WAITING_CONFIRM、5 = WAITING_USER；4 = CLOSED
        // 全部设成「已超时」—— 只要被扫到就必然置超时标志
        jdbcTemplate.update("UPDATE ticket SET resolution_deadline = ? WHERE id IN (3, 4, 5)",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));

        SlaScanService.ScanResult result = slaScanService.scan();

        assertThat(result.pausedSkipped())
                .as("§9.4：暂停态不参与判定（否则挂起工单会被误判超时并误发通知）")
                .isGreaterThanOrEqualTo(2);
        assertThat(breachFlag(3L)).as("WAITING_CONFIRM 被跳过").isZero();
        assertThat(breachFlag(5L)).as("WAITING_USER 被跳过").isZero();
        assertThat(breachFlag(4L)).as("CLOSED 不在候选集（§9.5「未关闭工单」）").isZero();
        assertThat(resolutionState(4L)).as("终态状态列也不该被改").isEqualTo("NORMAL");
    }

    // ==================== 工具 ====================

    /**
     * 把工单的解决时限设成「约剩 10%」→ 必然落进 WARNING 带（20% 以内）。
     *
     * <p>用策略里的 {@code resolution_minutes} 算，而不是写死 48 分钟 ——
     * 这样即使 SLA 策略被换过版，用例也不会假红。
     */
    private void makeResolutionWarningSoon(long ticketId) {
        Integer minutes = jdbcTemplate.queryForObject(
                "SELECT p.resolution_minutes FROM ticket t "
                        + "JOIN sla_policy p ON p.id = t.sla_policy_id WHERE t.id = ?",
                Integer.class, ticketId);
        assertThat(minutes).as("工单 %s 必须绑定了 SLA 策略（种子数据）", ticketId).isNotNull();

        LocalDateTime deadline = LocalDateTime.now().plusMinutes(Math.max(1, minutes / 10));
        jdbcTemplate.update("UPDATE ticket SET resolution_deadline = ? WHERE id = ?",
                Timestamp.valueOf(deadline), ticketId);
    }

    private int warningFlag(long ticketId) {
        return flag(ticketId, "sla_warning_notified");
    }

    private int breachFlag(long ticketId) {
        return flag(ticketId, "sla_breach_notified");
    }

    private int flag(long ticketId, String column) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM ticket WHERE id = ?", Integer.class, ticketId);
        return value == null ? 0 : value;
    }

    private String resolutionState(long ticketId) {
        return jdbcTemplate.queryForObject(
                "SELECT sla_resolution_state FROM ticket WHERE id = ?", String.class, ticketId);
    }

    /** 按快照原样还原一行（显式 setNull，避免驱动对 null 参数的类型推断问题） */
    private void restoreRow(long id, Map<String, Object> row) {
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ticket SET status = ?, response_deadline = ?, resolution_deadline = ?, "
                            + "first_response_at = ?, sla_response_state = ?, sla_resolution_state = ?, "
                            + "sla_warning_notified = ?, sla_breach_notified = ? WHERE id = ?");
            ps.setObject(1, row.get("status"));
            setTimestamp(ps, 2, row.get("response_deadline"));
            setTimestamp(ps, 3, row.get("resolution_deadline"));
            setTimestamp(ps, 4, row.get("first_response_at"));
            ps.setObject(5, row.get("sla_response_state"));
            ps.setObject(6, row.get("sla_resolution_state"));
            ps.setObject(7, row.get("sla_warning_notified"));
            ps.setObject(8, row.get("sla_breach_notified"));
            ps.setObject(9, id);
            return ps;
        });
    }

    private static void setTimestamp(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        }
        else {
            ps.setObject(index, value, Types.TIMESTAMP);
        }
    }
}
