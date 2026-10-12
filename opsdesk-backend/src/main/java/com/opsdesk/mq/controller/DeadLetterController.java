package com.opsdesk.mq.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.mq.dto.DeadLetterQuery;
import com.opsdesk.mq.service.DeadLetterService;
import com.opsdesk.mq.vo.DeadLetterVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信管理接口（API 文档 §15.1 / §15.2，规格基线 §21.12）
 *
 * <pre>
 * GET  /api/mq/dead-letters?page=1&amp;size=10&amp;queue=
 * POST /api/mq/dead-letters/{id}/requeue
 * </pre>
 *
 * <h2>权限 {@code mq:manage}（§22：仅 ADMIN）</h2>
 * 这是 D9-05 按 §0.5 决策 3 <b>新增的 API 权限码</b>。
 * 为什么不用现成的：
 * <ul>
 *   <li>不复用 {@code menu:system:mq} —— §3.6 明确「前端用 MENU 控菜单，
 *       <b>后端必须对 API 权限做真正的校验</b>」，拿 MENU 码当 API 校验等于把菜单可见性当授权；</li>
 *   <li>不复用 {@code audit:view} —— 那会让「能看审计」隐式等于「能重投死信」，语义污染。</li>
 * </ul>
 * EMPLOYEE / AGENT 访问 → <b>40300</b>（§25.3 用例 #4 的同类），不是 40100：
 * 他们是登录用户，只是没这个权限。
 *
 * <h2>⚠️ {@code {id}} 是「列表里的 1-based 序号」，不是数据库主键</h2>
 * 死信在 MQ 里、没有稳定的业务 id（详见 {@link DeadLetterVO} 的说明）。
 * 所以调用方应当<b>先 GET 一次拿序号、再立刻 requeue</b>；序号在列表变化后会失效。
 */
@RestController
@RequestMapping("/api/mq/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    /**
     * 分页查看死信消息（§15.1）。
     *
     * <p>⚠️ 读操作是<b>非破坏性</b>的（管理 API 的 {@code ack_requeue_true}）——
     * 看一眼不会把死信取走。
     */
    @RequirePermission(PermissionCodes.MQ_MANAGE)
    @GetMapping
    public Result<PageResult<DeadLetterVO>> page(@Valid DeadLetterQuery query) {
        return Result.ok(deadLetterService.page(query));
    }

    /**
     * 重新投递一条死信（§15.2）。
     *
     * <p>重投前做<b>幂等校验</b>（{@code mq:consumed:{consumer}:{messageId}}）：
     * 已成功消费过的消息不会重复执行业务，直接返回成功（§15.2 原文的「标记为已处理」）。
     */
    @RequirePermission(PermissionCodes.MQ_MANAGE)
    @PostMapping("/{id}/requeue")
    public Result<Void> requeue(@PathVariable long id) {
        deadLetterService.requeue(id);
        // §15.2 的响应是 data: null —— 「被幂等挡掉」也算成功（那条消息本来就是处理过的）
        return Result.ok();
    }
}
