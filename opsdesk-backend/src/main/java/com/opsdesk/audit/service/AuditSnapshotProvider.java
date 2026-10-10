package com.opsdesk.audit.service;

import com.opsdesk.common.enums.AuditResourceType;

/**
 * 审计快照提供者（工单 D6-01，规格基线 §14.3「按 resourceType + resourceId 查一次快照」）
 *
 * <p><b>一种资源类型一个实现</b>，由 {@link AuditRecordService} 注入 {@code List} 后建成索引。
 * 这样新增资源类型只加一个 Bean，不用改切面或记录服务。
 *
 * <h2>⚠️ 实现方必须脱敏，不能直接丢实体</h2>
 * 返回值会被 {@code ObjectMapper} 序列化进 {@code audit_log.before_data / after_data}。
 * 而审计表是<b>「仅 ADMIN 可见」但仍然是一张表</b> —— 把整个实体塞进去等于把敏感字段
 * 复制一份到另一个地方。最典型的：{@code User.password} 是 BCrypt 密文，
 * 一旦被序列化，凭据就散播到了审计表里（还带着「永久保留」的性质）。
 *
 * <p>所以实现方应当返回<b>字段白名单</b>（{@code Map<String, Object>} 或专用快照 DTO），
 * 而不是 {@code Entity} 本身。参考 {@code TicketAuditSnapshotProvider}。
 */
public interface AuditSnapshotProvider {

    /** 本实现负责的资源类型 */
    AuditResourceType resourceType();

    /**
     * 取一份快照。
     *
     * @param resourceId 资源 id；调用方保证非 {@code null}
     * @return 待序列化的对象（<b>已脱敏</b>）；资源不存在时返回 {@code null}
     */
    Object snapshot(Long resourceId);
}
