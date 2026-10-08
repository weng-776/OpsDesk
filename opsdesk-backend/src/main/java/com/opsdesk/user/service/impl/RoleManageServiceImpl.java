package com.opsdesk.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.user.dto.RolePermissionDTO;
import com.opsdesk.user.entity.Permission;
import com.opsdesk.user.entity.Role;
import com.opsdesk.user.entity.RolePermission;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.service.PermissionService;
import com.opsdesk.user.service.RoleManageService;
import com.opsdesk.user.service.RolePermissionService;
import com.opsdesk.user.service.RoleService;
import com.opsdesk.user.service.UserRoleService;
import com.opsdesk.user.vo.PermissionNodeVO;
import com.opsdesk.user.vo.RoleVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 角色与权限管理业务实现（工单 D2-03）
 *
 * <p>规格依据：API 文档 §7.1–§7.3、规格基线 §3.6 / §23.2 / §23.4。
 *
 * <h2>本类的核心：权限变更后要「批量」失效缓存</h2>
 * 改的是<b>角色</b>，受影响的是<b>拥有该角色的所有用户</b>。
 * 所以不能只 evict 当前操作者，必须先查出该角色下的全部 {@code user_id}，再逐个 evict ——
 * 否则那些用户会继续拿着旧权限跑到 TTL 到期（最多 10 分钟），
 * §7.3 的「变更后需批量清除该角色下所有用户的权限缓存」就落空了。
 *
 * <h2>两个查询都要「批量」，不能进循环</h2>
 * <ul>
 *   <li>角色列表的权限：一次 {@code role_permission WHERE role_id IN (…)} 再内存分组</li>
 *   <li>受影响用户：一次 {@code user_role WHERE role_id = ?}</li>
 * </ul>
 *
 * <p>⚠️ §7.3 还要求「写审计 {@code ROLE_PERMISSION_CHANGE}」—— {@code @AuditLog} 注解与
 * AOP 切面是 <b>D6-01</b> 的交付物，本单做不了，留给 D6-04 的埋点补全
 * （与 D1-03 的处理一致）。
 */
@Slf4j
@Service
public class RoleManageServiceImpl implements RoleManageService {

    private final RoleService roleService;
    private final PermissionService permissionService;
    private final RolePermissionService rolePermissionService;
    private final UserRoleService userRoleService;

    /** 权限变更后失效缓存用（§7.3 / §23.2） */
    private final PermissionLoader permissionLoader;

    public RoleManageServiceImpl(RoleService roleService,
                                 PermissionService permissionService,
                                 RolePermissionService rolePermissionService,
                                 UserRoleService userRoleService,
                                 PermissionLoader permissionLoader) {
        this.roleService = roleService;
        this.permissionService = permissionService;
        this.rolePermissionService = rolePermissionService;
        this.userRoleService = userRoleService;
        this.permissionLoader = permissionLoader;
    }

    // ==================== §7.1 角色列表 ====================

    @Override
    public List<RoleVO> listRoles() {
        List<Role> roles = roleService.list(new LambdaQueryWrapper<Role>().orderByAsc(Role::getId));
        if (roles.isEmpty()) {
            return List.of();
        }

        // 一次批量查全部角色的权限关联，再按 roleId 分组 —— 与角色数无关的 1 条查询
        Map<Long, List<Long>> permissionIdsByRole = rolePermissionService.list(
                        new LambdaQueryWrapper<RolePermission>()
                                .select(RolePermission::getRoleId, RolePermission::getPermissionId)
                                .in(RolePermission::getRoleId, roles.stream().map(Role::getId).toList()))
                .stream()
                .collect(Collectors.groupingBy(RolePermission::getRoleId,
                        Collectors.mapping(RolePermission::getPermissionId, Collectors.toList())));

        return roles.stream().map(role -> {
            RoleVO vo = toRoleVO(role);
            vo.setPermissionIds(permissionIdsByRole.getOrDefault(role.getId(), List.of())
                    .stream()
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList());
            return vo;
        }).toList();
    }

    // ==================== §7.2 权限树 ====================

    @Override
    public List<PermissionNodeVO> permissionTree() {
        // permission 表没有逻辑删除列，一次查全量；排序在建树前定好，children 顺序也随之确定
        List<Permission> all = permissionService.list(new LambdaQueryWrapper<Permission>()
                .orderByAsc(Permission::getSort)
                .orderByAsc(Permission::getId));

        Map<Long, PermissionNodeVO> nodes = new LinkedHashMap<>();
        for (Permission permission : all) {
            nodes.put(permission.getId(), toNode(permission));
        }

        List<PermissionNodeVO> roots = new ArrayList<>();
        for (Permission permission : all) {
            PermissionNodeVO node = nodes.get(permission.getId());
            PermissionNodeVO parent = permission.getParentId() == null
                    ? null : nodes.get(permission.getParentId());

            if (parent == null) {
                // parentId = 0（真根）或父节点不存在（脏数据）→ 都归为根，避免节点在响应里静默消失
                roots.add(node);
            }
            else if (parent == node) {
                // 自引用（parent_id = id）是脏数据。不拦会成为自己的 child，
                // Jackson 序列化时无限递归 → 请求挂死
                log.warn("[权限树] 检测到自引用脏数据，降级为根节点。id={}", permission.getId());
                roots.add(node);
            }
            else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    // ==================== §7.3 设置角色权限 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, RolePermissionDTO dto) {
        requireRole(roleId);
        List<Long> permissionIds = normalizePermissionIds(dto.getPermissionIds());

        long beforeCount = rolePermissionService.count(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, roleId));

        // ① 全量覆盖：先删后插，同一事务
        rolePermissionService.remove(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, roleId));
        if (!permissionIds.isEmpty()) {
            List<RolePermission> links = permissionIds.stream().map(permissionId -> {
                RolePermission link = new RolePermission();
                link.setRoleId(roleId);
                link.setPermissionId(permissionId);
                return link;
            }).toList();
            rolePermissionService.saveBatch(links);
        }

        // ② 批量失效「该角色下所有用户」的权限缓存（§7.3 / §23.2）
        List<Long> affectedUserIds = loadAffectedUserIds(roleId);
        affectedUserIds.forEach(permissionLoader::evict);

        log.info("[设置角色权限] roleId={} 权限数 {} → {}，失效 {} 个用户的权限缓存",
                roleId, beforeCount, permissionIds.size(), affectedUserIds.size());
    }

    /**
     * 查出该角色下的全部用户 ID（§7.3 的「受影响用户」）。
     *
     * <pre>SELECT user_id FROM user_role WHERE role_id = ?</pre>
     * <p>命中 {@code user_role} 的 {@code idx_ur_role(role_id)}（PK 是 (user_id, role_id)，
     * 过滤列是 role_id，所以走的是那条二级索引）。
     */
    private List<Long> loadAffectedUserIds(Long roleId) {
        return userRoleService.list(new LambdaQueryWrapper<UserRole>()
                        .select(UserRole::getUserId)
                        .eq(UserRole::getRoleId, roleId))
                .stream()
                .map(UserRole::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    // ==================== 私有工具 ====================

    /** 取角色，不存在抛 40400 */
    private Role requireRole(Long roleId) {
        Role role = roleService.getById(roleId);
        if (role == null) {
            throw BizException.notFound("角色不存在");
        }
        return role;
    }

    /**
     * 权限 ID 去重 + 存在性校验。
     *
     * <p>空集合是<b>合法</b>的（表示清空该角色全部权限），所以这里不抛异常 ——
     * 与 D1-03 的 {@code normalizeRoleIds}（用户必须至少有一个角色）刻意不同。
     */
    private List<Long> normalizePermissionIds(List<Long> permissionIds) {
        List<Long> distinct = permissionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        if (permissionService.listByIds(distinct).size() != distinct.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "存在无效的权限 ID");
        }
        return distinct;
    }

    private RoleVO toRoleVO(Role role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setCode(role.getCode() == null ? null : role.getCode().name());
        vo.setDescription(role.getDescription());
        return vo;
    }

    private PermissionNodeVO toNode(Permission permission) {
        PermissionNodeVO node = new PermissionNodeVO();
        node.setId(permission.getId());
        node.setParentId(permission.getParentId());
        node.setName(permission.getName());
        node.setCode(permission.getCode());
        node.setType(permission.getType());
        return node;
    }
}
