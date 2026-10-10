package com.opsdesk.audit.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计用的请求上下文（工单 D6-01，规格基线 §14.3「ip / user_agent 从当前请求取」）
 *
 * <h2>⚠️ 非 Web 上下文必须安全降级，不能抛异常</h2>
 * {@link RequestContextHolder} 是 <b>ThreadLocal</b>，下面这些场景拿到的一定是 {@code null}：
 * <ul>
 *   <li>定时任务（D5-03 的 SLA 扫描）</li>
 *   <li>RabbitMQ 消费者（消息线程不是 Web 线程）</li>
 *   <li>手动 {@code new Thread()} / 未传播上下文的异步任务</li>
 *   <li>单元测试直接调 service</li>
 * </ul>
 * 这些场景下 {@code ip} / {@code user_agent} 留 {@code null} 是<b>正确</b>结果
 * （审计表这两列本来就 {@code DEFAULT NULL}），绝不能因为拿不到请求就抛异常 ——
 * 那会让「MQ 消费里的一次敏感操作」直接失败。
 *
 * <h2>⚠️ X-Forwarded-For 可以伪造</h2>
 * 它只是<b>审计辅助信息</b>，用来事后追查，<b>不作为任何安全判定依据</b>
 * （真正的安全边界是 JWT + 数据范围校验）。取值顺序：
 * {@code X-Forwarded-For} 第一段 → {@code X-Real-IP} → {@code getRemoteAddr()}。
 *
 * <p>长度按 DDL 截断：{@code ip} 是 {@code VARCHAR(45)}（IPv6 上限）、
 * {@code user_agent} 是 {@code VARCHAR(255)} —— <b>不截断的话长 UA 会让 insert 直接失败</b>。
 */
@Slf4j
@Component
public class AuditRequestContext {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";
    private static final String HEADER_USER_AGENT = "User-Agent";

    /** {@code audit_log.ip} 是 VARCHAR(45) */
    private static final int MAX_IP_LENGTH = 45;

    /** {@code audit_log.user_agent} 是 VARCHAR(255) */
    private static final int MAX_USER_AGENT_LENGTH = 255;

    /**
     * 当前请求的来源 IP。
     *
     * @return IP；非 Web 上下文或取不到时返回 {@code null}
     */
    public String currentIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String ip = firstNonBlank(forwardedForClient(request), request.getHeader(HEADER_REAL_IP),
                request.getRemoteAddr());
        return truncate(ip, MAX_IP_LENGTH);
    }

    /**
     * 当前请求的客户端 UA。
     *
     * @return UA（已按 {@code VARCHAR(255)} 截断）；非 Web 上下文或取不到时返回 {@code null}
     */
    public String currentUserAgent() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        return truncate(request.getHeader(HEADER_USER_AGENT), MAX_USER_AGENT_LENGTH);
    }

    // ==================== 私有 ====================

    /** 拿当前请求；非 Web 上下文返回 {@code null}（不抛） */
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    /**
     * {@code X-Forwarded-For} 是逗号分隔的链路：{@code client, proxy1, proxy2} ——
     * 取<b>第一段</b>（最靠近客户端的那个）。
     */
    private String forwardedForClient(HttpServletRequest request) {
        String header = request.getHeader(HEADER_FORWARDED_FOR);
        if (!StringUtils.hasText(header)) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).trim();
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    /** 按列宽截断；{@code null} 原样返回 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        log.debug("[审计] 请求上下文字段超长被截断：{} → {}", value.length(), maxLength);
        return value.substring(0, maxLength);
    }
}
