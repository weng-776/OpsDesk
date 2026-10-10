package com.opsdesk.audit.annotation;

import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（工单 D6-01，规格基线 §14.2 / §14.3）
 *
 * <p>标在<b>业务方法</b>上，由 {@code AuditLogAspect} 织入，自动写 {@code audit_log}。
 * §14.3 的原文形态：
 * <pre>
 * &#64;AuditLog(operation = AuditOperation.TICKET_ASSIGN, resourceType = "TICKET")
 * public void assign(Long ticketId, Long assigneeId) { ... }
 * </pre>
 *
 * <h2>⚠️ 必须标在【实现类】的方法上，不能标接口方法</h2>
 * Spring AOP 的 {@code @annotation} 切点匹配的是<b>被代理对象的方法</b>。
 * 项目用 CGLIB 代理（Boot 默认 {@code spring.aop.proxy-target-class=true}），
 * 标在接口方法上的注解在运行期匹配不稳定 —— 会「有时记有时不记」。
 *
 * <h2>⚠️ 加了注解但没织入？先查这两处</h2>
 * <ol>
 *   <li>{@code pom.xml} 有没有 {@code spring-boot-starter-aop}（没有的话连 {@code @Aspect} 都不认）；</li>
 *   <li>该方法是否被<b>同类内部调用</b>（{@code this.xxx()}）—— 那样绕过了代理，注解不会生效。
 *       本项目的流转都是 Controller → Service 的外部调用，不受影响。</li>
 * </ol>
 *
 * <h2>与 {@code ticket_history} 的分工</h2>
 * 见 §14.4：{@code ticket_history} 记工单业务过程（工单相关人可见），
 * {@code audit_log} 记系统关键操作（<b>仅 ADMIN 可见</b>）。两者可以同时记同一件事。
 *
 * <p>⚠️ <b>审计表不可篡改</b>（§14.3）：本项目<b>不提供</b>任何修改 / 删除审计日志的接口。
 * 新加审计相关代码时请勿开这个口子，也不要给审计自身的方法加本注解（会自指递归）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /**
     * 操作码（§3.11 白名单）。
     *
     * <p>⚠️ §3.11 的工单码里<b>没有</b> ACCEPT / START / HOLD / RESUME / RESOLVE / REJECT ——
     * 这些动作统一记 {@link AuditOperation#TICKET_STATUS_CHANGE}（它们确实都是「状态变更」）。
     * 不要为了"更精确"往枚举里加码：§3.11 是白名单 SSOT。
     */
    AuditOperation operation();

    /**
     * 资源类型（§3.12 白名单）。
     *
     * <p>⚠️ 这里用<b>枚举</b>而不是 §14.3 示例里的字符串 {@code "TICKET"} ——
     * 与实体字段 {@code AuditResourceType} 同类型，拼错在编译期就报，不必等运行期。
     */
    AuditResourceType resourceType();

    /**
     * 资源 id 的 SpEL 表达式，从方法参数解析，例如 {@code "#ticketId"}。
     *
     * <p>可用变量：参数名（{@code #ticketId}）与位置（{@code #p0} / {@code #a0}）两种写法都支持。
     * 用 {@code SimpleEvaluationContext} 求值 —— 挡掉 {@code T(...)} 与任意方法调用，
     * 所以属性访问要写 {@code #dto.ticketId} 而不是 {@code #dto.getTicketId()}。
     *
     * <p>留空（默认）表示<b>解析不出资源 id</b>：此时 {@code before_data} / {@code after_data}
     * 都为 {@code null}，只记操作码与操作人。
     * <p>⚠️ 表达式在方法<b>执行前</b>求值，所以「id 由方法内部生成」的创建类操作
     * （如 {@code TicketCreateService.create}）解析不出来 —— 那类需要 {@code #result} 支持，
     * 本单不做（见工单 D6-01 的范围）。
     */
    String resourceId() default "";
}
