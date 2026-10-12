package com.opsdesk.mq;

import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.JwtHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D9-05 权限验收：死信管理是敏感操作，仅 ADMIN 可查看 / 重投（§22）。
 *
 * <p>token 直接用 {@link JwtHelper} 签发，不走登录接口；角色与权限仍由真实拦截器从 DB/Redis 装载。
 */
@AutoConfigureMockMvc
class DeadLetterControllerPermissionTest extends AbstractMqIntegrationTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long AGENT_ID = 2L;
    private static final Long EMPLOYEE_ID = 4L;
    private static final List<Long> TOUCHED = List.of(ADMIN_ID, AGENT_ID, EMPLOYEE_ID);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtHelper jwtHelper;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanAuthCache() {
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    private String tokenOf(long userId) {
        return "Bearer " + jwtHelper.createToken(userId);
    }

    @Test
    @DisplayName("ADMIN 有 mq:manage → GET 死信列表 200")
    void adminCanListDeadLetters() throws Exception {
        mockMvc.perform(get("/api/mq/dead-letters")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("AGENT / EMPLOYEE 没有 mq:manage → GET 死信列表 40300")
    void nonAdminCannotListDeadLetters() throws Exception {
        for (Long userId : List.of(AGENT_ID, EMPLOYEE_ID)) {
            mockMvc.perform(get("/api/mq/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, tokenOf(userId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));
        }
    }

    @Test
    @DisplayName("AGENT 无 mq:manage → POST 重投也返回 40300（不仅是 GET 受保护）")
    void nonAdminCannotRequeue() throws Exception {
        mockMvc.perform(post("/api/mq/dead-letters/1/requeue")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(AGENT_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
