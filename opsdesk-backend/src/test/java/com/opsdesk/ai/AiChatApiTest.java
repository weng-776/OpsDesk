package com.opsdesk.ai;

import com.opsdesk.ai.service.AiChatService;
import com.opsdesk.ai.vo.AiChatVO;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    // ==================== sessionId 的格式校验 ====================

    @Test
    @DisplayName("§11.2：sessionId 必须是 UUID —— 非法格式 → 40001（字段级明细）")
    void sessionId格式非法() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"你好\",\"sessionId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.data.sessionId").value("sessionId 必须是 UUID 格式"));

        verifyNoInteractions(aiChatService);
    }

    @Test
    @DisplayName("合法 UUID 的 sessionId → 正常透传给服务层（可空是允许的，有值就必须合法）")
    void sessionId合法时透传() throws Exception {
        String sid = "7f3a1b2c-4d5e-6f70-8a9b-0c1d2e3f4a5b";
        when(aiChatService.chat(any(), anyString(), eq(sid))).thenReturn(
                new AiChatService.AiChatAnswer("好的", List.of(), sid));

        mockMvc.perform(post("/api/ai/chat")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"你好\",\"sessionId\":\"" + sid + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sid));
    }

    @Test
    @DisplayName("不传 sessionId → 服务端生成并回传（前端必须把它带在下一轮，否则没有上下文）")
    void 不传sessionId时服务端生成() throws Exception {
        String generated = "11111111-2222-3333-4444-555555555555";
        when(aiChatService.chat(any(), anyString(), isNull())).thenReturn(
                new AiChatService.AiChatAnswer("好的", List.of(), generated));

        mockMvc.perform(post("/api/ai/chat")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(generated));
    }

    // ==================== 验收 7：响应只暴露该暴露的字段 ====================

    @Test
    @DisplayName("验收7：响应契约就是 §11.2 的那三个字段 —— 不返回模型原始响应")
    void 响应字段与规格一致() {
        // 反射断言字段清单：这是「不返回模型原始响应 / 系统提示词」最本质的保障 ——
        // 只要 VO 里没有这些字段，它们就不可能被序列化出去
        assertThat(fieldNames(AiChatVO.class))
                .as("§11.2 的 data 只有三个字段")
                .containsExactlyInAnyOrder("answer", "toolCalls", "sessionId");
        assertThat(fieldNames(AiChatVO.ToolCall.class))
                .as("§11.2 的 toolCalls[i] 只有三个字段")
                .containsExactlyInAnyOrder("tool", "args", "success");
    }

    private Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
    }

    private String tokenOf(long userId) {
        return "Bearer " + jwtHelper.createToken(userId);
    }
}
