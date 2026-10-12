package com.opsdesk.ai.service;

import com.opsdesk.common.UserContext;

import java.util.List;
import java.util.Map;

/**
 * AI 助手对话（工单 D11-02，规格基线 §11.2 调用流程 / §11.3 安全边界，API 文档 §11.2）
 *
 * <h2>这个类负责把四样东西串起来</h2>
 * <ol>
 *   <li><b>工具</b> —— D11-01 的 {@code TicketAiTools}；</li>
 *   <li><b>会话记忆</b> —— Spring AI 的 {@code ChatMemory}（落 MySQL）；</li>
 *   <li><b>轮次上限</b> —— §11.3「单次最大 10 轮工具调用」；</li>
 *   <li><b>总超时</b> —— 复用 {@code AiChatGateway} 的 30s 硬上限。</li>
 * </ol>
 *
 * <h2>🔴 身份为什么是<b>方法入参</b>，而不是从 {@code UserContext} 里读</h2>
 * AI 调用会进入 D10-01 的专用线程池（{@code opsdesk-ai-call}），而 {@code UserContext} 是
 * {@link ThreadLocal} —— <b>跨线程读不到</b>。所以身份必须在请求线程上捕获、一路显式传下去：
 * <pre>
 * Controller（有 UserContext）→ 捕获 CurrentUser → chat(currentUser, ...) → new TicketAiTools(currentUser, ...)
 * </pre>
 * 这也是「Tool 入参不含 userId」的另一面：<b>身份不经过模型，也不经过 ThreadLocal</b>。
 */
public interface AiChatService {

    /**
     * 一轮对话。
     *
     * @param currentUser 当前登录用户（<b>必须由调用方在请求线程上捕获后传入</b>）
     * @param prompt      用户输入（§11.2：≤500 字）
     * @param sessionId   会话 ID；<b>可空</b> —— 为空时服务端生成 UUID 并在返回值里回传
     * @return 回答 + 本轮工具调用记录 + 实际使用的 sessionId
     */
    AiChatAnswer chat(UserContext.CurrentUser currentUser, String prompt, String sessionId);

    /**
     * 一轮对话的产物（对应 API 文档 §11.2 的响应 {@code data}）。
     *
     * <p>刻意做成 {@link AiChatService} 的嵌套 record 而不新建 VO 文件：
     * 它只在这一个契约里用，嵌套能同时保证「结构清晰」与「不散落」。
     *
     * @param answer    给用户的回答（已按长度截断）
     * @param toolCalls 本轮模型调用过的工具（顺序与调用顺序一致）
     * @param sessionId 实际使用的会话 ID（客户端未传时由服务端生成）
     */
    record AiChatAnswer(String answer, List<AiToolCall> toolCalls, String sessionId) {
    }

    /**
     * 一次工具调用的记录（§11.2 的 {@code toolCalls[i]}）。
     *
     * @param tool    工具名
     * @param args    模型传给工具的入参（<b>已截断</b>；工具返回值<b>不回显</b>）
     * @param success 是否执行成功
     */
    record AiToolCall(String tool, Map<String, Object> args, boolean success) {
    }
}
