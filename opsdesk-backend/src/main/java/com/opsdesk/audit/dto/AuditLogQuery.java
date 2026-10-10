package com.opsdesk.audit.dto;

import com.opsdesk.common.PageQuery;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 审计日志查询入参（API 文档 §13.1）
 *
 * <pre>
 * GET /api/audit-logs?page=1&amp;size=10&amp;userId=&amp;operation=&amp;resourceType=&amp;resourceId=&amp;startTime=&amp;endTime=
 * </pre>
 *
 * <p>分页继承 {@link PageQuery}（{@code page} 从 1 开始、{@code size} 上限 50）。
 *
 * <p>⚠️ {@code @EqualsAndHashCode(callSuper = true)} 不能省：{@link PageQuery} 的
 * {@code page} / {@code size} 也要参与比较，否则同 filters、不同页码的两个 query 会被判相等。
 *
 * <h2>时间参数</h2>
 * 用 {@link LocalDateTime} + {@code yyyy-MM-dd HH:mm:ss}（§2.6），**不做任何时区换算** ——
 * 库里存的就是本地墙钟时间，入参也是同一口径。换成 {@code java.util.Date} 或
 * {@code OffsetDateTime} 反而会引入时区偏移（验收 3 要的正是「不出现时区偏差」）。
 *
 * <p>⚠️ {@code endTime} 是**闭区间上界**（{@code <=}）。若前端只想筛「某一天」，
 * 要传 {@code 23:59:59} 而不是日期零点 —— 这是与工单列表一致的既有约定。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditLogQuery extends PageQuery {

    /** 操作人 ID（精确匹配） */
    private Long userId;

    /** 操作码（§3.11 白名单，精确匹配） */
    private AuditOperation operation;

    /** 资源类型（§3.12 白名单，精确匹配） */
    private AuditResourceType resourceType;

    /** 资源 ID（精确匹配） */
    private Long resourceId;

    /** 时间下限（含），格式 {@code yyyy-MM-dd HH:mm:ss} */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 时间上限（含），格式 {@code yyyy-MM-dd HH:mm:ss} */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
