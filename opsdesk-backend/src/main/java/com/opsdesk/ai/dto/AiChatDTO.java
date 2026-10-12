package com.opsdesk.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 助手对话请求（工单 D11-04，API 文档 §11.2）
 *
 * <pre>
 * POST /api/ai/chat
 * { "prompt": "...", "sessionId": "7f3a-..." }
 * </pre>
 *
 * <p>校验一律用 Jakarta Validation 注解，<b>不在 Controller 里手写 if</b> ——
 * 校验失败由 {@code GlobalExceptionHandler} 统一转成 {@code 40001} + 字段级明细（§20.2）。
 */
@Data
public class AiChatDTO {

    /**
     * sessionId 的格式：标准 UUID（8-4-4-4-12）。
     *
     * <p>§11.2 规定 sessionId 是 UUID，而服务端生成的也正是 {@code UUID.randomUUID()} 的输出。
     * 强校验有两个作用：① 让输入形状可预期；② 挡住「塞一个超长/含特殊字符的串」这类输入。
     *
     * <p>⚠️ 但它<b>不是</b>会话隔离的手段 —— 真正的隔离是「{@code conversationId = userId:sessionId}」
     * （见 {@code AiChatServiceImpl}）：就算有人猜到别人的 sessionId，
     * 拼上自己的 userId 之后也是一个<b>不同的会话</b>，读不到别人的历史。
     */
    public static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /** 用户输入（§11.2：必填，≤500 字） */
    @NotBlank(message = "提问内容不能为空")
    @Size(max = 500, message = "提问内容不能超过 500 字")
    private String prompt;

    /**
     * 会话 ID（可空）。
     *
     * <p>为空时由服务端生成一个 UUID 并在响应里回传 —— 前端必须把下一轮的 {@code sessionId} 带回来，
     * 否则每轮都是新会话、没有上下文。
     *
     * <p>{@code @Pattern} 对 {@code null} 是放行的，所以「可空」与「有值则必须是 UUID」两者并不冲突。
     */
    @Pattern(regexp = UUID_PATTERN, message = "sessionId 必须是 UUID 格式")
    private String sessionId;
}
