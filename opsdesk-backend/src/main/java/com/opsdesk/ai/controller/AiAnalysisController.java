package com.opsdesk.ai.controller;

import com.opsdesk.ai.service.AiAnalysisQueryService;
import com.opsdesk.ai.vo.AiAnalysisVO;
import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 分析的触发与查询接口（工单 D10-04，API 文档 §11.1 / §8.13）
 *
 * <pre>
 * POST /api/ai/tickets/{ticketId}/analyze     §11.1 手动触发（重试 / 刷新）
 * GET  /api/tickets/{ticketId}/ai-analysis    §8.13 查询结果
 * </pre>
 *
 * <h2>⚠️ 两个端点前缀不同，所以类级没有 {@code @RequestMapping}</h2>
 * 项目惯例是「类级定前缀 + 方法级相对路径」，但这两个端点在规格里就分属两个前缀：
 * 触发在 <b>{@code /api/ai/**}</b>，查询在 <b>{@code /api/tickets/**}</b>（§8.13 就是这么定的）。
 * 硬塞一个类级前缀会让其中一个路径变错 —— 所以这里用方法级完整路径。
 *
 * <p>🔴 <b>查询路径千万别写成 {@code /api/ai/tickets/{id}/analysis}</b>：
 * 那是按 §11 的"AI 模块"直觉推出来的，但规格 §8.13 把它归在工单域下。
 * 路径写错的表现是前端 404，而 {@code AiAnalysisControllerTest} 会直接钉住这两个路径。
 *
 * <h2>权限与数据范围</h2>
 * <ul>
 *   <li>{@code POST} —— {@code ticket:process}（§11.1 明确）。能"处理工单"的人才谈得上刷新它的分析；</li>
 *   <li>{@code GET} —— {@code ticket:detail}：与"看工单详情"同一个权限码。
 *       分析结果里含问题摘要与处理建议，实质是工单内容的另一种呈现，
 *       所以「能看详情」就是它的门槛，不另开权限码。</li>
 * </ul>
 * <p>数据范围（40301）由 service 层用 {@code TicketDataScopeHelper} 叠加 ——
 * 与 {@code TicketController} 一致，Controller 不参与。
 */
@RestController
public class AiAnalysisController {

    private final AiAnalysisQueryService aiAnalysisQueryService;

    public AiAnalysisController(AiAnalysisQueryService aiAnalysisQueryService) {
        this.aiAnalysisQueryService = aiAnalysisQueryService;
    }

    /**
     * 手动触发一次分析（§11.1）—— 用于重试或手动刷新。
     *
     * <p>⚠️ 重复调用会<b>落一条新记录</b>，查询按最新一条返回（不是覆盖）。
     * <p>⚠️ 返回体里的 {@code status} 可能是 {@code FAILED}（AI 不可用且规则引擎也失败）——
     * 那是业务结果，HTTP 仍是 200。
     */
    @RequirePermission(PermissionCodes.TICKET_PROCESS)
    @PostMapping("/api/ai/tickets/{ticketId}/analyze")
    public Result<AiAnalysisVO> analyze(@PathVariable long ticketId) {
        return Result.ok(aiAnalysisQueryService.triggerAnalyze(ticketId));
    }

    /**
     * 查询工单最新的分析结果（§8.13）。
     *
     * <p>工单不存在 / 尚未生成过分析 → {@code 40400}；数据范围外 → {@code 40301}。
     */
    @RequirePermission(PermissionCodes.TICKET_DETAIL)
    @GetMapping("/api/tickets/{ticketId}/ai-analysis")
    public Result<AiAnalysisVO> latest(@PathVariable long ticketId) {
        return Result.ok(aiAnalysisQueryService.latest(ticketId));
    }
}
