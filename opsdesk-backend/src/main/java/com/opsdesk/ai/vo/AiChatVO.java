package com.opsdesk.ai.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AI 助手对话响应（工单 D11-04，API 文档 §11.2）
 *
 * <pre>
 * { "answer": "...", "toolCalls": [ { "tool": "...", "args": {}, "success": true } ], "sessionId": "..." }
 * </pre>
 *
 * <h2>⚠️ 只有这三个字段 —— 绝不返回模型的原始响应</h2>
 * 模型的 {@code ChatResponse} 里带着大量**不该出门**的东西：
 * 系统提示词（含我们的安全规则与工具描述）、工具返回的业务数据原文、内部元数据。
 * 所以这里只取「给人看的答案」+「工具调用的摘要」。
 *
 * <p>这一点由测试兜住（断言响应体里不含系统提示词的任何片段）。
 *
 * <h2>⚠️ 不提供流式（SSE）</h2>
 * §11.2 定义的响应是普通 JSON，本接口<b>不</b>做流式 —— 规格没要求，加了纯属额外复杂度。
 */
@Data
public class AiChatVO {

    /** 给用户的回答（已按长度截断） */
    private String answer;

    /** 本轮模型调用过的工具（按调用顺序） */
    private List<ToolCall> toolCalls;

    /** 实际使用的会话 ID；前端必须把它带在下一轮请求里（为空时由服务端生成） */
    private String sessionId;

    /** 一次工具调用的摘要（§11.2 的 {@code toolCalls[i]}） */
    @Data
    public static class ToolCall {

        /** 工具名，如 {@code queryMyTickets} */
        private String tool;

        /** 模型传给工具的入参（已截断）；<b>不含</b>工具返回值 */
        private Map<String, Object> args;

        /** 是否执行成功 */
        private boolean success;
    }
}
