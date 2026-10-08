package com.opsdesk.ticket.dto;

import com.opsdesk.common.PageQuery;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 工单列表查询入参（API 文档 §8.3；「我的工单」§8.2 也复用它）
 *
 * <p>{@code page} / {@code size} 继承 {@link PageQuery} —— 基类已把 {@code page} 收敛到 ≥1、
 * {@code size} 收敛到 [1, 50]（§20.1 硬约束），所以 {@code size=999} 会被静默收敛成 50。
 *
 * <p>⚠️ §8.2「我的工单」的签名里只列了 {@code status}，这里给它的是<b>超集</b>（多几个筛选参数）——
 * 加可选参数是向后兼容的，前端不传就等价于原签名。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TicketQuery extends PageQuery {

    /** 状态筛选（§3.5） */
    private TicketStatus status;

    /** 优先级筛选（§3.4） */
    private TicketPriority priority;

    /** 分类筛选（§3.3） */
    private TicketCategory category;

    /** 类型筛选（§3.2） */
    private TicketType type;

    /** 关键字：匹配 {@code ticket_no} / {@code title} */
    private String keyword;

    /**
     * 处理人筛选。
     *
     * <p>它是<b>叠加在数据范围之上的额外条件</b>，不是数据范围的替代 ——
     * 数据范围永远由 {@code TicketDataScopeHelper} 追加，客户端传什么都越不过它。
     * <p>（API 文档 §8.3 表格里那句「AGENT 默认只看自己」与规格基线 §8.3 的权威定义冲突，
     * 按 SSOT 以规格基线为准：不传该参数时不额外过滤。）
     */
    private Long assigneeId;

    /** 创建时间下限（含），格式 {@code yyyy-MM-dd HH:mm:ss}（§2.6） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 创建时间上限（含），格式 {@code yyyy-MM-dd HH:mm:ss}（§2.6） */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /**
     * 排序，取值见 API 文档 §8.3：{@code createdAt,desc}（默认）/ {@code priority,asc}。
     *
     * <p>⚠️ <b>必须白名单校验</b>（SOP §5 红区「动态排序 / 动态列：必须白名单校验，
     * 不能直接拼」）—— 客户端传的是列名，直接拼就是注入。非白名单值 → 40001。
     */
    private String sort;
}
