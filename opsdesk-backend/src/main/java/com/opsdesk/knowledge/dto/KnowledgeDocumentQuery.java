package com.opsdesk.knowledge.dto;

import com.opsdesk.common.PageQuery;
import com.opsdesk.common.enums.KnowledgeDocStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文档列表查询入参（API 文档 §10.1，补单 · 知识库文档列表）
 *
 * <pre>
 * GET /api/knowledge/documents?page=1&amp;size=10&amp;status=READY&amp;keyword=VPN
 * </pre>
 *
 * <p>{@code page} / {@code size} 继承 {@link PageQuery}：{@code page ≥ 1}、
 * {@code size ∈ [1,50]}，越界**静默收敛**（§20.1），所以 {@code size=999} 会被压成 50 ——
 * 这是"不报错但也不按你要的来"，前端别把它当失败处理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeDocumentQuery extends PageQuery {

    /**
     * 处理状态筛选（§3.12：{@code PROCESSING} / {@code READY} / {@code FAILED}）。
     *
     * <p>⚠️ 必须<b>用枚举</b>接收而不是 {@code String}：拿字符串在 service 里比，
     * 拼错了（比如 {@code ready}）就静默退化成「不过滤」，返回全量数据看起来又"像对的"；
     * 枚举绑不上会直接 {@code 40001}（{@code GlobalExceptionHandler#handleTypeMismatch}）。
     */
    private KnowledgeDocStatus status;

    /**
     * 关键字，同时匹配 {@code title} 与 {@code file_name}。
     *
     * <p>空串 / 全空格视为「不过滤」（不是 40001）。
     * <p>⚠️ 拼条件前必须走 {@code LikeEscaper.escape}，否则 {@code ?keyword=%} 会捞全表。
     */
    private String keyword;
}
