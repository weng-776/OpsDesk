package com.opsdesk.ticket.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分派工单入参（API 文档 §8.12）
 *
 * <p>请求体：{@code { "assigneeId": 3 }}
 *
 * <p>⚠️ 只有 {@code assigneeId} 一个字段 —— §8.12 的请求体示例里 assign / transfer
 * 就只有它。不额外加 {@code remark}：规格没定义，加了就是自造契约。
 * 历史记录的备注由服务端生成（「分派给 XXX」），不依赖客户端传。
 */
@Data
public class TicketAssignDTO {

    /** 处理人 id（必填）—— 必须是 AGENT / ADMIN 且账号启用 */
    @NotNull(message = "处理人不能为空")
    private Long assigneeId;
}
