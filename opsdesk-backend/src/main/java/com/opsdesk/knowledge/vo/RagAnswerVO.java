package com.opsdesk.knowledge.vo;

import lombok.Data;

import java.util.List;

/**
 * RAG 语义问答结果（工单 D12-04，API §10.6 / 规格 §12.2「回答必须带引用」）
 *
 * <p>规格原话：<b>「无引用的 RAG 不可信、不可验收」</b>。所以 {@code references} 是契约的一部分，
 * 不是"顺手带的附加信息" —— 前端必须把它们渲染出来，用户才能自己核对答案有没有根据。
 */
@Data
public class RagAnswerVO {

    /** 模型基于召回资料生成的答案 */
    private String answer;

    /**
     * 引用列表（召回即为非空；一条都没召到时为空数组，此时 {@code answer} 会明确说明"未找到相关资料"）。
     *
     * <p>⚠️ 顺序的含义分两种情况：
     * <ul>
     *   <li>语义检索：<b>按相似度降序</b>，第一条最相关；</li>
     *   <li>降级为关键词检索：<b>顺序没有相关性含义</b>（按 documentId 排的），此时 {@code score} 全为 null，
     *       前端不要把第一条当成"最相关"。</li>
     * </ul>
     */
    private List<Reference> references;

    /**
     * 一条引用。
     *
     * <p>字段与 §10.6 的响应示例一致。
     */
    @Data
    public static class Reference {

        /** 文档 id（前端可据此跳转到文档详情） */
        private Long documentId;

        /** 文档标题 —— §12.2 明确要求引用必须带上它 */
        private String documentTitle;

        /** 片段序号 */
        private Integer chunkIndex;

        /**
         * 向量相似度（`COSINE`，越大越相似）。
         *
         * <p>⚠️ <b>降级为关键词检索时为 {@code null}</b>：关键词是字面命中，没有"相似度"可言，
         * 编一个数字出来（比如都填 1.0）会让前端误以为这是真实的语义相关度。
         * 前端遇到 null 就按"关键词命中"展示。
         */
        private Double score;

        /**
         * 片段摘要（chunk 开头一段）。
         *
         * <p>⚠️ 与关键词检索的 snippet 不同：向量命中的片段<b>不一定字面包含问题里的词</b>
         * （语义相近即可），所以这里取头部，不保证含关键词。
         */
        private String snippet;
    }
}
