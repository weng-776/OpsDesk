package com.opsdesk.notification.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opsdesk.common.enums.NotificationType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知出参（API 文档 §16.15）
 *
 * <p>字段与 §16.15 逐项对齐（8 个）。
 *
 * <h2>⚠️ {@code readFlag} 是 {@code boolean}，但实体里是 {@code Integer}</h2>
 * 库里 {@code read_flag} 是 {@code TINYINT}(0/1)，§16.15 要求出参是 <b>boolean</b> ——
 * 所以装配时要做一次 {@code readFlag != 0} 的转换，不能直接把 0/1 丢出去
 * （前端会拿到 {@code 0} 而不是 {@code false}，条件判断全是坑）。
 *
 * <p>⚠️ 时间字段上的 {@link JsonFormat} 不是多余的：项目没有全局 JSR-310 序列化配置
 * （{@code spring.jackson.date-format} 只管 {@code java.util.Date}），而 §2.6 要求
 * 统一 {@code yyyy-MM-dd HH:mm:ss}。
 */
@Data
public class NotificationVO {

    /** 通知 ID */
    private Long id;

    /** 类型（§3.10） */
    private NotificationType type;

    /** 标题 */
    private String title;

    /** 正文 */
    private String content;

    /** 业务类型：{@code TICKET} 等（前端据此决定跳到哪个页面） */
    private String bizType;

    /** 业务 ID（前端跳转用） */
    private Long bizId;

    /** 是否已读 */
    private boolean readFlag;

    /** 时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}
