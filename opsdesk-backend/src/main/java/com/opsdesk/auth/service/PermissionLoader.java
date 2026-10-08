package com.opsdesk.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.auth.interceptor.UserAuthContextLoader;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.utils.EnumUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限加载器：Redis 缓存 + 回源查库（工单 D2-01，SOP §5 红区）
 *
 * <p>规格依据：规格基线 §23.2（每次请求读 {@code auth:perms:{userId}}，TTL 10 分钟，
 * 未命中回源查库；<b>权限/角色变更时删除该 key</b> → 权限立即生效）、§23.4（Redis key 清单）。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li><b>本类</b>：缓存读写、脏数据容错、{@link #evict} 失效</li>
 *   <li><b>{@link UserAuthContextLoader}</b>：纯查库（4 条批量查询），被本类当作<b>回源</b>复用
 *       —— 不在这里重写第 3 份「用户 → 角色 → 权限」的查询</li>
 * </ul>
 *
 * <h2>为什么缓存不命中时要「回填」而不是只返回</h2>
 * §23.2 的模型是「读穿透」：第一个请求付查库的成本，之后 10 分钟内所有请求（含并发进来的）
 * 都命中缓存。登录接口也会调本类，等于登录顺手把缓存 warm 起来。
 *
 * <h2>Redis 不可用时降级，不 fail-closed</h2>
 * 缓存是<b>性能优化</b>，不是正确性依赖 —— §23.2 只说「未命中回源查库」。
 * 所以缓存读/写异常一律记 WARN 并回源查库，让业务继续跑。
 * <p>⚠️ 真正的安全闸是<b>登出黑名单</b> {@code auth:token:{jti}}（在 {@code AuthInterceptor} 里），
 * 那个必须 fail-closed —— Redis 挂掉时请求会被黑名单检查挡成 50000，这是有意的。
 * 两者取舍不同，别把这里改成 fail-closed。
 *
 * <h2>并发</h2>
 * 两个请求同时未命中 → 都会查库、都会写缓存。写的是同一份内容，幂等，无需加锁。
 * 加分布式锁反而会引入「锁超时 / 死锁」的新问题，收益为零。
 */
@Slf4j
@Component
public class PermissionLoader {

    /** 权限缓存 key 前缀（§23.4 权威定义：{@code auth:perms:{userId}}） */
    public static final String PERMS_CACHE_PREFIX = "auth:perms:";

    /** 缓存 TTL（§23.4 权威：10 分钟） */
    public static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /**
     * 缓存载荷格式版本。
     *
     * <p><b>改动 {@link CachedPermissions} 的字段时把它 +1</b>：读到版本不符的旧载荷
     * 会被当作「脏数据」丢弃并回源查库、随后覆盖成新格式 —— 也就是<b>自愈</b>，
     * 不需要任何运维动作（不用手动清 {@code auth:perms:*}）。
     *
     * <p>为什么要这么做：载荷加字段（如 D2-04 优化加的 {@code departmentId}）后，
     * 旧缓存反序列化出来新字段是 {@code null}，会被当成「这个用户没有部门」——
     * 症状是 <b>AGENT 暂时看不到本部门的工单</b>（§8.1 那种受理盲区），
     * 且不报错、只在 TTL（10 分钟）内悄悄错。
     * 版本号把这种「格式代沟」变成一次正常的缓存未命中。
     *
     * <p>v1：{@code {roles, permissions}}
     * <p>v2：{@code {v, roles, permissions, departmentId}}（D2-04 优化）
     */
    private static final int PAYLOAD_VERSION = 2;

    /** 日志里回显脏数据的最大长度，避免一条超长脏值把日志刷爆 */
    private static final int DIRTY_VALUE_MAX_LOG_LENGTH = 120;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserAuthContextLoader authContextLoader;

    public PermissionLoader(StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper,
                            UserAuthContextLoader authContextLoader) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.authContextLoader = authContextLoader;
    }

    // ==================== 对外 ====================

    /**
     * 取用户的角色与权限码。
     *
     * <p>命中缓存 → 0 条 SQL 直接返回；未命中 / 脏数据 → 回源查库并回填缓存。
     *
     * @param userId 用户 id；为 {@code null} 时返回空集合（不查库、不缓存）
     */
    public UserAuthContextLoader.UserAuthContext load(Long userId) {
        if (userId == null) {
            return UserAuthContextLoader.UserAuthContext.empty();
        }

        UserAuthContextLoader.UserAuthContext cached = readCache(userId);
        if (cached != null) {
            return cached;
        }

        UserAuthContextLoader.UserAuthContext loaded = authContextLoader.load(userId);
        writeCache(userId, loaded);
        return loaded;
    }

    /**
     * 清除用户的权限缓存 —— 权限 / 角色变更后调用，使变更<b>无需重新登录立即生效</b>（§23.2）。
     *
     * <p>调用点（§23.2 要求的三类变更）：
     * <ul>
     *   <li>用户角色变更 —— D1-03 的 {@code UserManageService#assignRoles} / {@code #update}
     *       已直接删同一个 key，功能等价；后续可改为调用本方法以统一 key 定义</li>
     *   <li>角色权限变更 —— D2-03 的 {@code role:assign-permission} 落地时调用</li>
     *   <li>角色本身变更 —— 同上</li>
     * </ul>
     */
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(key(userId));
            log.debug("[权限缓存] 已清除 userId={}", userId);
        }
        catch (Exception ex) {
            // 清缓存失败不阻断业务：最坏情况是旧权限多存活一个 TTL（≤10 分钟）
            log.warn("[权限缓存] 清除失败（不阻断业务，最坏多存活 10 分钟）。userId={}", userId, ex);
        }
    }

    // ==================== 缓存读写 ====================

    /**
     * 读缓存。
     *
     * @return 解析成功返回结果；<b>未命中 / Redis 异常 / 脏数据一律返回 {@code null}</b>，
     *         由调用方回源查库 —— 这样「需要回源」只有一条出口，不会漏
     */
    private UserAuthContextLoader.UserAuthContext readCache(Long userId) {
        String key = key(userId);
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        }
        catch (Exception ex) {
            log.warn("[权限缓存] 读取失败，降级回源查库。userId={}", userId, ex);
            return null;
        }

        if (!StringUtils.hasText(json)) {
            return null;
        }

        try {
            CachedPermissions parsed = objectMapper.readValue(json, CachedPermissions.class);
            if (parsed.v() != PAYLOAD_VERSION) {
                // 旧格式载荷（例如加 departmentId 之前写进去的）—— 不是脏数据，是「代沟」。
                // 回源 + 覆盖成新格式，下一个请求就恢复正常
                log.info("[权限缓存] 载荷版本 {} 与当前 {} 不符，回源并覆盖。userId={}",
                        parsed.v(), PAYLOAD_VERSION, userId);
                return null;
            }
            return toAuthContext(parsed);
        }
        catch (Exception ex) {
            // 脏数据（非法 JSON / 结构对不上）→ 丢弃并回源，不抛异常（工单要点）
            log.warn("[权限缓存] 脏数据，丢弃并回源查库。userId={} value={}",
                    userId, abbreviate(json), ex);
            return null;
        }
    }

    /** 回填缓存；写失败只记 WARN，不影响本次返回 */
    private void writeCache(Long userId, UserAuthContextLoader.UserAuthContext context) {
        CachedPermissions payload = new CachedPermissions(
                PAYLOAD_VERSION,
                context.roles().stream().sorted().map(Role::name).toList(),
                List.copyOf(context.permissions()),
                context.departmentId());
        try {
            redisTemplate.opsForValue().set(key(userId),
                    objectMapper.writeValueAsString(payload), CACHE_TTL);
        }
        catch (Exception ex) {
            log.warn("[权限缓存] 写入失败（不影响本次返回）。userId={}", userId, ex);
        }
    }

    /**
     * 缓存载荷 → 领域对象。
     *
     * <p>容错三件事：{@code roles} / {@code permissions} 为 null 时归一成空集合；
     * 未知角色码（枚举里已删除的历史值）直接跳过而不是抛异常；空白权限码丢弃。
     * <p>{@code departmentId} 原样透传（{@code null} = 该用户未设部门，是合法值，
     * 不能当成「缺字段」—— 那是靠 {@code v} 版本号区分的）。
     */
    private UserAuthContextLoader.UserAuthContext toAuthContext(CachedPermissions payload) {
        Set<Role> roles = new LinkedHashSet<>();
        if (payload.roles() != null) {
            for (String code : payload.roles()) {
                EnumUtils.fromCode(Role.class, code).ifPresent(roles::add);
            }
        }
        Set<String> permissions = new LinkedHashSet<>();
        if (payload.permissions() != null) {
            for (String code : payload.permissions()) {
                if (StringUtils.hasText(code)) {
                    permissions.add(code);
                }
            }
        }
        return new UserAuthContextLoader.UserAuthContext(roles, permissions, payload.departmentId());
    }

    private String key(Long userId) {
        return PERMS_CACHE_PREFIX + userId;
    }

    private static String abbreviate(String raw) {
        return raw.length() <= DIRTY_VALUE_MAX_LOG_LENGTH
                ? raw : raw.substring(0, DIRTY_VALUE_MAX_LOG_LENGTH) + "...";
    }

    /**
     * 缓存载荷（§23.2：值用 JSON 存，<b>角色码列表 + 权限码列表</b>）。
     *
     * <p>刻意用字符串码而不是 {@code Role} 枚举：缓存是跨版本的持久数据，
     * 存 code 才不会因为枚举重命名 / 重排序而读不出来。
     *
     * <p>{@code departmentId} 是 D2-04 优化加进来的：数据范围 §8.3 的 AGENT 条件 ③
     * 需要「我的部门」，顺手跟权限一起缓存，请求期就不必再单独查一次 {@code user} 表。
     * <p>⚠️ 字段变化必须同步 +1 {@link #PAYLOAD_VERSION}。
     *
     * @param v            载荷格式版本（见 {@link #PAYLOAD_VERSION}）
     * @param roles        角色码列表
     * @param permissions  权限码列表
     * @param departmentId 所属部门 id，可为 {@code null}
     */
    record CachedPermissions(int v, List<String> roles, List<String> permissions, Long departmentId) {
    }
}
