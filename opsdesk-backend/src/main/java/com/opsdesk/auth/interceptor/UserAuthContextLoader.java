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
 * <p><b>本类只查库、不带缓存</b>，是 D1-02 的临时落点 ——
 * 规格基线 §23.2 要求「每次请求读 Redis {@code auth:perms:{userId}}（TTL 10 分钟），
 * 未命中回源查库」，那套缓存 + {@code evict(userId)} 是 <b>D2-01 的交付物</b>。
 * D2-01 落地后，本类应被 {@code PermissionLoader} 取代（拦截器与登录都改调它）。
 *
 * <p>之所以在 D1-02 里重复了一段与登录同构的查询，是为了守住本工单
 * 「其余文件一律不要动」的边界 —— 不回头改 D1-01 已验收的 {@code AuthServiceImpl}。
 * 这段重复的寿命只有一张工单。
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
