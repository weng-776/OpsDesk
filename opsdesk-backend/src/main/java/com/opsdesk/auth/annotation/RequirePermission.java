package com.opsdesk.auth.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需的权限码（工单 D2-02，规格基线 §23.1「接口权限：注解 + 拦截器」）
 *
 * <p>用法：
 * <pre>{@code
 * @RequirePermission(PermissionCodes.USER_LIST)
 * @GetMapping
 * public Result<PageResult<UserVO>> page(@Valid UserQuery query) { ... }
 * }</pre>
 *
 * <p>由 {@code PermissionInterceptor}（order 1）在 {@code AuthInterceptor}（order 0）之后校验：
 * <ul>
 *   <li>未登录 → 40100（order 0 就抛了，order 1 根本不执行）</li>
 *   <li>已登录但 {@code UserContext.getPermissions()} 不含该码 → 40300</li>
 * </ul>
 *
 * <p><b>只做方法级</b>（{@link ElementType#METHOD}）：本项目 14 个受控接口各自需要的权限码
 * 都不相同，类级默认值没有使用点 —— 加了就是没有调用方的投机设计。
 *
 * <p><b>权限码请引用 {@code PermissionCodes} 常量</b>，不要写裸字符串（SOP §6）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 所需权限码，取值见规格基线 §3.6 */
    String value();
}
