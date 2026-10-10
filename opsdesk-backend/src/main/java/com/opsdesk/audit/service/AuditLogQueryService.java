package com.opsdesk.audit.service;

import com.opsdesk.audit.dto.AuditLogQuery;
import com.opsdesk.audit.vo.AuditLogVO;
import com.opsdesk.common.PageResult;

/**
 * 审计日志查询（工单 D6-02，API 文档 §13.1）
 *
 * <p>规格依据：API 文档 §13.1（接口与筛选参数）、§16.16（出参字段）；
 * 规格基线 §22（权限：{@code audit:view} 仅 ADMIN）、§18.4（索引）、§14.3（不可篡改）。
 *
 * <h2>⚠️ 只读，且永远只读（§14.3）</h2>
 * 审计日志「不提供任何修改 / 删除审计日志的 API」。
 * 本接口与它的实现里<b>只有查询</b> —— 后续给本模块加功能时，不要加写接口。
 * （{@code AuditLogService} 是生成类，技术上带 {@code remove*} 方法，
 * 但**没有任何 HTTP 入口**，也不该有。）
 *
 * <h2>索引</h2>
 * 筛选条件按 {@code idx_audit_resource(resource_type, resource_id, created_at)} /
 * {@code idx_audit_user(user_id, created_at)} / {@code idx_audit_op(operation, created_at)}
 * 的最左前缀设计，且排序用 {@code created_at DESC} —— 与这三个索引的尾列方向一致。
 */
public interface AuditLogQueryService {

    /**
     * 分页查询审计日志（按时间倒序，最新在前）。
     *
     * @param query 筛选 + 分页参数；各筛选条件都是可选的（不传即不筛）
     * @return 分页结果；查不到时是空列表而不是 40400（「查不到数据但请求合法」）
     */
    PageResult<AuditLogVO> page(AuditLogQuery query);
}
