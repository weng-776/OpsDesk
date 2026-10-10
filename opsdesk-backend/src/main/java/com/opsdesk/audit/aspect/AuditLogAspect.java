package com.opsdesk.audit.aspect;

import com.opsdesk.audit.annotation.AuditLog;
import com.opsdesk.audit.service.AuditRecordService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 审计切面（工单 D6-01，规格基线 §14.3）
 *
 * <h2>执行顺序（<b>这是本类最关键的一点</b>）</h2>
 * <pre>
 * 事务拦截器（order = 0，最外层）
 *   └─ 本切面（order = LOWEST_PRECEDENCE，在事务<b>里面</b>）
 *        ├─ ① 执行前：查 before 快照
 *        ├─ ② proceed()：业务方法（含它自己的 SQL）
 *        ├─ ③ 执行后：查 after 快照
 *        └─ ④ 写 audit_log
 * </pre>
 * Spring 的 {@code TransactionInterceptor} 默认 order 是 {@code LOWEST_PRECEDENCE}，
 * 与不加 {@code @Order} 的 {@code @Aspect} <b>相同</b> → 相对顺序未定义。
 * 一旦本切面落到事务外面，第 ④ 步就成了独立提交：业务回滚时审计会残留，
 * 且会记下「看起来成功了」的快照 —— 直接违反 §14.3 的「与业务同事务同步写」。
 * 所以 {@code OpsDeskApplication} 上用 {@code @EnableTransactionManagement(order = 0)}
 * 把事务提到最外层，本类再用 {@link Order} 明确待在它里面。
 *
 * <h2>⚠️ 业务抛异常时：一行审计都不写</h2>
 * 同事务下写了也会被回滚，但<b>不写更明确</b>；更重要的是 {@code after_data} 的定义是
 * 「<b>变更后</b>快照」—— 操作失败了就没有「变更后」，硬写一个快照就是错的。
 * 异常原样抛出，不改写、不包装。
 *
 * <h2>⚠️ 自调用不生效</h2>
 * 同类内部 {@code this.xxx()} 绕过代理，注解不会被织入。本项目的流转都是
 * Controller → Service 的外部调用，不受影响；但后续若要加埋点，注意这一点。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditLogAspect {

    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();

    private final AuditRecordService auditRecordService;

    public AuditLogAspect(AuditRecordService auditRecordService) {
        this.auditRecordService = auditRecordService;
    }

    /**
     * 环绕通知：只在带 {@link AuditLog} 的方法上生效。
     *
     * <p>用 {@code @annotation(auditLog)} 的绑定写法直接拿到注解实例，
     * 不必再按方法反射查一遍。
     */
    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        Long resourceId = resolveResourceId(joinPoint, auditLog.resourceId());
        String beforeData = auditRecordService.snapshot(auditLog.resourceType(), resourceId);

        Object result;
        try {
            result = joinPoint.proceed();
        }
        catch (Throwable ex) {
            // ⚠️ 业务失败 → 不写审计（见类注释）。异常原样抛出，不改写。
            log.debug("[审计] 业务方法抛异常，跳过审计。operation={} resourceType={} resourceId={}",
                    auditLog.operation(), auditLog.resourceType(), resourceId, ex);
            throw ex;
        }

        String afterData = auditRecordService.snapshot(auditLog.resourceType(), resourceId);
        auditRecordService.record(auditLog.operation(), auditLog.resourceType(), resourceId,
                beforeData, afterData);
        return result;
    }

    /**
     * 用 SpEL 从方法参数解析 {@code resourceId}。
     *
     * <p>用 {@link SimpleEvaluationContext} 而不是 {@code StandardEvaluationContext}：
     * 表达式是开发者写在注解里的（不是用户输入），本没有注入面，但前者能顺手挡掉
     * {@code T(java.lang.Runtime)} 与任意方法调用 —— 零成本多一层保险。
     * 代价只是属性访问要写 {@code #dto.ticketId} 而不是 {@code #dto.getTicketId()}。
     *
     * <p>同时注册<b>参数名</b>与<b>位置</b>（{@code #p0} / {@code #a0}）两种变量：
     * 参数名依赖编译期 {@code -parameters}（Boot 父 POM 默认开），位置写法永远可用。
     *
     * <p>解析失败只记 warn 并返回 {@code null}（before/after 留空）——
     * 表达式写错是开发期问题，不该让线上的业务操作失败。
     */
    private Long resolveResourceId(ProceedingJoinPoint joinPoint, String expression) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        try {
            Object[] args = joinPoint.getArgs();
            SimpleEvaluationContext context = SimpleEvaluationContext
                    .forReadOnlyDataBinding()
                    .build();

            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
            for (int i = 0; i < args.length; i++) {
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
                if (parameterNames != null && i < parameterNames.length) {
                    context.setVariable(parameterNames[i], args[i]);
                }
            }

            Object value = SPEL_PARSER.parseExpression(expression).getValue(context);
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            return Long.valueOf(value.toString());
        }
        catch (Exception ex) {
            log.warn("[审计] resourceId 表达式解析失败，before/after 留空。expression={}", expression, ex);
            return null;
        }
    }
}
