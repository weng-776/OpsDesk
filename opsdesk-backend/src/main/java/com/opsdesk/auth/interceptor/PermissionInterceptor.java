package com.opsdesk.auth.interceptor;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限码校验拦截器（工单 D2-02，SOP §5 红区）
 *
 * <p>规格依据：规格基线 §23.1（接口权限：注解 + 拦截器）、§3.6（权限码）、§22（API 与权限对应表）、
 * API 文档 §2.4（错误码 40300）。
 *
 * <h2>为什么是独立拦截器，而不是塞进 {@code AuthInterceptor}</h2>
 * <ul>
 *   <li><b>认证与授权分开</b>：认证（你是谁）在 order 0，授权（你能不能做）在 order 1。
 *       40100 在 order 0 抛出 → 本拦截器<b>根本不会执行</b>，
 *       于是「未登录 40100」与「无权限 40300」<b>天然不会混淆</b>（工单要点），不用写额外判断</li>
 *   <li>{@code AuthInterceptor} 是 D1-02 的红区文件，能不动就不动</li>
 * </ul>
 * 注册位置见 {@code AuthWebMvcConfig}（`order(0)` 认证 → `order(1)` 授权）。
 *
 * <h2>默认放行（有意的取舍）</h2>
 * 只有标了 {@link RequirePermission} 的方法才校验。也就是说：
 * <b>新接口忘了加注解 = 静默对所有已登录用户开放</b>，没有编译期或运行期提示。
 * <p>这是按工单字面实现的（工单只要求「给用户 / 部门 / 角色接口补上注解」），
 * 代价是<b>新增受控接口时必须记得加注解</b> —— 建议把「接口是否该加 @RequirePermission」
 * 写进 code review 检查项。要改成「默认拒绝」需另行设计（得给「任意登录用户可访问」的接口
 * 加显式标记，且要补齐现有未标注接口），不在本工单范围。
 *
 * <h2>本类不做的事</h2>
 * <b>不做数据范围校验</b>（§8 的 40301）—— §22 结尾明确「最终实现以 §8 数据范围校验为准，
 * 不是简单的角色判断」。数据范围是 D2-04 的 {@code TicketDataScopeHelper}：
 * 本类只回答「你有没有这个权限码」，不回答「这条数据归不归你」。
 */
@Slf4j
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 非 Controller 方法（静态资源兜底 handler、/error 等）没有注解可言 → 放行。
        // 注意：能走到这里说明已通过 order 0 的登录校验
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequirePermission required = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            return true;
        }

        String permissionCode = required.value();
        if (!StringUtils.hasText(permissionCode)) {
            // @RequirePermission("") 是手误。按「未声明」放行，而不是把接口锁死到没人能访问 ——
            // 一个空字符串把线上接口变 403 更难排查
            log.warn("[权限校验] @RequirePermission 的值为空，按未声明处理并放行。{}#{}",
                    handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName());
            return true;
        }

        // 正常不会走到：order 0 的 AuthInterceptor 已保证登录。
        // 真到了这里说明两个拦截器的注册配置被改坏了 —— 按「未登录」处理比按「无权限」更准确，
        // 否则会报出误导人的 40300
        if (!UserContext.isLogin()) {
            log.warn("[权限校验] UserContext 为空但请求已到达授权阶段，拦截器注册顺序可能被改坏。{} {}",
                    request.getMethod(), request.getRequestURI());
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        if (!UserContext.hasPermission(permissionCode)) {
            // 权限码只进日志，不进响应 —— 不把权限模型泄露给本来就无权的调用方
            log.warn("[权限校验] 拒绝：userId={} 缺少权限 [{}] | {} {}",
                    UserContext.getUserId(), permissionCode, request.getMethod(), request.getRequestURI());
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        return true;
    }
}
