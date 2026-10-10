package com.opsdesk.audit.service.impl;

import com.opsdesk.audit.service.AuditSnapshotProvider;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工单的审计快照（工单 D6-01）
 *
 * <p>把 {@code Ticket} 转成<b>字段白名单</b>再交给序列化 —— 不直接丢实体。
 * 理由见 {@link AuditSnapshotProvider}：实体里可能藏着不该进审计表的东西，
 * 而且白名单让审计 JSON 的键集合<b>稳定可预期</b>（便于人工比对 before / after 的差异）。
 *
 * <p>用 {@link LinkedHashMap} 而不是 {@code HashMap}：键顺序稳定，
 * 审计 JSON 在库里看起来永远同一个样子，diff 时不会有假差异。
 *
 * <h2>为什么带上 {@code description}</h2>
 * 它是本表最可能超长的字段（§14.3 点名要截断到 2000 字符的就是它）。
 * 审计要能看到「描述被改成了什么」，所以必须记；截断交给 {@code AuditRecordService} 统一做。
 */
@Component
public class TicketAuditSnapshotProvider implements AuditSnapshotProvider {

    private final TicketService ticketService;

    public TicketAuditSnapshotProvider(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public AuditResourceType resourceType() {
        return AuditResourceType.TICKET;
    }

    @Override
    public Object snapshot(Long resourceId) {
        Ticket ticket = ticketService.getById(resourceId);
        if (ticket == null) {
            return null;
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", ticket.getId());
        snapshot.put("ticketNo", ticket.getTicketNo());
        snapshot.put("title", ticket.getTitle());
        snapshot.put("description", ticket.getDescription());
        snapshot.put("type", ticket.getType());
        snapshot.put("category", ticket.getCategory());
        snapshot.put("priority", ticket.getPriority());
        snapshot.put("status", ticket.getStatus());
        snapshot.put("source", ticket.getSource());
        snapshot.put("creatorId", ticket.getCreatorId());
        snapshot.put("departmentId", ticket.getDepartmentId());
        snapshot.put("assigneeId", ticket.getAssigneeId());
        // ---- SLA ----
        snapshot.put("slaPolicyId", ticket.getSlaPolicyId());
        snapshot.put("responseDeadline", ticket.getResponseDeadline());
        snapshot.put("resolutionDeadline", ticket.getResolutionDeadline());
        snapshot.put("firstResponseAt", ticket.getFirstResponseAt());
        snapshot.put("slaResponseState", ticket.getSlaResponseState());
        snapshot.put("slaResolutionState", ticket.getSlaResolutionState());
        snapshot.put("slaPausedAt", ticket.getSlaPausedAt());
        snapshot.put("slaPausedMinutes", ticket.getSlaPausedMinutes());
        snapshot.put("reopenCount", ticket.getReopenCount());
        // ---- 生命周期 ----
        snapshot.put("resolvedAt", ticket.getResolvedAt());
        snapshot.put("closedAt", ticket.getClosedAt());
        snapshot.put("cancelReason", ticket.getCancelReason());
        // version 是乐观锁版本号：它变了就说明这一行被改过，对审计比对有用
        snapshot.put("version", ticket.getVersion());
        snapshot.put("createdAt", ticket.getCreatedAt());
        snapshot.put("updatedAt", ticket.getUpdatedAt());
        return snapshot;
    }
}
