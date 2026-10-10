package com.opsdesk.audit.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志出参（API 文档 §16.16）
 *
 * <p>字段与 §16.16 逐项对齐（10 个）。{@code operation} / {@code resourceType} 用枚举 ——
 * Jackson 序列化成枚举名（{@code "TICKET_ASSIGN"} / {@code "TICKET"}），与 §16.16 的
 * 「string」一致，且和项目其它 VO 的做法统一。
 *
 * <h2>⚠️ {@code beforeData} / {@code afterData} 是 {@code object} 不是 string</h2>
 * §16.16 把这两个字段标成 <b>object</b> —— 也就是说响应里应当是**真正的 JSON 对象**，
 * 而不是把库里那段 JSON 文本再当成字符串塞回去（那会变成一层转义，前端要
 * {@code JSON.parse} 两次）。所以这里用 {@link JsonNode}，由 Service 在装配时解析。
 *
 * <p>库里存的是文本（{@code TEXT}），解析失败时降级为 {@code null} 并记 warn ——
 * 审计查询不该因为一条脏数据整页失败。
 *
 * <p>⚠️ 时间字段上的 {@link JsonFormat} 不是多余的：项目没有全局 JSR-310 序列化配置
 * （{@code spring.jackson.date-format} 只管 {@code java.util.Date}），而 §2.6 要求
 * 统一 {@code yyyy-MM-dd HH:mm:ss}。
 */
@Data
public class AuditLogVO {

    /** 记录 ID */
    private Long id;

    /** 操作人 ID；{@code 0} 表示系统操作（无登录上下文，见 {@code AuditRecordService.SYSTEM_USER_ID}） */
    private Long userId;

    /** 操作人姓名；系统操作时为 {@code "系统"}，用户已删除时为 {@code null} */
    private String userName;

    /** 操作码（§3.11） */
    private AuditOperation operation;

    /** 资源类型（§3.12） */
    private AuditResourceType resourceType;

    /** 资源 ID；可为 {@code null} */
    private Long resourceId;

    /** 变更前快照（JSON 对象）；无快照时为 {@code null} */
    private JsonNode beforeData;

    /** 变更后快照（JSON 对象）；无快照时为 {@code null} */
    private JsonNode afterData;

    /** 来源 IP */
    private String ip;

    /** 客户端 UA */
    private String userAgent;

    /** 记录时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}
