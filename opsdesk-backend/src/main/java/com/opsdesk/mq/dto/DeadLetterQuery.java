package com.opsdesk.mq.dto;

import com.opsdesk.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 死信列表查询入参（API 文档 §15.1）
 *
 * <pre>
 * GET /api/mq/dead-letters?page=1&amp;size=10&amp;queue=
 * </pre>
 *
 * <p>分页继承 {@link PageQuery}（{@code page} 从 1 开始、{@code size} 上限 50）。
 *
 * <p>⚠️ {@code @EqualsAndHashCode(callSuper = true)} 不能省：{@link PageQuery} 的
 * {@code page} / {@code size} 也要参与比较（与 {@code AuditLogQuery} 同一约定）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeadLetterQuery extends PageQuery {

    /**
     * 队列名（可选）。留空 = 查全部死信队列。
     *
     * <p>取值必须是本项目声明的 5 个 {@code *.dlq} 之一 —— 传别的值会 40001，
     * 而不是「静默返回空列表」：队列名写错属于调用方 bug，静默空结果会让人以为「死信队列是空的」。
     */
    private String queue;
}
