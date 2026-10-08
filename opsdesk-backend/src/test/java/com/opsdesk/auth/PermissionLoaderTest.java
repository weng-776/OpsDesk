package com.opsdesk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.auth.interceptor.UserAuthContextLoader;
import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 权限缓存验收测试（工单 D2-01）
 *
 * <p>规格依据：规格基线 §23.2（读 {@code auth:perms:{userId}}，TTL 10 分钟，未命中回源；
 * 权限/角色变更删 key → 立即生效）、§23.4（key 清单）、§25.3 用例 #12。
 *
 * <h2>怎么证明「真的读了缓存」</h2>
 * 单测里数不清 SQL，但可以用<b>绕过 service 直接改库</b>的办法：
 * {@code user_role} 是「用户 → 角色」的唯一真相来源，直接改它<b>不会</b>触发 evict。
 * 此时若 {@code load} 返回的仍是<b>旧值</b>，就证明它读的是缓存而不是库 ——
 * 比「断言第二次比第一次快」可靠得多。
 *
 * <h2>⚠️ 本类刻意<b>不加</b> {@code @Transactional}</h2>
 * 加上的话整类共用一个事务 ⇒ 共用一个 {@code SqlSession} ⇒ 命中 <b>MyBatis 一级缓存</b>。
 * 那时用 {@code JdbcTemplate} 绕过 MyBatis 改 {@code user_role}，MyBatis 的本地缓存
 * 仍然返回第一次查到的旧值，测试会<b>假红</b>（第一次实测就是这么挂的）。
 * <p>不加事务后，每次 mapper 调用各自开一个 {@code SqlSession} 并关闭，没有跨调用的本地缓存。
 * <p>生产环境不受这个问题影响：每个 HTTP 请求各自一个事务，
 * {@code evict} 与下一次读取必然落在不同 {@code SqlSession} 上。
 *
 * <p>代价是要自己清理：探针用户 + {@code user_role} 物理删除，Redis key 显式删
 * （Redis 不参与事务）。
 */
@SpringBootTest
class PermissionLoaderTest {

    /** 探针用户所属部门：财务部（种子 id=5） */
    private static final long PROBE_DEPARTMENT_ID = 5L;

    /** 种子角色 AGENT 的 id —— 探针用户初始角色，权限 20 条 */
    private static final long ROLE_AGENT_ID = 2L;

    /** 种子角色 ADMIN 的 id —— 改成它来观察权限集合变化，权限 48 条 */
    private static final long ROLE_ADMIN_ID = 3L;

    @Autowired
    private PermissionLoader permissionLoader;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 每次用例新建一个探针用户，避免动种子数据 */
    private Long userId;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        String username = "probe_perms_" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO `user` (username, password, nickname, department_id, status, deleted) "
                + "VALUES (?, ?, ?, ?, 1, 0)", username, "not-a-real-hash", "权限缓存探针", PROBE_DEPARTMENT_ID);
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Long.class, username);

        jdbcTemplate.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userId, ROLE_AGENT_ID);

        cacheKey = PermissionLoader.PERMS_CACHE_PREFIX + userId;
        redisTemplate.delete(cacheKey);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(cacheKey);
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);
    }

    // ==================== 验收 1 / 3：回源 + 回填 + TTL ====================

    @Test
    @DisplayName("未命中缓存 → 回源查库 + 回填，TTL 为 10 分钟（§23.4）")
    void 未命中时回源查库并回填缓存() {
        assertThat(redisTemplate.hasKey(cacheKey)).as("前置：缓存为空").isFalse();

        UserAuthContextLoader.UserAuthContext context = permissionLoader.load(userId);

        assertThat(context.roles()).containsExactly(Role.AGENT);
        assertThat(context.permissions()).contains("ticket:list");

        assertThat(redisTemplate.hasKey(cacheKey)).as("已回填缓存").isTrue();
        Long ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull();
        assertThat((long) ttl).as("TTL 应在 10 分钟附近").isBetween(570L, 600L);

        String json = redisTemplate.opsForValue().get(cacheKey);
        assertThat(json).as("值是 JSON，且存的是角色码 + 权限码")
                .contains("\"roles\"").contains("\"permissions\"")
                .contains("AGENT").contains("ticket:list");
    }

    @Test
    @DisplayName("第二次 load 命中缓存：绕过 service 直接改库后，返回的仍是缓存里的旧值")
    void 第二次load走缓存而不是查库() {
        assertThat(permissionLoader.load(userId).roles()).containsExactly(Role.AGENT);

        // 绕过 service 直接改库 → 不触发 evict，缓存应当"看不见"这次改动
        replaceUserRole(ROLE_ADMIN_ID);

        assertThat(permissionLoader.load(userId).roles())
                .as("命中缓存 → 仍是旧值 AGENT；若这里变成 ADMIN，说明根本没读缓存")
                .containsExactly(Role.AGENT);
    }

    // ==================== 验收 2：evict → 立即生效（§25.3 #12） ====================

    @Test
    @DisplayName("evict 后无需重新登录即可读到新权限（§23.2 / §25.3 用例 #12）")
    void evict后立即读到新权限() {
        // 1) 先把缓存 warm 成 AGENT
        assertThat(permissionLoader.load(userId).roles()).containsExactly(Role.AGENT);

        // 2) 绕过 service 改角色（不会自动 evict）→ 此时缓存与库已经不一致
        replaceUserRole(ROLE_ADMIN_ID);
        assertThat(permissionLoader.load(userId).roles())
                .as("未 evict 时仍是旧的 AGENT").containsExactly(Role.AGENT);

        // 3) evict → 下一次 load 必须拿到新角色
        permissionLoader.evict(userId);

        UserAuthContextLoader.UserAuthContext after = permissionLoader.load(userId);
        assertThat(after.roles()).as("evict 后回源 → 新角色 ADMIN").containsExactly(Role.ADMIN);
        assertThat(after.permissions())
                .as("ADMIN 的权限数（48）应明显多于 AGENT（20）")
                .hasSizeGreaterThan(20);
        assertThat(redisTemplate.hasKey(cacheKey)).as("evict 后重新回填").isTrue();
    }

    // ==================== 脏数据容错 ====================

    @Test
    @DisplayName("缓存是非法 JSON → 不抛异常，回源查库并把缓存覆盖成合法值")
    void 非法JSON不抛异常且被覆盖() {
        redisTemplate.opsForValue().set(cacheKey, "这不是 JSON {{{", Duration.ofMinutes(10));

        UserAuthContextLoader.UserAuthContext context = permissionLoader.load(userId);

        assertThat(context.roles()).containsExactly(Role.AGENT);
        String overwritten = redisTemplate.opsForValue().get(cacheKey);
        assertThatCode(() -> objectMapper.readValue(overwritten, Object.class))
                .as("覆盖后的值确实是合法 JSON").doesNotThrowAnyException();
        assertThat(overwritten).contains("AGENT");
    }

    @Test
    @DisplayName("缓存里有未知角色码 → 跳过而不是抛异常")
    void 未知角色码被跳过() {
        redisTemplate.opsForValue().set(cacheKey,
                "{\"v\":2,\"roles\":[\"NOT_A_ROLE\",\"AGENT\"],\"permissions\":[\"ticket:list\"]}",
                Duration.ofMinutes(10));

        UserAuthContextLoader.UserAuthContext context = permissionLoader.load(userId);

        assertThat(context.roles()).as("未知码跳过，合法的保留").containsExactly(Role.AGENT);
        assertThat(context.permissions()).containsExactly("ticket:list");
    }

    @Test
    @DisplayName("roles / permissions 为 null → 归一成空集合，不返回 null")
    void 字段为null时归一成空集合() {
        redisTemplate.opsForValue().set(cacheKey,
                "{\"v\":2,\"roles\":null,\"permissions\":null}", Duration.ofMinutes(10));

        UserAuthContextLoader.UserAuthContext context = permissionLoader.load(userId);

        assertThat(context.roles()).isNotNull().isEmpty();
        assertThat(context.permissions()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("JSON 结构对不上（roles 是对象不是数组）→ 同样回源，不抛异常")
    void 结构对不上时回源() {
        redisTemplate.opsForValue().set(cacheKey,
                "{\"roles\":{\"unexpected\":\"object\"},\"permissions\":[]}",
                Duration.ofMinutes(10));

        assertThatCode(() -> permissionLoader.load(userId)).doesNotThrowAnyException();
        assertThat(permissionLoader.load(userId).roles()).containsExactly(Role.AGENT);
    }

    // ==================== 载荷里的 departmentId 与版本号（D2-04 优化） ====================

    @Test
    @DisplayName("载荷带 departmentId：回源时带上、缓存命中时读得出来（请求期不必再查 user 表）")
    void 载荷带部门ID并能往返() {
        Long departmentId = departmentIdOfProbeUser();
        assertThat(departmentId).as("前置：探针用户有部门").isNotNull();

        UserAuthContextLoader.UserAuthContext first = permissionLoader.load(userId);
        assertThat(first.departmentId()).as("回源时带上部门").isEqualTo(departmentId);

        String json = redisTemplate.opsForValue().get(cacheKey);
        assertThat(json).as("载荷 JSON 里带了版本号与部门")
                .contains("\"v\":2").contains("\"departmentId\":" + departmentId);

        assertThat(permissionLoader.load(userId).departmentId())
                .as("第二次命中缓存 → 部门照样读得出来").isEqualTo(departmentId);
    }

    @Test
    @DisplayName("旧版本载荷（v1，无 v / departmentId）→ 当作代沟回源并覆盖，绝不静默当成「没部门」")
    void 旧版本载荷自愈() {
        // 模拟 D2-04 优化之前写进去的 v1 载荷：只有 roles + permissions
        redisTemplate.opsForValue().set(cacheKey,
                "{\"roles\":[\"AGENT\"],\"permissions\":[\"ticket:list\"]}", Duration.ofMinutes(10));

        Long departmentId = departmentIdOfProbeUser();
        UserAuthContextLoader.UserAuthContext context = permissionLoader.load(userId);

        assertThat(context.departmentId())
                .as("旧载荷没有 departmentId，必须回源拿到真实值 —— 若为 null，"
                        + "AGENT 会看不到本部门工单（§8.1 的受理盲区）")
                .isEqualTo(departmentId);
        assertThat(context.permissions())
                .as("确实是回源了，而不是沿用了旧载荷里的那 1 条权限")
                .hasSizeGreaterThan(1);

        assertThat(redisTemplate.opsForValue().get(cacheKey))
                .as("已被覆盖成新格式，下一个请求恢复正常").contains("\"v\":2")
                .contains("\"departmentId\":" + departmentId);
    }

    private Long departmentIdOfProbeUser() {
        return jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("evict 幂等；userId 为 null 时不查库、不写缓存")
    void evict幂等且null安全() {
        assertThatCode(() -> {
            permissionLoader.evict(userId);
            permissionLoader.evict(userId);
            permissionLoader.evict(null);
        }).doesNotThrowAnyException();

        UserAuthContextLoader.UserAuthContext context = permissionLoader.load(null);
        assertThat(context.roles()).isEmpty();
        assertThat(context.permissions()).isEmpty();
        assertThat(redisTemplate.hasKey(PermissionLoader.PERMS_CACHE_PREFIX + "null"))
                .as("null 不落缓存").isFalse();
    }

    /** 绕过 service 直接改 user_role，模拟「改角色但没 evict」的不一致状态 */
    private void replaceUserRole(long roleId) {
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
        jdbcTemplate.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }
}
