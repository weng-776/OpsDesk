package com.opsdesk.auth.interceptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.enums.Role;
import com.opsdesk.user.entity.Permission;
import com.opsdesk.user.entity.RolePermission;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.service.PermissionService;
import com.opsdesk.user.service.RolePermissionService;
import com.opsdesk.user.service.RoleService;
import com.opsdesk.user.service.UserRoleService;
import com.opsdesk.user.service.UserService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按 userId 加载角色与权限码（工单 D1-02）
 *
 * <p>链路：{@code user_role → role → role_permission → permission}，共 4 条**批量**查询，
 * 全部命中主键 / 最左前缀，无循环内查库、无 {@code SELECT *}。
 *
 * <p><b>本类只查库、不带缓存</b> —— 它是 {@link com.opsdesk.auth.service.PermissionLoader}
 * （D2-01）的<b>回源实现</b>：缓存命中时根本走不到这里；未命中或缓存是脏数据时，
 * 由 {@code PermissionLoader} 调 {@link #load} 回源，再回填 Redis
 * {@code auth:perms:{userId}}（规格基线 §23.2，TTL 10 分钟）。
 *
 * <p>D1-01 的 {@code AuthServiceImpl} 里原本有一份与这里同构的查询（D1-02 刻意没动它，
 * 以守住当时的改动边界）。D2-01 起，登录与鉴权拦截器<b>都改为调用 {@code PermissionLoader}</b>，
 * 那份重复已删除 —— 至此「用户 → 角色 → 权限码」的查询<b>全项目只剩这里一份</b>。
 */
@Component
public class UserAuthContextLoader {

    private final UserService userService;
    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final RolePermissionService rolePermissionService;
    private final PermissionService permissionService;

    public UserAuthContextLoader(UserService userService,
                                 UserRoleService userRoleService,
                                 RoleService roleService,
                                 RolePermissionService rolePermissionService,
                                 PermissionService permissionService) {
        this.userService = userService;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.rolePermissionService = rolePermissionService;
        this.permissionService = permissionService;
    }

    /**
     * 加载用户的角色码、权限码与所属部门。
     *
     * <p>无角色 / 无权限时返回空集合（不返回 null，调用方不必判空）。
     * 用户不存在时同样返回空集合 —— 「用户是否存在」不归本类判定，
     * 拦截器只关心「这个 userId 能拿到什么权限」。
     *
     * <p><b>共 5 条批量查询</b>：{@code user}（取 department_id）+ 权限链路的 4 条。
     * 之所以连部门一起查，是因为数据范围 §8.3 的 AGENT 条件 ③ 需要「我的部门」；
     * 把结果一并放进 {@code auth:perms:{userId}} 的缓存载荷后，
     * 请求期就<b>不必再单独查一次 user</b> 了（D2-04 优化）。
     */
    public UserAuthContext load(Long userId) {
        if (userId == null) {
            return UserAuthContext.empty();
        }
        Long departmentId = loadDepartmentId(userId);

        List<Long> roleIds = loadRoleIds(userId);
        if (roleIds.isEmpty()) {
            // 没角色也要把 departmentId 带出去，保持「载荷完整」这一条不变量
            return new UserAuthContext(Set.of(), Set.of(), departmentId);
        }
        Set<Role> roles = roleService.listByIds(roleIds).stream()
                .map(com.opsdesk.user.entity.Role::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new UserAuthContext(roles, loadPermissionCodes(roleIds), departmentId);
    }

    /**
     * 取用户所属部门 id（数据范围 §8.3 用）。
     *
     * <pre>SELECT department_id FROM user WHERE id = ? AND deleted = 0</pre>
     * <p>只 select 一列，避免把 BCrypt 密文也捞进内存。
     * <p>用 {@code getOne(wrapper)}（<b>不带</b> {@code false}）—— 按主键查最多一行，
     * 真出现多行宁可抛异常，也不要静默取第一行（known-traps #2：{@code getOne(w, false)} 是 fail-open）。
     *
     * @return 用户不存在 / 未设部门 → {@code null}
     */
    private Long loadDepartmentId(Long userId) {
        User user = userService.getOne(new LambdaQueryWrapper<User>()
                .select(User::getDepartmentId)
                .eq(User::getId, userId));
        return user == null ? null : user.getDepartmentId();
    }

    /**
     * 用户 → 角色 ID。
     *
     * <pre>SELECT role_id FROM user_role WHERE user_id = ?</pre>
     * <p>{@code user_role} 无 {@code deleted} 列；{@code WHERE user_id = ?} 命中
     * 主键 {@code (user_id, role_id)} 的最左前缀。
     */
    private List<Long> loadRoleIds(Long userId) {
        return userRoleService.list(new LambdaQueryWrapper<UserRole>()
                        .select(UserRole::getRoleId)
                        .eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 角色 ID 列表 → 权限码列表。
     *
     * <pre>
     * SELECT permission_id FROM role_permission WHERE role_id IN (?,…)
     * SELECT id,parent_id,name,code,type,sort,created_at FROM permission WHERE id IN (?,…)
     * </pre>
     * <p>前者命中主键 {@code (role_id, permission_id)} 的最左前缀；
     * 后者命中主键 {@code id}。按 {@code permission.sort} 再按 id 排序，
     * 与登录接口返回的顺序保持一致。
     */
    private Set<String> loadPermissionCodes(List<Long> roleIds) {
        List<Long> permissionIds = rolePermissionService.list(new LambdaQueryWrapper<RolePermission>()
                        .select(RolePermission::getPermissionId)
                        .in(RolePermission::getRoleId, roleIds))
                .stream()
                .map(RolePermission::getPermissionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return Set.of();
        }
        return permissionService.listByIds(permissionIds).stream()
                .filter(p -> StringUtils.hasText(p.getCode()))
                .sorted(Comparator
                        .comparing((Permission p) -> p.getSort() == null ? 0 : p.getSort())
                        .thenComparing(Permission::getId))
                .map(Permission::getCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 一次请求内要落进 {@code UserContext} 的身份要素。
     *
     * <p>名字里只写了 auth，但 {@code departmentId} 也在这里 —— 因为它是数据范围
     * §8.3 的输入，而数据范围和权限是同一份「每次请求都要拿到」的东西，
     * 一起查、一起缓存比拆成两次查询/两个 key 划算。
     *
     * @param roles        角色码集合
     * @param permissions  权限码集合
     * @param departmentId 所属部门 id，可为 {@code null}（未设部门）
     */
    public record UserAuthContext(Set<Role> roles, Set<String> permissions, Long departmentId) {

        public UserAuthContext {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        public static UserAuthContext empty() {
            return new UserAuthContext(Set.of(), Set.of(), null);
        }
    }
}
