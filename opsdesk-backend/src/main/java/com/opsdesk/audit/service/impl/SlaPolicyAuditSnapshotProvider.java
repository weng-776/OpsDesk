package com.opsdesk.audit.service.impl;

import com.opsdesk.audit.service.AuditSnapshotProvider;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.sla.entity.SlaPolicy;
import com.opsdesk.sla.service.SlaPolicyService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** SLA 策略审计快照（D6-04；不把生成列 activePriority 暴露为业务字段） */
@Component
public class SlaPolicyAuditSnapshotProvider implements AuditSnapshotProvider {

    private final SlaPolicyService slaPolicyService;

    public SlaPolicyAuditSnapshotProvider(SlaPolicyService slaPolicyService) {
        this.slaPolicyService = slaPolicyService;
    }

    @Override
    public AuditResourceType resourceType() {
        return AuditResourceType.SLA_POLICY;
    }

    @Override
    public Object snapshot(Long resourceId) {
        SlaPolicy policy = slaPolicyService.getById(resourceId);
        if (policy == null) {
            return null;
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", policy.getId());
        snapshot.put("priority", policy.getPriority());
        snapshot.put("responseMinutes", policy.getResponseMinutes());
        snapshot.put("resolutionMinutes", policy.getResolutionMinutes());
        snapshot.put("status", policy.getStatus());
        snapshot.put("effectiveFrom", policy.getEffectiveFrom());
        snapshot.put("createdAt", policy.getCreatedAt());
        snapshot.put("updatedAt", policy.getUpdatedAt());
        return snapshot;
    }
}
