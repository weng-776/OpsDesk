package com.opsdesk.common;

import com.opsdesk.common.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UserContext 单元测试（规格基线 §23.2）
 *
 * <p>重点验证两件容易出事的事：<b>未登录时的安全默认值</b>、<b>ThreadLocal 不串线程</b>。
 */
class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 未登录 ====================

    @Test
    void 未登录时读取安全且不抛异常() {
        assertThat(UserContext.isLogin()).isFalse();
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getJti()).isNull();
        assertThat(UserContext.getRoles()).as("返回空集合而不是 null").isEmpty();
        assertThat(UserContext.getPermissions()).isEmpty();
        assertThat(UserContext.hasRole(Role.ADMIN)).isFalse();
        assertThat(UserContext.hasPermission("ticket:list")).isFalse();
        assertThat(UserContext.isAdmin()).isFalse();
    }

    @Test
    void requireUserId未登录时抛40100() {
        assertThatThrownBy(UserContext::requireUserId)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    // ==================== 正常路径 ====================

    @Test
    void 设置后可正确读取角色与权限() {
        UserContext.set(new UserContext.CurrentUser(
                7L, "jti-abc",
                Set.of(Role.AGENT),
                Set.of("ticket:list", "ticket:process")));

        assertThat(UserContext.isLogin()).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(7L);
        assertThat(UserContext.getJti()).isEqualTo("jti-abc");
        assertThat(UserContext.requireUserId()).isEqualTo(7L);
        assertThat(UserContext.hasRole(Role.AGENT)).isTrue();
        assertThat(UserContext.hasRole(Role.ADMIN)).isFalse();
        assertThat(UserContext.hasPermission("ticket:process")).isTrue();
        assertThat(UserContext.hasPermission("user:delete")).as("没给的权限不能有").isFalse();
        assertThat(UserContext.isAdmin()).isFalse();
    }

    @Test
    void 管理员判定() {
        UserContext.set(new UserContext.CurrentUser(1L, "jti", Set.of(Role.ADMIN), Set.of()));
        assertThat(UserContext.isAdmin()).isTrue();
        assertThat(UserContext.hasRole(Role.ADMIN)).isTrue();
    }

    @Test
    void 角色与权限为null时归一成空集合() {
        UserContext.set(new UserContext.CurrentUser(1L, "jti", null, null));
        assertThat(UserContext.getRoles()).isEmpty();
        assertThat(UserContext.getPermissions()).isEmpty();
        assertThat(UserContext.hasPermission(null)).as("null 权限码不能误判为有权限").isFalse();
        assertThat(UserContext.hasRole(null)).isFalse();
    }

    @Test
    void clear后回到未登录() {
        UserContext.set(new UserContext.CurrentUser(1L, "jti", Set.of(Role.ADMIN), Set.of("x")));
        assertThat(UserContext.isLogin()).isTrue();

        UserContext.clear();

        assertThat(UserContext.isLogin()).isFalse();
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.isAdmin()).isFalse();
    }

    // ==================== 线程隔离 ====================

    @Test
    void ThreadLocal不跨线程泄漏() throws Exception {
        UserContext.set(new UserContext.CurrentUser(9L, "jti-main", Set.of(Role.ADMIN), Set.of()));

        AtomicReference<Long> otherThreadUserId = new AtomicReference<>(-1L);
        Thread other = new Thread(() -> otherThreadUserId.set(UserContext.getUserId()));
        other.start();
        other.join();

        assertThat(otherThreadUserId.get()).as("新线程读不到主线程的身份").isNull();
        assertThat(UserContext.getUserId()).as("主线程不受影响").isEqualTo(9L);
    }
}
