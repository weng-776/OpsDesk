package com.opsdesk.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.audit.service.AuditSnapshotProvider;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.user.entity.Role;
import com.opsdesk.user.entity.RolePermission;
import com.opsdesk.user.service.RolePermissionService;
import com.opsdesk.user.service.RoleService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 角色审计快照（D6-04；权限集合排序后入快照，便于 before/after 稳定比较） */
@Component
public class RoleAuditSnapshotProvider implements AuditSnapshotProvider {

    private final RoleService roleService;
    private final RolePermissionService rolePermissionService;

    public RoleAuditSnapshotProvider(RoleService roleService, RolePermissionService rolePermissionService) {
        this.roleService = roleService;
        this.rolePermissionService = rolePermissionService;
    }

    @Override
    public AuditResourceType resourceType() {
        return AuditResourceType.ROLE;
    }

    @Override
    public Object snapshot(Long resourceId) {
        Role role = roleService.getById(resourceId);
        if (role == null) {
            return null;
        }

        List<Long> permissionIds = rolePermissionService.list(new LambdaQueryWrapper<RolePermission>()
                        .select(RolePermission::getPermissionId)
                        .eq(RolePermission::getRoleId, resourceId)
                        .orderByAsc(RolePermission::getPermissionId))
                .stream().map(RolePermission::getPermissionId).toList();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", role.getId());
        snapshot.put("name", role.getName());
        snapshot.put("code", role.getCode());
        snapshot.put("description", role.getDescription());
        snapshot.put("permissionIds", permissionIds);
        snapshot.put("createdAt", role.getCreatedAt());
        snapshot.put("updatedAt", role.getUpdatedAt());
        return snapshot;
    }
}
