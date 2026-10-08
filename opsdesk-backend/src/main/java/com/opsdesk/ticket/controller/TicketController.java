package com.opsdesk.ticket.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.support.IdempotencyGuard;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单接口（API 文档 §8）
 *
 * <p>D3-01 只实现创建；列表 / 详情 / 状态流转归 D3-02 ~ D4-05。
 *
 * <p>鉴权：需登录（{@code AuthInterceptor}）+ 权限码 {@code ticket:create}（§3.6 / §22）。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketCreateService ticketCreateService;

    public TicketController(TicketCreateService ticketCreateService) {
        this.ticketCreateService = ticketCreateService;
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
}
