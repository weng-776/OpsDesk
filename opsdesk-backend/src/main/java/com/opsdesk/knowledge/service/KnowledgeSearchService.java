package com.opsdesk.knowledge.service;

import com.opsdesk.common.PageQuery;
import com.opsdesk.common.PageResult;
import com.opsdesk.knowledge.vo.KnowledgeSearchItemVO;
import com.opsdesk.knowledge.vo.RagAnswerVO;

/**
 * 知识库检索（工单 D12-04，规格基线 §12.4）
 *
 * <h2>为什么是两个方法而不是一个</h2>
 * §12.4 明确「两者接口分离，避免语义混淆」：
 * <ul>
 *   <li>{@link #search} 是<b>字面</b>命中（MySQL {@code LIKE}），返回分页列表；</li>
 *   <li>{@link #ragQuery} 是<b>语义</b>召回（Milvus 向量）+ LLM 总结，返回答案与引用。</li>
 * </ul>
 * 两者的"命中"含义不同、返回结构不同、依赖也不同（后者还要 Milvus / Embedding / LLM），
 * 合并成一个接口会让调用方无法表达"我要哪种"。
 */
public interface KnowledgeSearchService {

    /**
     * 关键词检索（API §10.5）。
     *
     * <p>匹配范围是<b>片段正文 + 文档标题</b>，且只搜 {@code status = READY} 的文档
     * （{@code PROCESSING} 可能只有一半片段、{@code FAILED} 没有有效片段）。
     *
     * @param keyword   关键词；为空/全空白时返回空分页（不报错 —— 用户没输关键词不是错误）
     * @param pageQuery 分页参数（可为 null，走默认值；{@code size} 上限 50 由 {@link PageQuery} 收敛）
     */
    PageResult<KnowledgeSearchItemVO> search(String keyword, PageQuery pageQuery);

    /**
     * RAG 语义问答（API §10.6，规格 §12.2「回答必须带引用」）。
     *
     * <p>流程：{@code question → Embedding → Top-K → 上下文 → LLM → 带引用的答案}。
     *
     * <p><b>降级（§24.3）</b>：Milvus / Embedding 不可用时<b>内部降级为关键词检索，接口不变</b> ——
     * 仍然返回答案与引用，只是引用里 {@code score} 为 {@code null}、答案开头会说明降级。
     * 只有连 LLM 也不可用时才抛 {@code 50300}（没有 LLM 就产不出 {@code answer}，而契约必须有它）。
     *
     * <p>⚠️ 召回为空时<b>不调用 LLM</b>，直接返回"未找到相关资料" —— 让模型在零上下文的情况下"自由发挥"
     * 就是编造答案。
     *
     * @param question 用户问题（≤500 字，由 Controller 校验）
     * @param topK     召回条数；null 取默认 5，超过上限 10 时<b>收敛为 10</b>
     */
    RagAnswerVO ragQuery(String question, Integer topK);
}
