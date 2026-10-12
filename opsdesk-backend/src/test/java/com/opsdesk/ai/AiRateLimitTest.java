package com.opsdesk.ai;

import com.opsdesk.ai.service.AiChatService;
import com.opsdesk.ai.support.AiRateLimiter;
import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.JwtHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D11-03 验收 1 / 2 / 6：AI 接口限流（§11.3「成本失控」、§23.4、API §11.2）
 *
 * <h2>为什么 mock 掉 {@link AiChatService}</h2>
 * 限流在 <b>Controller 层</b>，而这一层要打 20+ 次请求 —— 真调 LLM 既慢又烧钱。
 * mock 掉 service 之后，限流链路（Redis 计数 → 42900）与「限流发生在调 LLM 之前」
 * 都能被干净地验证：<b>mock 的调用次数就是「穿透到 LLM 的次数」</b>。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiRateLimitTest {

    private static final long EMP_WANG_ID = 4L;
    private static final long AGENT_ZHANG_ID = 2L;
    private static final List<Long> TOUCHED = List.of(EMP_WANG_ID, AGENT_ZHANG_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtHelper jwtHelper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private AiChatService aiChatService;

    private String empToken;
    private String agentToken;

    @BeforeEach
    void setUp() {
        clearRateKeys();
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
        when(aiChatService.chat(any(), anyString(), any())).thenReturn(
                new AiChatService.AiChatAnswer("好的", List.of(), "sid"));
        empToken = tokenOf(EMP_WANG_ID);
        agentToken = tokenOf(AGENT_ZHANG_ID);
    }

    @AfterEach
    void tearDown() {
        clearRateKeys();
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    // ==================== 验收 1：第 21 次 42900 ====================

    @Test
    @DisplayName("验收1：同一用户 1 分钟内调 21 次 → 前 20 次成功，第 21 次 42900")
    void 第21次被限流() throws Exception {
        for (int i = 1; i <= AiRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            call(empToken).andExpect(status().isOk());
        }

        call(empToken)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));

        // 「限流在调 LLM 之前」的直接证据：service（= LLM 入口）只被穿透了 20 次
        verify(aiChatService, times(AiRateLimiter.MAX_REQUESTS_PER_WINDOW)).chat(any(), anyString(), any());
    }

    // ==================== 验收 2：按用户独立计数 ====================

    @Test
    @DisplayName("验收2：限流键含 userId —— A 打满不影响 B；一个用户拖不垮所有人")
    void 两个用户各自计数() throws Exception {
        for (int i = 1; i <= AiRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            call(empToken).andExpect(status().isOk());
        }
        call(empToken).andExpect(status().isTooManyRequests());

        // A 已打满，B 仍然畅通
        call(agentToken).andExpect(status().isOk());
        call(agentToken).andExpect(status().isOk());
    }

    // ==================== 验收 6：TTL 正常，不会永久锁死 ====================

    @Test
    @DisplayName("验收6：计数键带 TTL（1 分钟窗口），不是永久键")
    void 计数键有TTL() throws Exception {
        call(empToken).andExpect(status().isOk());

        Long ttl = redisTemplate.getExpire(rateKey(EMP_WANG_ID), TimeUnit.SECONDS);
        assertThat(ttl).as("§23.4：TTL 1 分钟")
                .isNotNull()
                .isGreaterThan(0)
                .isLessThanOrEqualTo(60L);
    }

    @Test
    @DisplayName("验收6 关键边界：键已存在但【没有 TTL】时会被兜底补上（否则用户被永久锁死）")
    void 无TTL的键会被补设() throws Exception {
        // 人为制造「有 key、无 TTL」这种状态 —— 现实中由「INCR 成功但 EXPIRE 失败」造成。
        // 若脚本只判 `current == 1`，这里计数早已 > 1，永远补不上 TTL → 该用户被永久锁死。
        redisTemplate.opsForValue().set(rateKey(EMP_WANG_ID), "5");
        assertThat(redisTemplate.getExpire(rateKey(EMP_WANG_ID), TimeUnit.SECONDS))
                .as("前提：这个键确实没有过期时间").isEqualTo(-1L);

        call(empToken).andExpect(status().isOk());

        assertThat(redisTemplate.getExpire(rateKey(EMP_WANG_ID), TimeUnit.SECONDS))
                .as("TTL < 0 兜底分支生效，键被补上过期时间")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("超过窗口上限后仍然拒绝（计数继续累加，不是到 20 就重置）")
    void 持续拒绝() throws Exception {
        redisTemplate.opsForValue().set(rateKey(EMP_WANG_ID), "100");
        redisTemplate.expire(rateKey(EMP_WANG_ID), 60, TimeUnit.SECONDS);

        call(empToken).andExpect(status().isTooManyRequests());
        call(empToken).andExpect(status().isTooManyRequests());
        verify(aiChatService, times(0)).chat(any(), anyString(), any());
    }

    // ==================== 工具 ====================

    private org.springframework.test.web.servlet.ResultActions call(String token) throws Exception {
        return mockMvc.perform(post("/api/ai/chat")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"你好\"}"));
    }

    private String tokenOf(long userId) {
        return "Bearer " + jwtHelper.createToken(userId);
    }

    private String rateKey(long userId) {
        return "rate:ai:" + userId;
    }

    private void clearRateKeys() {
        TOUCHED.forEach(id -> redisTemplate.delete(rateKey(id)));
    }
}
