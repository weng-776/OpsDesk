package com.opsdesk.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 撤销工单入参（API 文档 §8.12）
 *
 * <p>请求体：{@code { "reason": "问题已自行解决" }}
 *
 * <p>⚠️ {@code reason} <b>必填</b> —— 矩阵 #4/#7 的「附加约束」明确写「需 {@code cancel_reason}」，
 * 验收也要求「不带 {@code cancel_reason} → 40001」。用 {@code @NotBlank} 在进 service 前就拦掉。
 *
 * <p>字段名用 {@code reason}（跟 §8.12 的请求体示例一致），落库到 {@code ticket.cancel_reason}。
 */
@Data
public class TicketCancelDTO {

    /** 撤销原因（必填） */
    @NotBlank(message = "撤销原因不能为空")
    @Size(max = 200, message = "撤销原因不能超过 200 字")
    private String reason;
}
