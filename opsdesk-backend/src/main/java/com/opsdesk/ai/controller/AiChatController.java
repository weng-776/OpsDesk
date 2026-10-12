package com.opsdesk.ai.controller;

import com.opsdesk.ai.service.AiChatService;
import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.Result;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.constant.PermissionCodes;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 助手对话接口（工单 D11-02，API 文档 §11.2）
 *
 * <pre>
 * POST /api/ai/chat   鉴权 ticket:list（需登录）
 * </pre>
 *
 * <h2>🔴 这个类唯一的实质职责：在【请求线程】上捕获身份</h2>
 * {@code UserContext} 是 {@link ThreadLocal}，只在请求线程上有值。
 * 而对话会进入 {@code AiChatGateway} 的专用线程池 —— 到了那边 {@code UserContext} 就是 null。
 *
 * <p>所以身份必须在这里、在还来得及的时候捕获下来，作为<b>普通参数</b>交给
 * {@link AiChatService}：{@code UserContext.get()} → {@code chat(currentUser, ...)}。
 * 这是 D11-01 设计片段里那条身份链路的**起点**。
 *
 * <h2>⚠️ 限流不在这里</h2>
 * §11.3 的「每用户 20 次/分钟」（Redis {@code rate:ai:{userId}}）属 D11-03，本单未实现。
 * 这里只做参数校验。
 */
@RestController
public class AiChatController {

    /** §11.2：prompt ≤ 500 字 */
    private static final int MAX_PROMPT_LENGTH = 500;

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /**
     * 一轮对话。
     *
     * <p>响应 {@code data} 的三个字段见 API 文档 §11.2：
     * {@code answer} / {@code toolCalls:[{tool,args,success}]} / {@code sessionId}。
     *
     * <p>⚠️ 客户端没传 {@code sessionId} 时服务端生成一个并在响应里回传 ——
     * 前端要把下一轮的 {@code sessionId} 带回来，否则每轮都是新会话（没有上下文）。
     */
    @RequirePermission(PermissionCodes.TICKET_LIST)
    @PostMapping("/api/ai/chat")
    public Result<AiChatService.AiChatAnswer> chat(@RequestBody(required = false) ChatRequest request) {
        // 🔴 在请求线程上捕获完整身份（含 departmentId / permissions）—— 再晚就拿不到了
        UserContext.CurrentUser currentUser = UserContext.get();
        if (currentUser == null) {
            // 正常轮不到这里（AuthInterceptor 在前），fail-fast 兜底
            throw new BizException(ErrorCode.UNAUTHORIZED, "AI 助手需要登录后使用");
        }

        String prompt = request == null ? null : request.prompt();
        if (!StringUtils.hasText(prompt)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "提问内容不能为空");
        }
        String trimmed = prompt.trim();
        if (trimmed.length() > MAX_PROMPT_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "提问内容不能超过 " + MAX_PROMPT_LENGTH + " 字");
        }

        return Result.ok(aiChatService.chat(currentUser, trimmed,
                request == null ? null : request.sessionId()));
    }

    /**
     * §11.2 的请求体。
     *
     * <p>用嵌套 {@code record} 而不是新建 DTO 文件：它只属于这一个接口，
     * 而 {@code record} 的 component 名天然就是 JSON 字段名，不需要额外注解。
     *
     * @param prompt    用户输入（必填，≤500）
     * @param sessionId 会话 ID（可空 → 服务端生成）
     */
    public record ChatRequest(String prompt, String sessionId) {
    }
}
