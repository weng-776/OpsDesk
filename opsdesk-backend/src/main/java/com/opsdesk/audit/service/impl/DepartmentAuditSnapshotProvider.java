package com.opsdesk.audit.service.impl;

import com.opsdesk.audit.service.AuditSnapshotProvider;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.organization.entity.Department;
import com.opsdesk.organization.service.DepartmentService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 部门审计快照（D6-04；删除后 after 快照为 null） */
@Component
public class DepartmentAuditSnapshotProvider implements AuditSnapshotProvider {

    private final DepartmentService departmentService;

    public DepartmentAuditSnapshotProvider(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @Override
    public AuditResourceType resourceType() {
        return AuditResourceType.DEPARTMENT;
    }

    @Override
    public Object snapshot(Long resourceId) {
        Department department = departmentService.getById(resourceId);
        if (department == null) {
            return null;
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", department.getId());
        snapshot.put("parentId", department.getParentId());
        snapshot.put("name", department.getName());
        snapshot.put("path", department.getPath());
        snapshot.put("sort", department.getSort());
        snapshot.put("status", department.getStatus());
        snapshot.put("createdAt", department.getCreatedAt());
        snapshot.put("updatedAt", department.getUpdatedAt());
        return snapshot;
    }
}
