package com.opsdesk.auth.interceptor;

import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.JwtHelper;
import com.opsdesk.common.TraceIdFilter;
import com.opsdesk.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 鉴权拦截器（工单 D1-02，SOP §5 红区）
 *
 * <p>职责：所有 {@code /api/**}（白名单除外）请求校验 {@code Authorization: Bearer <token>}，
 * 把身份落进 {@link UserContext}；登出黑名单在 {@link #TOKEN_BLACKLIST_PREFIX}。
 *
 * <p>规格依据：规格基线 §23.2（每次请求读 Redis 黑名单 {@code auth:token:{jti}}）、
 * §23.6（userId 进 MDC）、API 文档 §2.2（鉴权）。
 *
 * <h2>本类唯一需要理解的一件事：preHandle 里的语句顺序</h2>
 *
 * <pre>
 *   ① 取 Bearer token              ┐
 *   ② JwtHelper.parse              │ 全部可能抛异常
 *   ③ Redis 黑名单查询              │ → 必须排在 UserContext.set 之前
 *   ④ 加载 roles / permissions      ┘
 *   ────────── 危险分界线：以上任一失败 → 40100，此时尚未 set ──────────
 *   ⑤ UserContext.set(...)
 *   ⑥ MDC.put(USER_ID, userId)     ← 不抛异常
 *   ⑦ return true
 * </pre>
 *
 * <p><b>为什么 set 必须是最后一步</b> —— 已反编译 {@code spring-webmvc} 6.2.19 核实：
 * <ul>
 *   <li>{@code HandlerExecutionChain#applyPreHandle} 里
 *       {@code if (!interceptor.preHandle(...))} 这一行<b>没有 try-catch</b>，
 *       preHandle 抛出的异常直接往上冒；而 {@code this.interceptorIndex = i}
 *       只在 preHandle <b>正常返回 true 之后</b>才执行。</li>
 *   <li>{@code HandlerExecutionChain#triggerAfterCompletion} 是
 *       {@code for (int i = interceptorIndex; i >= 0; i--)}。</li>
 * </ul>
 * 本拦截器是链上第 0 个。若它的 preHandle 抛异常，{@code interceptorIndex} 仍是 -1，
 * 循环体一次都不进 —— <b>我们自己的 {@link #afterCompletion} 不会被调用</b>。
 * 因此 set 之后不得再有任何可能抛异常的语句，三条路径才都安全：
 * <table border="1">
 *   <caption>路径安全性</caption>
 *   <tr><th>路径</th><th>结果</th></tr>
 *   <tr><td>①~④ 抛（set 之前）</td><td>没 set → 无残留；异常转 40100</td></tr>
 *   <tr><td>正常返回 true</td><td>interceptorIndex = 0 → afterCompletion 被调 → clear</td></tr>
 *   <tr><td>返回 false</td><td>set 在 return false 之后，走不到 → 无残留</td></tr>
 * </table>
 *
 * <p><b>MDC 绝对不要 clear</b>：{@code TraceIdFilter} 的 {@code finally} 要读
 * {@code MDC.get(USER_ID)} 打访问日志，读完才 clear。本拦截器的 afterCompletion
 * 在它之前执行，只清 ThreadLocal、不动 MDC，访问日志里才有 userId。
 *
 * <p><b>本类不做的事</b>（留给后续工单，别在这里补）：
 * <ul>
 *   <li>权限码校验 —— D2-02 的 {@code @RequirePermission} + 权限拦截器。
 *       本单只判「是否登录」，不判「是否有权限」。</li>
 *   <li>权限的 Redis 缓存 —— D2-01 的 {@code PermissionLoader}。</li>
 *   <li>用户 {@code status} 的实时校验 —— 工单未要求。⚠️ 已知缺口：
 *       用户在登录后被禁用，其存量 token 在本单实现下仍可用到过期
 *       （D1-03 改用户状态时需要配合 evict / 写黑名单来兜）。</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 登出黑名单 key 前缀（规格基线 §23.4：{@code auth:token:{jti}}） */
    public static final String TOKEN_BLACKLIST_PREFIX = "auth:token:";

    /** 黑名单 value —— 只判 key 是否存在，值本身无意义 */
    public static final String TOKEN_BLACKLIST_VALUE = "1";

    private final BearerTokenResolver tokenResolver;
    private final JwtHelper jwtHelper;
    private final StringRedisTemplate redisTemplate;
    private final PermissionLoader permissionLoader;

    public AuthInterceptor(BearerTokenResolver tokenResolver,
                           JwtHelper jwtHelper,
                           StringRedisTemplate redisTemplate,
                           PermissionLoader permissionLoader) {
        this.tokenResolver = tokenResolver;
        this.jwtHelper = jwtHelper;
        this.redisTemplate = redisTemplate;
        this.permissionLoader = permissionLoader;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 防线二：不依赖上面「set 必须是最后一步」的推导，先把本线程可能残留的身份清掉。
        // Tomcat 线程是复用的，上一次请求若在 set 之后抛了异常就会留脏数据。
        UserContext.clear();

        // ① 取 Bearer token
        String token = tokenResolver.resolve(request);
        if (token == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        // ② 解析并校验签名 / 有效期
        JwtHelper.JwtPayload payload = jwtHelper.parse(token);
        if (payload == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        // ③ 登出黑名单。jti 缺失的 token 无法被吊销，直接拒绝（fail-closed）
        if (!StringUtils.hasText(payload.jti())) {
            log.debug("[鉴权] token 缺少 jti，无法校验黑名单，拒绝");
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + payload.jti()))) {
            log.debug("[鉴权] token 已登出。jti={}", payload.jti());
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        // ④ 加载角色与权限 —— D2-01 起走 Redis 缓存 auth:perms:{userId}（TTL 10 分钟），
        //    未命中才回源查库。命中时这里 0 条 SQL
        UserAuthContextLoader.UserAuthContext authContext = permissionLoader.load(payload.userId());

        // ==================== 危险分界线：以下两句不得失败，之后不得再插任何语句 ====================
        // ⑤ 落身份（必须是本方法最后一件「有语义」的事，理由见类注释）
        UserContext.set(new UserContext.CurrentUser(
                payload.userId(), payload.jti(), authContext.roles(), authContext.permissions()));

        // ⑥ userId 进 MDC，供 TraceIdFilter 的访问日志使用。MDC 生命周期归 TraceIdFilter，这里只 put
        MDC.put(TraceIdFilter.USER_ID, String.valueOf(payload.userId()));
        // ======================================================================================

        return true;
    }

    /**
     * 请求结束清理 {@link UserContext}。
     *
     * <p><b>唯一一条语句</b>，且<b>绝不</b> {@code MDC.clear()}（见类注释）。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        UserContext.clear();
    }
}
