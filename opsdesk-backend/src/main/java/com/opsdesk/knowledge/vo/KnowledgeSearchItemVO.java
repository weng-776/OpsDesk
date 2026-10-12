package com.opsdesk.knowledge.vo;

import lombok.Data;

/**
 * 关键词检索结果项（工单 D12-04，API §10.5 / §16.14）
 *
 * <p>字段与 §16.14 的表格一一对应，不多不少。
 *
 * <h2>⚠️ 为什么这里没有 {@code score}</h2>
 * 关键词检索是 {@code LIKE} 字面命中，<b>不存在"相似度"</b> —— 硬塞一个分数只会误导前端
 * （该按什么排序？）。语义检索的相似度在 {@link RagAnswerVO.Reference#getScore()} 里。
 * 这也是 §12.4 要求「两个接口分离」的一个具体体现：两者连"命中"的含义都不同。
 */
@Data
public class KnowledgeSearchItemVO {

    /** 命中的文档 id */
    private Long documentId;

    /** 文档标题 */
    private String documentTitle;

    /** 命中的片段序号（从 0 开始） */
    private Integer chunkIndex;

    /**
     * 命中片段 —— <b>已截取成"含关键词的窗口"</b>（不是 chunk 全文，chunk 有 500 字，
     * 列表全量返回会让响应过大）。
     *
     * <p>高亮由<b>前端</b>做：后端只保证 snippet 里含关键词，不往文本里插 HTML 标记
     * （那是表现层的事，插标记还会给 XSS 开口子）。
     *
     * <p>若关键词只命中了<b>标题</b>（正文没提到），snippet 会是 chunk 的开头一段、<b>不含</b>关键词 ——
     * 这不是 bug，而是"标题命中、正文无关"这个事实本身。
     */
    private String snippet;
}
