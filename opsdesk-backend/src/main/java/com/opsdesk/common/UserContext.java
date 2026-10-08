package com.opsdesk.common;

import com.opsdesk.common.enums.Role;

import java.util.Collections;
import java.util.Set;

/**
 * 当前登录用户上下文（规格基线 §23.2）
 *
 * <p>由鉴权拦截器在请求进入时 {@link #set}，请求结束时 {@link #clear}。
 * 业务代码直接静态调用即可，不用一层层传 userId。
 *
 * <pre>{@code
 * Long userId = UserContext.getUserId();          // 可能为 null（未登录）
 * Long userId = UserContext.requireUserId();      // 未登录直接抛 40100
 * if (UserContext.isAdmin()) { ... }
 * }</pre>
 *
 * <p><b>来源说明</b>：{@code userId} / {@code jti} 来自 JWT；
 * {@code roles} / {@code permissions} 由 auth 模块按 §23.2 从 Redis
 * {@code auth:perms:{userId}}（TTL 10 分钟，未命中回源查库）加载后放进来。
 * 所以管理员改了角色，用户**不需要重新登录**，下次请求就生效。
 *
 * <p>⚠️ 本类只是<b>数据载体</b>，不做任何鉴权决策 ——
 * 「拒绝还是放行」由 auth 模块的注解 + 拦截器负责（SOP §5 红区）。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    // ==================== 生命周期 ====================

    /** 绑定当前请求的登录用户（鉴权拦截器调用） */
    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /**
     * 解绑。⚠️ 必须在请求结束的 finally 里调用 ——
     * Tomcat 线程是复用的，不清会把上一个请求的身份带给下一个请求。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /** 取完整上下文；未登录返回 null */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    // ==================== 常用读取 ====================

    /** 是否已登录 */
    public static boolean isLogin() {
        return HOLDER.get() != null;
    }

    /** 当前用户 id；未登录返回 null */
    public static Long getUserId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    /**
     * 当前用户 id；未登录抛 {@link ErrorCode#UNAUTHORIZED}（40100）。
     * 业务代码优先用这个 —— 比在几十个地方判空干净。
     */
    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    /** 当前 token 的 jti；未登录返回 null。登出时用它写 Redis 黑名单 */
    public static String getJti() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.jti();
    }

    /** 角色集合；未登录返回空集合（不返回 null，调用方不必判空） */
    public static Set<Role> getRoles() {
        CurrentUser user = HOLDER.get();
        return user == null ? Collections.emptySet() : user.roles();
    }

    /** 权限码集合；未登录返回空集合 */
    public static Set<String> getPermissions() {
        CurrentUser user = HOLDER.get();
        return user == null ? Collections.emptySet() : user.permissions();
    }

    // ==================== 便捷判定 ====================

    /** 是否拥有某个角色 */
    public static boolean hasRole(Role role) {
        return role != null && getRoles().contains(role);
    }

    /** 是否拥有某个权限码（如 {@code ticket:list}，见 §3.6） */
    public static boolean hasPermission(String permissionCode) {
        return permissionCode != null && getPermissions().contains(permissionCode);
    }

    /** 是否管理员 */
    public static boolean isAdmin() {
        return hasRole(Role.ADMIN);
    }

    /**
     * 当前登录用户。
     *
     * @param userId      用户 id（来自 JWT 的 {@code sub}）
     * @param jti         token 唯一标识（来自 JWT 的 {@code jti}）
     * @param roles       角色集合，允许 null（会被归一成空集合）
     * @param permissions 权限码集合，允许 null（会被归一成空集合）
     */
    public record CurrentUser(Long userId, String jti, Set<Role> roles, Set<String> permissions) {

        public CurrentUser {
            roles = roles == null ? Collections.emptySet() : Set.copyOf(roles);
            permissions = permissions == null ? Collections.emptySet() : Set.copyOf(permissions);
        }
    }
}
