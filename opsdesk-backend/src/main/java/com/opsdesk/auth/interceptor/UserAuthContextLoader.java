package com.opsdesk.auth.interceptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.enums.Role;
import com.opsdesk.user.entity.Permission;
import com.opsdesk.user.entity.RolePermission;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.service.PermissionService;
import com.opsdesk.user.service.RolePermissionService;
import com.opsdesk.user.service.RoleService;
import com.opsdesk.user.service.UserRoleService;
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

    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final RolePermissionService rolePermissionService;
    private final PermissionService permissionService;

    public UserAuthContextLoader(UserRoleService userRoleService,
                                 RoleService roleService,
                                 RolePermissionService rolePermissionService,
                                 PermissionService permissionService) {
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.rolePermissionService = rolePermissionService;
        this.permissionService = permissionService;
    }

    /**
     * 加载用户的角色码与权限码。
     *
     * <p>无角色 / 无权限时返回空集合（不返回 null，调用方不必判空）。
     * 用户不存在时同样返回空集合 —— 「用户是否存在」不归本类判定，
     * 拦截器只关心「这个 userId 能拿到什么权限」。
     */
    public UserAuthContext load(Long userId) {
        if (userId == null) {
            return UserAuthContext.empty();
        }
        List<Long> roleIds = loadRoleIds(userId);
        if (roleIds.isEmpty()) {
            return UserAuthContext.empty();
        }
        Set<Role> roles = roleService.listByIds(roleIds).stream()
                .map(com.opsdesk.user.entity.Role::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new UserAuthContext(roles, loadPermissionCodes(roleIds));
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
     * @param roles       角色码集合
     * @param permissions 权限码集合
     */
    public record UserAuthContext(Set<Role> roles, Set<String> permissions) {

        public UserAuthContext {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        public static UserAuthContext empty() {
            return new UserAuthContext(Set.of(), Set.of());
        }
    }
}
