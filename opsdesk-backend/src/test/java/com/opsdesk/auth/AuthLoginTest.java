package com.opsdesk.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.auth.dto.LoginRequest;
import com.opsdesk.auth.service.AuthService;
import com.opsdesk.auth.vo.LoginVO;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录接口回归测试（工单 D1-01 的补充）
 *
 * <p>规格依据：API 文档 §4.1、规格基线 §4.1 / §23.1。
 *
 * <p><b>为什么补这一组</b>：D1-01 原本只有 curl 手工验收，<b>没有自动化用例</b>，
 * 结果「把密码塞进 WHERE 条件」这种致命改动没被任何测试挡住。
 * 这里把登录的 4 条主路径固定下来。
 *
 * <p>走真实 Redis / MySQL（不 mock）—— 登录同时依赖两者，mock 掉就验不出问题。
 */
@SpringBootTest
class AuthLoginTest {

    /** 用例会写 {@code login:fail:*} 计数，跑完必须清掉，否则会污染演示账号的锁定状态 */
    private static final String[] TOUCHED_USERNAMES = {"admin", "emp_wang", "no_such_user"};

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void clearLoginFailCounters() {
        for (String username : TOUCHED_USERNAMES) {
            redisTemplate.delete("login:fail:" + username);
        }
    }

    private static LoginRequest request(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    @Test
    @DisplayName("正确密码能登录：返回 token + 角色 + 权限")
    void 正确密码可以登录() {
        LoginVO vo = authService.login(request("admin", "123456"));

        assertThat(vo.getToken()).as("签发了 JWT").isNotBlank();
        assertThat(vo.getUser().getUsername()).isEqualTo("admin");
        assertThat(vo.getUser().getRoles()).contains("ADMIN");
        assertThat(vo.getUser().getPermissions()).as("admin 拥有全部权限").isNotEmpty();
        assertThat(vo.getUser().getDepartmentName()).isNotBlank();
    }

    @Test
    @DisplayName("密码错误 → 40100，且提示不区分「用户不存在」")
    void 密码错误返回40100() {
        assertThatThrownBy(() -> authService.login(request("admin", "wrong-password")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("用户名不存在 → 与密码错误同样的 40100（不暴露账号是否存在）")
    void 用户名不存在返回40100() {
        assertThatThrownBy(() -> authService.login(request("no_such_user", "123456")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("用户名前后空格被归一化（\"  admin  \" 也能登录）")
    void 用户名前后空格被归一化() {
        LoginVO vo = authService.login(request("  admin  ", "123456"));
        assertThat(vo.getUser().getUsername()).isEqualTo("admin");
    }

    @Test
    @Transactional
    @DisplayName("禁用账号即使密码正确也被拒 → 40100（§23.1）")
    void 禁用账号被拒() {
        User admin = userService.getOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));
        admin.setStatus(0);
        userService.updateById(admin);

        assertThatThrownBy(() -> authService.login(request("admin", "123456")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("禁用");
    }

    /**
     * 回归守卫：**BCrypt 是加盐的，encode 的结果不可复现**。
     *
     * <p>所以密码校验**只能**用 {@code matches(明文, 库中密文)}，
     * 绝不能写成 {@code eq(User::getPassword, passwordEncoder.encode(明文))} ——
     * 那样永远匹配不到，登录 100% 失败。
     */
    @Test
    @DisplayName("回归守卫：BCrypt 每次 encode 结果都不同，只能靠 matches 比对")
    void bcrypt编码不可复现() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(10);

        String first = encoder.encode("123456");
        String second = encoder.encode("123456");

        assertThat(first).as("同一明文两次 encode 结果不同（加了随机盐）").isNotEqualTo(second);
        assertThat(encoder.matches("123456", first)).isTrue();
        assertThat(encoder.matches("123456", second)).isTrue();
        assertThat(encoder.matches("654321", first)).as("错密码匹配失败").isFalse();
    }
}
