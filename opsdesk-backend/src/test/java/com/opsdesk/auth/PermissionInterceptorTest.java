package com.opsdesk.auth;

import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.JwtHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @RequirePermission} 权限校验验收测试（工单 D2-02）
 *
 * <p>规格依据：规格基线 §23.1（注解 + 拦截器）、§22（API 与权限对应表）、
 * §25.3 用例 #3（EMPLOYEE 调 {@code GET /api/users} → 40300）、API 文档 §2.4。
 *
 * <h2>本类要钉死的三件事</h2>
 * <ol>
 *   <li><b>40300</b>：已登录但角色没有该权限码（EMPLOYEE / AGENT 打用户、部门接口）</li>
 *   <li><b>40100 与 40300 必须分开</b>：无 token → 40100；有 token 但无权限 → 40300。
 *       这是靠「认证 order 0 → 授权 order 1」的顺序保证的，顺序写反就会全部变成 40300</li>
 *   <li><b>校验发生在 Controller 之前</b>：无权限的写请求不能产生任何副作用</li>
 * </ol>
 *
 * <p>token 直接用 {@link JwtHelper} 签发，不走登录接口 —— 免去在测试里写演示口令。
 * 三个角色分别取种子账号：admin(id=1, ADMIN) / agent_zhang(id=2, AGENT) / emp_wang(id=4, EMPLOYEE)。
 *
 * <p>不加 {@code @Transactional}：拦截器会给这三个用户写权限缓存，Redis 不参与事务，
 * 所以在 {@code @AfterEach} 里显式清；DB 侧本类只读，无副作用需要回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermissionInterceptorTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long AGENT_ID = 2L;
    private static final Long EMPLOYEE_ID = 4L;

    /** 本类用到的三个种子账号，跑完清掉它们的权限缓存 */
    private static final List<Long> TOUCHED_USERS = List.of(ADMIN_ID, AGENT_ID, EMPLOYEE_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtHelper jwtHelper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        TOUCHED_USERS.forEach(userId ->
                redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + userId));
    }

    private String tokenOf(Long userId) {
        return "Bearer " + jwtHelper.createToken(userId);
    }

    // ==================== 验收 1：EMPLOYEE → 40300，ADMIN → 200 ====================

    @Test
    @DisplayName("验收1：EMPLOYEE 调 GET /api/users → 403/40300（§25.3 用例 #3）")
    void employeeCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, tokenOf(EMPLOYEE_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("验收1：ADMIN 调同一个接口 → 200 通过")
    void adminCanListUsers() throws Exception {
        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("AGENT 也没有 user:* → 40300（不是「只有 EMPLOYEE 被拦」）")
    void agentCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, tokenOf(AGENT_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    // ==================== 验收 3：40100 与 40300 分开 ====================

    @Test
    @DisplayName("验收3：无 token 调同一接口 → 401/40100（与 40300 区分开）")
    void noTokenIsUnauthorizedNotForbidden() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("验收3：40100 与 40300 是两条不同的路径（同一接口两种结果）")
    void unauthorizedAndForbiddenAreDistinct() throws Exception {
        int noToken = mockMvc.perform(get("/api/users")).andReturn().getResponse().getStatus();
        int noPermission = mockMvc.perform(get("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMPLOYEE_ID)))
                .andReturn().getResponse().getStatus();

        assertThat(noToken).as("未登录").isEqualTo(401);
        assertThat(noPermission).as("已登录但无权限").isEqualTo(403);
    }

    // ==================== 部门接口 ====================

    @Test
    @DisplayName("AGENT 调 GET /api/departments/tree → 40300；ADMIN → 200")
    void departmentTreeRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/departments/tree").header(HttpHeaders.AUTHORIZATION, tokenOf(AGENT_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(get("/api/departments/tree").header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================== 校验发生在 Controller 之前 ====================

    @Test
    @DisplayName("无权限的写请求不产生副作用（权限校验在 Controller 之前）")
    void forbiddenWriteHasNoSideEffect() throws Exception {
        Long before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE deleted = 0", Long.class);

        mockMvc.perform(post("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMPLOYEE_ID))
                        .contentType("application/json")
                        .content("{\"username\":\"probe_should_not_exist\",\"password\":\"probe123456\","
                                + "\"nickname\":\"不该被创建\",\"departmentId\":5,\"roleIds\":[1]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        Long after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE deleted = 0", Long.class);
        assertThat(after).as("被 40300 拦下的请求不能真的建出用户").isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE username = 'probe_should_not_exist'", Long.class))
                .as("探针用户不存在").isZero();
    }

    @Test
    @DisplayName("分配角色接口按 user:assign-role 校验，EMPLOYEE 被拒")
    void assignRoleRequiresPermission() throws Exception {
        mockMvc.perform(put("/api/users/5/roles")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMPLOYEE_ID))
                        .contentType("application/json")
                        .content("{\"roleIds\":[3]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    // ==================== 默认放行（有意的取舍） ====================

    @Test
    @DisplayName("未标注解的接口默认放行：三个角色都能调 /api/auth/me")
    void unannotatedEndpointIsOpenToAnyLoggedInUser() throws Exception {
        for (Long userId : TOUCHED_USERS) {
            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, tokenOf(userId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }
}
