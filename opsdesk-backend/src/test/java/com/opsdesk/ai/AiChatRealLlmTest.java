package com.opsdesk.ai;

import com.opsdesk.ai.service.AiChatService;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D11-02 验收 1 / 2：<b>真调 LLM</b> 的端到端验证
 *
 * <h2>为什么必须真调一次</h2>
 * {@code AiChatToolLoopTest} 把「模型」换成了脚本，验证的是编排骨架；
 * 但「模型在真实对话里会不会<b>主动选择</b>正确的工具」「多轮上下文是否真的被模型用上」
 * 这两件事只有真调才说明问题。
 *
 * <p>没有 {@code DS_API_KEY} 时整类跳过（与 D10-01/02 同一手法）。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DS_API_KEY", matches = ".+")
class AiChatRealLlmTest {

    private static final long EMP_WANG_ID = 4L;

    private static final List<String> TOUCHED_CONVERSATIONS = List.of(
            EMP_WANG_ID + ":real-tools",
            EMP_WANG_ID + ":real-memory");

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private ChatMemory chatMemory;

    @AfterEach
    void clearMemory() {
        TOUCHED_CONVERSATIONS.forEach(chatMemory::clear);
    }

    @Test
    @DisplayName("验收1：问「我有哪些工单」→ 模型真的调用 queryMyTickets，回答里带工单信息")
    void 模型会主动选择工具() {
        AiChatService.AiChatAnswer answer =
                aiChatService.chat(currentUser(), "我有哪些工单？", "real-tools");

        System.out.println("[D11-02 真调] toolCalls=" + answer.toolCalls());
        System.out.println("[D11-02 真调] answer=" + answer.answer());

        assertThat(answer.toolCalls())
                .as("§11.2：模型应当选择 queryMyTickets 而不是凭空回答")
                .isNotEmpty()
                .anyMatch(call -> "queryMyTickets".equals(call.tool()) && call.success());
        assertThat(answer.answer()).as("回答不能为空").isNotBlank();
        assertThat(answer.sessionId()).isEqualTo("real-tools");

        // 验收 7：回答里不能夹带内部提示词（模型的原始响应里是有这些的）
        assertThat(answer.answer())
                .as("不能把系统提示词/内部指令泄露给用户")
                .doesNotContain("你是 OpsDesk IT 服务台")
                .doesNotContain("你可以调用工具获取真实数据")
                .doesNotContain("工具调用的参数必须来自用户的问题");
    }

    @Test
    @DisplayName("验收2：同一 sessionId 连续两问 → 第二问能引用第一问的上下文（记忆生效）")
    void 多轮对话有上下文() {
        aiChatService.chat(currentUser(), "请记住我的工位是 A 区 3 楼，我叫测试员小李。", "real-memory");

        AiChatService.AiChatAnswer second =
                aiChatService.chat(currentUser(), "我叫什么名字？在哪个工位？", "real-memory");

        System.out.println("[D11-02 真调] 第二轮 answer=" + second.answer());

        assertThat(second.answer())
                .as("能答出第一轮告诉它的信息 → 会话记忆确实生效了")
                .contains("测试员小李");
    }

    private UserContext.CurrentUser currentUser() {
        return new UserContext.CurrentUser(EMP_WANG_ID, "jti-real", Set.of(Role.EMPLOYEE),
                Set.of("ticket:list"), 2L);
    }
}
