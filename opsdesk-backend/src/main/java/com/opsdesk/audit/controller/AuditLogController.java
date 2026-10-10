package com.opsdesk.audit.controller;

import com.opsdesk.audit.dto.AuditLogQuery;
import com.opsdesk.audit.service.AuditLogQueryService;
import com.opsdesk.audit.vo.AuditLogVO;
import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志接口（API 文档 §13.1）
 *
 * <pre>
 * GET /api/audit-logs?page=1&amp;size=10&amp;userId=&amp;operation=&amp;resourceType=&amp;resourceId=&amp;startTime=&amp;endTime=
 * </pre>
 *
 * <p>权限 {@code audit:view}（§22：<b>仅 ADMIN</b>）。EMPLOYEE / AGENT 访问 →
 * <b>40300</b>（§25.3 用例 #4），不是 40100 —— 他们是登录用户，只是没这个权限。
 *
 * <h2>⚠️ 本类<b>只有 GET</b>，且永远只加 GET（§14.3）</h2>
 * 「不可篡改：不提供任何修改 / 删除审计日志的 API」。
 * 审计表是追责依据 —— 一旦能改能删，它就不再是证据。
 * 后续给本模块加功能时：<b>不要</b>加 PUT / PATCH / DELETE，
 * 也不要暴露「批量清理历史审计」之类的接口。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    /**
     * 分页查询审计日志（§13.1）。
     *
     * <p>所有筛选条件都可选：只传 {@code page}/{@code size} 就是「全部审计，最新在前」。
     */
    @RequirePermission(PermissionCodes.AUDIT_VIEW)
    @GetMapping
    public Result<PageResult<AuditLogVO>> page(@Valid AuditLogQuery query) {
        return Result.ok(auditLogQueryService.page(query));
    }
}
