package com.opsdesk.dashboard.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.dashboard.service.DashboardService;
import com.opsdesk.dashboard.vo.DashboardAgentVO;
import com.opsdesk.dashboard.vo.DashboardOverviewVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计 Dashboard（API 文档 §14、规格基线 §21.11）
 *
 * <pre>
 * GET /api/dashboard/overview   # ADMIN        → 6 个字段（全企业口径）
 * GET /api/dashboard/agent      # AGENT / ADMIN → 4 个字段（当前用户视角）
 * </pre>
 *
 * <h2>⚠️ 两个接口都用同一个权限码，但可见角色不同</h2>
 * <b>注意：两个接口都用同一个权限码，但可见角色不同</b>：
 * 种子数据里 ADMIN / AGENT / EMPLOYEE <b>都有</b> {@code dashboard:view}（本单不改库），
 * 所以「仅 ADMIN」「仅 AGENT / ADMIN」这两条角色约束<b>只能由 Service 层判定</b>：
 * <ul>
 *   <li>AGENT 调 {@code /overview} → {@code 40300}</li>
 *   <li>EMPLOYEE 调任意一个 → {@code 40300}</li>
 * </ul>
 * 注解在这里的作用只是「连 dashboard:view 都没有的人连 Service 都进不去」，
 * 不是完整的授权 —— 别以为改了注解就能改角色可见性。
 *
 * <h2>⚠️ 纯只读，不要给本模块加写接口</h2>
 * Dashboard 的数字全部是<b>派生</b>的（来自 {@code ticket} 表实时聚合），
 * 一旦出现「可写」的 Dashboard 接口，就说明有人在别处绕过了工单状态机。
 * 工单数据只能通过 {@code /api/tickets/**} 的流转接口变更。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 管理端总览（§14.1）—— 6 个指标，全企业口径，<b>不加任何数据范围条件</b>。
     *
     * <p>响应示例：{@code { "todayCreated":12, "pending":21, "processing":13,
     * "resolvedToday":8, "slaBreached":2, "avgResolveMinutes":180 }}。
     * 无数据时 6 个字段全为 {@code 0}，不会出现 {@code null}（前端可直接渲染）。
     */
    @RequirePermission(PermissionCodes.DASHBOARD_VIEW)
    @GetMapping("/overview")
    public Result<DashboardOverviewVO> overview() {
        return Result.ok(dashboardService.overview());
    }

    /**
     * AGENT 工作台（§14.2）—— 4 个指标，当前用户视角。
     *
     * <p>响应示例：{@code { "poolCount":5, "myCount":3, "warningCount":2, "resolvedToday":4 }}。
     * 口径见 {@link DashboardAgentVO}（规格原文只列了字段名，口径在补单文档 §3 定死）。
     */
    @RequirePermission(PermissionCodes.DASHBOARD_VIEW)
    @GetMapping("/agent")
    public Result<DashboardAgentVO> agent() {
        return Result.ok(dashboardService.agent());
    }
}
