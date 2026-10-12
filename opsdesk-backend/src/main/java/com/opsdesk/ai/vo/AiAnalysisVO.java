package com.opsdesk.ai.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opsdesk.common.enums.AiAnalysisStatus;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 分析结果 VO（API 文档 §16.12）
 *
 * <p>字段：{@code id} / {@code ticketId} / {@code model} / {@code type} / {@code category} /
 * {@code priority} / {@code summary} / {@code suggestions} / {@code status} / {@code createdAt}。
 *
 * <h2>🔴 刻意<b>不含</b> {@code rawResponse}</h2>
 * §16.12 的原文是「**不返回** {@code rawResponse}（模型原始返回仅入库用于排查）」。
 * 模型原始返回可能很长（含大量解释文字），且对前端毫无用处 —— 只应该留在库里供排查。
 *
 * <p>⚠️ 这一条由 {@code TicketAnalyzeServiceTest} 用反射断言字段清单钉住：
 * 以后谁「顺手」把 {@code rawResponse} 加进 VO，测试立刻红。
 *
 * <p>⚠️ 时间字段的 {@link JsonFormat} 不是多余的：项目没有全局 JSR-310 序列化配置，
 * 而 §2.6 要求统一 {@code yyyy-MM-dd HH:mm:ss}（与 {@code SlaPolicyVO} 同一约定）。
 */
@Data
public class AiAnalysisVO {

    /** 记录 ID */
    private Long id;

    /** 工单 ID */
    private Long ticketId;

    /** 模型标识（取自响应元数据；拿不到时为 null） */
    private String model;

    /** 建议类型（String：entity 的 {@code type} 列就是 VARCHAR，且可能为空） */
    private String type;

    /** 建议分类（回退后必在 §3 白名单内） */
    private TicketCategory category;

    /** 建议优先级（回退后必在 §3 白名单内） */
    private TicketPriority priority;

    /** 问题摘要 */
    private String summary;

    /** 处理建议（入库时是 JSON 列，出参还原成数组） */
    private List<String> suggestions;

    /** 状态：SUCCESS / FAILED */
    private AiAnalysisStatus status;

    /** 生成时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
