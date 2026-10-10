package com.opsdesk.sla;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.sla.dto.SlaPolicyUpdateDTO;
import com.opsdesk.sla.service.SlaPolicyManageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 策略「并发换版」验收测试（工单 D5-01 验收 3）
 *
 * <h2>为什么验收 3 要这样测</h2>
 * 验收 3 是「同一 priority 出现第二条 ACTIVE 时被业务层拒绝（不是靠 DB 唯一索引报错）」。
 * 但 {@code uk_sla_active_priority} 是**硬约束** —— 普通 SQL 根本造不出「两条 ACTIVE」
 * （{@code SlaPolicyManageServiceTest.两条ACTIVE无法被SQL造出} 已证明）。
 *
 * <p>所以「第二条 ACTIVE」唯一可能出现的场景就是 <b>并发换版</b>：
 * 两个请求同时读到同一条 ACTIVE、同时停旧、同时插新 —— 后插的那个会撞唯一索引。
 * 本类验证的是：<b>此时客户端拿到的是业务 40900，而不是数据库约束错误</b>。
 *
 * <h2>⚠️ 刻意不加 {@code @Transactional}</h2>
 * 同事务里测不出并发（两次 update 互相可见）。代价是要在 {@link #restore()} 里手工还原：
 * 删掉本次新增的 ACTIVE 行，再把旧行改回 ACTIVE。
 */
@SpringBootTest
class SlaPolicyVersionConcurrencyTest {

    private static final String PRIORITY = "P1";

    @Autowired
    private SlaPolicyManageService slaPolicyManageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 换版前那条 ACTIVE 策略的 id，用于还原 */
    private Long originalActiveId;

    @AfterEach
    void restore() {
        if (originalActiveId == null) {
            return;
        }
        // 顺序不能反：先删掉本次新增的 ACTIVE 行，再把旧行改回 ACTIVE ——
        // 反过来的话改回 ACTIVE 时会撞唯一索引
        int deleted = jdbcTemplate.update(
                "DELETE FROM sla_policy WHERE priority = ? AND status = 'ACTIVE' AND id <> ?",
                PRIORITY, originalActiveId);
        jdbcTemplate.update("UPDATE sla_policy SET status = 'ACTIVE' WHERE id = ?", originalActiveId);
        System.out.println(">>> 已还原 P1 策略：删除新增 ACTIVE 行 " + deleted
                + " 条，旧行 id=" + originalActiveId + " 改回 ACTIVE");
        originalActiveId = null;
    }

    @Test
    @DisplayName("验收3：两人同时换版同一策略 → 恰好一人成功，另一人拿到业务 40900（不是 DB 错误）")
    void 并发换版一人成功一人40900() throws Exception {
        originalActiveId = jdbcTemplate.queryForObject(
                "SELECT id FROM sla_policy WHERE priority = ? AND status = 'ACTIVE'",
                Long.class, PRIORITY);
        assertThat(originalActiveId).as("前置：P1 必须有一条 ACTIVE 策略").isNotNull();

        int[] minutes = {30, 45};
        CountDownLatch ready = new CountDownLatch(minutes.length);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(minutes.length);
        List<Future<ErrorCode>> futures = new ArrayList<>();

        try {
            for (int m : minutes) {
                futures.add(pool.submit(() -> {
                    SlaPolicyUpdateDTO dto = new SlaPolicyUpdateDTO();
                    dto.setResponseMinutes(m);
                    dto.setResolutionMinutes(m * 8);
                    try {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        slaPolicyManageService.update(originalActiveId, dto);
                        return null;                       // 成功
                    }
                    catch (BizException e) {
                        return e.getErrorCode();           // 预期 40900
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<ErrorCode> results = new ArrayList<>();
            for (Future<ErrorCode> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            long success = results.stream().filter(java.util.Objects::isNull).count();
            long conflict = results.stream().filter(ErrorCode.CONFLICT::equals).count();
            assertThat(success).as("恰好一人成功").isEqualTo(1);
            assertThat(conflict).as("恰好一人拿到业务 40900（而不是 DB 约束错误）").isEqualTo(1);
        }
        finally {
            pool.shutdownNow();
        }

        // 落库结果：该 priority 仍然只有一条 ACTIVE
        Integer actives = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sla_policy WHERE priority = ? AND status = 'ACTIVE'",
                Integer.class, PRIORITY);
        assertThat(actives).as("并发之后 ACTIVE 仍然唯一").isEqualTo(1);
    }
}
