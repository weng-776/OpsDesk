package com.opsdesk.ai;

import com.opsdesk.ai.service.AiChatService;
import com.opsdesk.common.JwtHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D11-02 验收：`POST /api/ai/chat` 的鉴权、参数校验与响应结构（API 文档 §11.2）
 *
 * <p>把 {@link AiChatService} 换成 mock：这一层要验的是<b>接口契约</b>（鉴权、参数、响应形状），
 * 不需要真的调用 LLM —— 真调那部分见 {@code AiChatRealLlmTest}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiChatApiTest {

    private static final long EMP_WANG_ID = 4L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtHelper jwtHelper;

    @MockitoBean
    private AiChatService aiChatService;

    @Test
    @DisplayName("EMPLOYEE（有 ticket:list）提问 → 200，响应含 answer / toolCalls / sessionId")
    void 正常提问() throws Exception {
        when(aiChatService.chat(any(), anyString(), any())).thenReturn(new AiChatService.AiChatAnswer(
                "你有一张工单",
                List.of(new AiChatService.AiToolCall("queryMyTickets", Map.of(), true)),
                "sid-123"));

        mockMvc.perform(post("/api/ai/chat")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"我有哪些工单\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value("你有一张工单"))
                .andExpect(jsonPath("$.data.sessionId").value("sid-123"))
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("queryMyTickets"))
                .andExpect(jsonPath("$.data.toolCalls[0].success").value(true));
    }

    @Test
    @DisplayName("§11.2：鉴权 ticket:list —— 未登录 → 40100")
    void 未登录被拒() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        verifyNoInteractions(aiChatService);
    }

    @Test
    @DisplayName("prompt 为空 / 超 500 字 → 40001，且不进入服务层")
    void 参数校验() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(post("/api/ai/chat")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"" + "问".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        verifyNoInteractions(aiChatService);
    }

    private String tokenOf(long userId) {
        return "Bearer " + jwtHelper.createToken(userId);
    }
}
