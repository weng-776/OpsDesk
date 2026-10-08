package com.opsdesk.ticket.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.support.IdempotencyGuard;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import com.opsdesk.ticket.vo.TicketListVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单接口（API 文档 §8）
 *
 * <p>D3-01 创建、D3-02 列表与我的工单；详情 / 状态流转归 D3-03 ~ D4-05。
 *
 * <p>鉴权：需登录（{@code AuthInterceptor}）+ 权限码（{@code ticket:create} / {@code ticket:list}）。
 * 数据范围由 {@code TicketDataScopeHelper} 在 service 层叠加，Controller 不参与。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketCreateService ticketCreateService;
    private final TicketQueryService ticketQueryService;

    public TicketController(TicketCreateService ticketCreateService,
                            TicketQueryService ticketQueryService) {
        this.ticketCreateService = ticketCreateService;
        this.ticketQueryService = ticketQueryService;
    }

    /**
     * 创建工单（§8.1）。
     *
     * <p>幂等：可选请求头 {@code Idempotency-Key}（§2.5 / §23.3）。
     * 带该头时，重复提交会返回<b>首次的响应体</b>，而不是再建一张单。
     *
     * @param idempotencyKey 声明 {@code required = false} —— §8.1 说「支持」而非「必填」
     */
    @RequirePermission(PermissionCodes.TICKET_CREATE)
    @PostMapping
    public Result<TicketCreatedVO> create(
            @Valid @RequestBody TicketCreateDTO dto,
            @RequestHeader(value = IdempotencyGuard.HEADER_NAME, required = false) String idempotencyKey) {
        return Result.ok(ticketCreateService.create(dto, idempotencyKey));
    }

    /**
     * 工单列表（§8.3）—— 数据范围按角色自动叠加。
     *
     * <p>⚠️ 路径顺序无关，但 {@code /mine} 是字面量路径，Spring 会优先匹配它而不是
     * 将来的 {@code /{id}}，不会被吃掉。
     */
    @RequirePermission(PermissionCodes.TICKET_LIST)
    @GetMapping
    public Result<PageResult<TicketListVO>> page(@Valid TicketQuery query) {
        return Result.ok(ticketQueryService.page(query));
    }

    /**
     * 我的工单（§8.2）—— <b>只看自己创建的</b>（{@code creator_id = 当前用户}）。
     *
     * <p>注意它<b>不走角色数据范围</b>：§8.2 抬头明确写「数据范围：SELF」，
     * 所以 ADMIN 调它也只能看到自己创建的工单。
     */
    @RequirePermission(PermissionCodes.TICKET_LIST)
    @GetMapping("/mine")
    public Result<PageResult<TicketListVO>> mine(@Valid TicketQuery query) {
        return Result.ok(ticketQueryService.mine(query));
    }
}
