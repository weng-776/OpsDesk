package com.opsdesk.ai.controller;

import com.opsdesk.ai.dto.AiChatDTO;
import com.opsdesk.ai.service.AiChatService;
import com.opsdesk.ai.support.AiRateLimiter;
import com.opsdesk.ai.vo.AiChatVO;
import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.Result;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.constant.PermissionCodes;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * AI 助手对话接口（工单 D11-04，API 文档 §11.2）
 *
 * <pre>
 * POST /api/ai/chat
 * { "prompt": "...", "sessionId": "7f3a-..." }
 * </pre>
 *
 * <p><b>鉴权</b> {@code ticket:list}｜<b>限流</b> 20 次/分钟（{@link AiRateLimiter}）
 *
 * <h2>这个类只做三件事</h2>
 * <ol>
 *   <li><b>在请求线程上捕获身份</b> —— {@code UserContext} 是 ThreadLocal，只在请求线程有值；
 *       而对话会进 {@code AiChatGateway} 的线程池。所以身份必须在这里取下来，作为普通参数传下去。
 *       这是 D11-01 设计片段里那条身份链路的<b>起点</b>；</li>
 *   <li><b>限流</b> —— 且必须在进 service（→ 调 LLM）之前，否则成了事后记账；</li>
 *   <li><b>DTO → VO 转换</b> —— 对外契约与内部契约分开，响应只暴露该暴露的字段。</li>
 * </ol>
 * 校验交给 Jakarta Validation（{@link AiChatDTO}），失败由全局处理器统一转 {@code 40001} —— 这里不写 if。
 *
 * <h2>⚠️ 为什么不返回模型的原始响应</h2>
 * {@code ChatResponse} 里有系统提示词、工具返回的业务数据原文、内部元数据。
 * {@link AiChatVO} 只保留「答案 + 工具调用摘要 + sessionId」三项，测试里有一条断言专门盯这个。
 *
 * <h2>⚠️ 不做流式（SSE）</h2>
 * §11.2 定义的就是普通 JSON 响应。规格没要求流式，加了只是额外复杂度。
 */
@RestController
public class AiChatController {

    private final AiChatService aiChatService;
    private final AiRateLimiter aiRateLimiter;

    public AiChatController(AiChatService aiChatService, AiRateLimiter aiRateLimiter) {
        this.aiChatService = aiChatService;
        this.aiRateLimiter = aiRateLimiter;
    }

    /**
     * 一轮对话。
     *
     * <p>响应 {@code data} 的三个字段见 API 文档 §11.2。
     *
     * <p>⚠️ 客户端没传 {@code sessionId} 时，服务端生成一个并在响应里回传 ——
     * 前端要把下一轮的 {@code sessionId} 带回来，否则每轮都是新会话（没有上下文）。
     *
     * <p>错误码：{@code 40001}（prompt 为空/超 500）、{@code 40100}（未登录）、
     * {@code 42900}（超过 20 次/分钟）、{@code 50300}（LLM 不可用）。
     */
    @RequirePermission(PermissionCodes.TICKET_LIST)
    @PostMapping("/api/ai/chat")
    public Result<AiChatVO> chat(@Valid @RequestBody(required = false) AiChatDTO dto) {
        // 请求体缺失不属于「字段校验」的范畴（@Valid 只管字段），单独给一个明确的 40001
        if (dto == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }

        // 🔴 在请求线程上捕获完整身份（含 departmentId / permissions）—— 再晚就拿不到了
        UserContext.CurrentUser currentUser = UserContext.get();
        if (currentUser == null) {
            // 正常轮不到这里（AuthInterceptor 在 order 0 已经返回 40100），fail-fast 兜底
            throw new BizException(ErrorCode.UNAUTHORIZED, "AI 助手需要登录后使用");
        }

        // 🔴 限流（§11.3「成本失控」）：必须在进 service（→ 调 LLM）【之前】，否则是事后记账。
        //    也是「参数校验通过之后」—— 参数写错的请求不该消耗额度。
        aiRateLimiter.check(currentUser.userId());

        AiChatService.AiChatAnswer answer =
                aiChatService.chat(currentUser, dto.getPrompt(), dto.getSessionId());
        return Result.ok(toVO(answer));
    }

    /**
     * 内部契约（{@link AiChatService.AiChatAnswer}）→ 对外契约（{@link AiChatVO}）。
     *
     * <p>刻意分成两个类型：service 的 record 是模块内部的东西，随时可能调整；
     * 而 VO 是**对外承诺**，改动意味着接口变更。两者绑在一起会让内部重构变成破坏性变更。
     */
    private AiChatVO toVO(AiChatService.AiChatAnswer answer) {
        AiChatVO vo = new AiChatVO();
        vo.setAnswer(answer.answer());
        vo.setSessionId(answer.sessionId());
        vo.setToolCalls(Objects.requireNonNullElse(answer.toolCalls(), List.<AiChatService.AiToolCall>of())
                .stream()
                .map(this::toToolCallVO)
                .toList());
        return vo;
    }

    private AiChatVO.ToolCall toToolCallVO(AiChatService.AiToolCall call) {
        AiChatVO.ToolCall vo = new AiChatVO.ToolCall();
        vo.setTool(call.tool());
        vo.setArgs(call.args());
        vo.setSuccess(call.success());
        return vo;
    }
}
