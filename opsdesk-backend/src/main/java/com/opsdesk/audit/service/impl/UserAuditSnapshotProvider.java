package com.opsdesk.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.audit.service.AuditSnapshotProvider;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.service.UserRoleService;
import com.opsdesk.user.service.UserService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户审计快照（D6-04；字段白名单，绝不包含 password / BCrypt 密文） */
@Component
public class UserAuditSnapshotProvider implements AuditSnapshotProvider {

    private final UserService userService;
    private final UserRoleService userRoleService;

    public UserAuditSnapshotProvider(UserService userService, UserRoleService userRoleService) {
        this.userService = userService;
        this.userRoleService = userRoleService;
    }

    @Override
    public AuditResourceType resourceType() {
        return AuditResourceType.USER;
    }

    @Override
    public Object snapshot(Long resourceId) {
        User user = userService.getById(resourceId);
        if (user == null) {
            return null;
        }

        List<Long> roleIds = userRoleService.list(new LambdaQueryWrapper<UserRole>()
                        .select(UserRole::getRoleId)
                        .eq(UserRole::getUserId, resourceId)
                        .orderByAsc(UserRole::getRoleId))
                .stream().map(UserRole::getRoleId).toList();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", user.getId());
        snapshot.put("username", user.getUsername());
        snapshot.put("nickname", user.getNickname());
        snapshot.put("email", user.getEmail());
        snapshot.put("departmentId", user.getDepartmentId());
        snapshot.put("status", user.getStatus());
        snapshot.put("roleIds", roleIds);
        snapshot.put("createdAt", user.getCreatedAt());
        snapshot.put("updatedAt", user.getUpdatedAt());
        // ⚠️ 不得添加 password：即使是 BCrypt 密文也属于凭据，不复制进永久审计表。
        return snapshot;
    }
}
