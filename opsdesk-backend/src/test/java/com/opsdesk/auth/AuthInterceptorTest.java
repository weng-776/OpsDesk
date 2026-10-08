package com.opsdesk.auth;

import com.opsdesk.auth.interceptor.AuthInterceptor;
import com.opsdesk.common.JwtHelper;
import com.opsdesk.common.UserContext;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权拦截器 + 登出 + /me 验收测试（工单 D1-02）
 *
 * <p>走完整的 Spring MVC 链路（真实 Redis / MySQL），不 mock ——
 * 拦截器、`GlobalExceptionHandler`、`WebMvcConfigurer` 注册这三件事的配合
 * 只有真跑一遍才算验过。
 *
 * <p>覆盖工单验收 1 / 2 / 3，外加「重复登出」与「白名单」两个边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthInterceptorTest {

    /** 种子账号 agent_zhang（§0 演示账号表） */
    private static final Long AGENT_ZHANG_ID = 2L;

    /** 只用于探测白名单的账号名，库里不存在 —— 故意不用真实账号，避免在测试里写演示口令 */
    private static final String PROBE_USERNAME = "d102_whitelist_probe";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtHelper jwtHelper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 每次测试用一个独立 jti，避免测试之间互相污染黑名单 */
    private String jti;
    private String token;
    private String blacklistKey;

    @BeforeEach
    void setUp() {
        jti = "d102-test-" + UUID.randomUUID();
        token = jwtHelper.createToken(AGENT_ZHANG_ID, jti);
        blacklistKey = AuthInterceptor.TOKEN_BLACKLIST_PREFIX + jti;
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        UserContext.clear();
    }

    /** 本测试写进 Redis 的键全部自造自清，跑完不留痕 */
    private void cleanUp() {
        redisTemplate.delete(blacklistKey);
        redisTemplate.delete("login:fail:" + PROBE_USERNAME);
    }

    // ==================== 验收 1 ====================

    @Test
    @DisplayName("验收1：不带 token 访问 /api/** → 401 / 40100")
    void noTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("验收1：token 不可解析（签名/格式错）→ 401 / 40100")
    void malformedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("验收1：Authorization 头没有 Bearer 前缀 → 401 / 40100")
    void nonBearerSchemeIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("验收1+3：带合法 token 通过，/me 返回 LoginUserVO（含 roles / permissions）")
    void validTokenPassesAndReturnsCurrentUser() throws Exception {
        // 注意：currentUser() 里的 userId 与 roles/permissions 全都取自 UserContext，
        // 所以这条断言同时反证了拦截器确实 set 成功了 —— 没 set 的话 requireUserId() 会先抛 40100。
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(2))
                .andExpect(jsonPath("$.data.username").value("agent_zhang"))
                .andExpect(jsonPath("$.data.nickname").value("张三"))
                .andExpect(jsonPath("$.data.departmentId").value(2))
                .andExpect(jsonPath("$.data.departmentName").value("技术部"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.roles", hasItem("AGENT")))
                .andExpect(jsonPath("$.data.permissions", hasItem("ticket:list")));
    }

    @Test
    @DisplayName("验收3：请求结束后 UserContext 被清空（Tomcat 线程复用不串身份）")
    void userContextIsClearedAfterRequest() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(UserContext.isLogin()).isFalse();
        assertThat(UserContext.get()).isNull();
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getPermissions()).isEmpty();
    }

    // ==================== 验收 2 ====================

    @Test
    @DisplayName("验收2：黑名单命中 → 401 / 40100")
    void blacklistedTokenIsRejected() throws Exception {
        redisTemplate.opsForValue().set(blacklistKey,
                AuthInterceptor.TOKEN_BLACKLIST_VALUE, 60, TimeUnit.SECONDS);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("验收2：登出写黑名单（TTL=剩余有效期），同一个 token 再访问 → 401 / 40100")
    void logoutThenSameTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(redisTemplate.hasKey(blacklistKey)).isTrue();
        Long ttlSeconds = redisTemplate.getExpire(blacklistKey, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        // 配置的有效期是 480 分钟（§23.1），刚签发的 token 剩余量应非常接近它
        assertThat((long) ttlSeconds).isBetween(470L * 60, 480L * 60);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("验收2：重复登出 → 401 / 40100（token 已失效，不是幂等失败）")
    void secondLogoutIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ==================== 白名单 ====================

    @Test
    @DisplayName("白名单：/api/auth/login 不带 token 也能进（40100 来自登录逻辑而非拦截器）")
    void loginIsWhitelisted() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + PROBE_USERNAME + "\",\"password\":\"probe\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                // 拦截器的文案是「未登录或 token 已失效」；能拿到登录逻辑的文案，
                // 就证明请求确实没被拦截器拦下 —— 白名单生效
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }
}
