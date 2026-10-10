package com.opsdesk.sla.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.sla.dto.SlaPolicyUpdateDTO;
import com.opsdesk.sla.service.SlaPolicyManageService;
import com.opsdesk.sla.vo.SlaPolicyVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SLA 策略接口（API 文档 §9）
 *
 * <p>D5-01 实现三个接口：列表（§9.1）、详情（§9.2）、<b>版本化修改</b>（§9.3）。
 *
 * <p>鉴权：需登录（{@code AuthInterceptor}）+ 权限码
 * （{@code sla:list} 查 / {@code sla:update} 改）。按 RBAC §8 只有 ADMIN 持有 {@code sla:update}。
 *
 * <p>⚠️ 这里<b>没有</b>「按 priority 查详情」的路径：§9.2 明确「统一用主键 id，
 * 不用 {@code {priority}}，避免与 PUT 的路径语义冲突」。
 */
@RestController
@RequestMapping("/api/sla/policies")
public class SlaPolicyController {

    private final SlaPolicyManageService slaPolicyManageService;

    public SlaPolicyController(SlaPolicyManageService slaPolicyManageService) {
        this.slaPolicyManageService = slaPolicyManageService;
    }

    /**
     * 策略列表（§9.1）—— 可按优先级 / 状态过滤。
     *
     * <p>版本化之后同一优先级会有多行（一条 ACTIVE + 若干历史 INACTIVE），
     * 所以这个列表天然是「版本历史」视图。
     *
     * @param priority 可选；非法值会由 Spring 抛类型不匹配 → 全局处理器转 {@code 40001}
     * @param status   可选；{@code ACTIVE} / {@code INACTIVE}
     */
    @RequirePermission(PermissionCodes.SLA_LIST)
    @GetMapping
    public Result<List<SlaPolicyVO>> list(
            @RequestParam(required = false) TicketPriority priority,
            @RequestParam(required = false) String status) {
        return Result.ok(slaPolicyManageService.list(priority, status));
    }

    /**
     * 策略详情（§9.2）。
     *
     * <p>不存在 → {@code 40400}。
     */
    @RequirePermission(PermissionCodes.SLA_LIST)
    @GetMapping("/{id}")
    public Result<SlaPolicyVO> detail(@PathVariable Long id) {
        return Result.ok(slaPolicyManageService.detail(id));
    }

    /**
     * 修改 SLA 策略（§9.3）—— <b>版本化修改</b>，返回新版本。
     *
     * <p>⚠️ 不是原地 UPDATE：旧版本 {@code → INACTIVE}，新增一条 {@code ACTIVE}（{@code effective_from = now}）。
     * 存量工单的 {@code sla_policy_id} 仍指向旧版本，口径不变（§9.7）。
     *
     * <p>错误：不存在 {@code 40400}；目标不是 ACTIVE 版本 {@code 40900}；
     * 解决时限 &lt; 响应时限 {@code 40001}；非 ADMIN {@code 40300}。
     */
    @RequirePermission(PermissionCodes.SLA_UPDATE)
    @PutMapping("/{id}")
    public Result<SlaPolicyVO> update(@PathVariable Long id,
                                      @Valid @RequestBody SlaPolicyUpdateDTO dto) {
        return Result.ok(slaPolicyManageService.update(id, dto));
    }
}
