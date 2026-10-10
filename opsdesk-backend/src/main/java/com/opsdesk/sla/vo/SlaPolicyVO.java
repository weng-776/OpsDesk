package com.opsdesk.sla.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opsdesk.common.enums.TicketPriority;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SLA 策略 VO（API 文档 §16.13）
 *
 * <p>§16.13 的 6 个字段：{@code id} / {@code priority} / {@code responseMinutes} /
 * {@code resolutionMinutes} / {@code status} / {@code effectiveFrom}。
 *
 * <p>⚠️ 不返回 Entity：{@code sla_policy} 上还有生成列 {@code active_priority}
 * （仅用于唯一索引约束，纯粹是数据库技巧）以及 {@code created_at} / {@code updated_at} ——
 * 都不是接口契约的一部分，不该泄露。
 *
 * <p>⚠️ 时间字段的 {@link JsonFormat} 不是多余的：项目没有全局 JSR-310 序列化配置，
 * 而 §2.6 要求统一 {@code yyyy-MM-dd HH:mm:ss}。
 */
@Data
public class SlaPolicyVO {

    /** 策略 ID（版本号就体现在这里 —— 改一次时限就多一个 id） */
    private Long id;

    /** 优先级：P1 / P2 / P3 */
    private TicketPriority priority;

    /** 响应时限（分钟） */
    private Integer responseMinutes;

    /** 解决时限（分钟） */
    private Integer resolutionMinutes;

    /** 状态：ACTIVE / INACTIVE（§9.1） */
    private String status;

    /** 生效时间（§9.7：新版本写入时为 now） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime effectiveFrom;
}
