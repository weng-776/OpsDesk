package com.opsdesk.knowledge.splitter;

/**
 * 一个切分片段（工单 D12-02，规格基线 §12.2「Chunk 参数：size = 500 字，overlap = 50 字」）
 *
 * <h2>字段语义（⚠️ 三个字段的单位/口径各不相同，别混用）</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>含义</th></tr>
 *   <tr><td>{@code index}</td><td>片段序号，<b>从 0 开始、跨节连续递增</b>（对应
 *       {@code knowledge_chunk.chunk_index}，DDL 注释明确「从 0 开始」）</td></tr>
 *   <tr><td>{@code content}</td><td><b>入库正文</b>。若该片所属章节有标题、且不是该节首片，
 *       则形如 {@code "## 标题\n正文…"} —— 标题是<b>复读</b>出来的前缀</td></tr>
 *   <tr><td>{@code start} / {@code end}</td><td>该片在<b>规范化后原文</b>中的字符区间
 *       {@code [start, end)}。<b>不含复读前缀</b></td></tr>
 * </table>
 *
 * <h2>长度口径（★务必用对）</h2>
 * <pre>
 *   content.length() ≤ chunkSize            ← 恒成立：标题复读前缀会从 500 的预算里扣掉
 *   span() = end - start ≤ content.length()  ← span 不含复读前缀
 * </pre>
 * <ul>
 *   <li>判断「这一片是否超过 500 字」→ 用 {@code content.length()} 或 {@code span()} <b>都可以</b>；</li>
 *   <li>想知道「这一片覆盖了原文多长」→ 只能用 {@code span()}。</li>
 * </ul>
 * 两者的差值就是复读标题的长度（含 1 个换行）；无标题的节、以及每节的首片，差值为 0。
 *
 * <h2>⚠️ 为什么 {@code content} 与 {@code [start, end)} 不是「相等」而是「以…结尾」</h2>
 * 「每片复读章节标题」是有意为之（用户拍板：每片自带章节上下文，召回质量更好），
 * 所以第 2 片起 {@code content} 的前缀（标题行）<b>不属于</b>{@code [start, end)} 这段原文。
 * 不变式是：
 * <pre>
 *   content.endsWith(原文.substring(start, end))   ← 恒成立（首片时退化为「相等」）
 * </pre>
 *
 * <p>{@code start}/{@code end} 的用途是让「无内容丢失」（§12.2 要求切分不丢内容）能被
 * <b>精确断言</b>：逐片校验「正文确实是这段原文」+「所有非空白字符都被某个区间覆盖」。
 * 如果只靠「字符串后缀匹配前缀」去合并片段，会被原文里的巧合重复骗过
 * （相邻两片恰好都以「的」结尾/开头就会多吞一个字符）。
 *
 * <p>同时，排查「某段内容怎么没进向量库」时，{@code start}/{@code end} 是直接证据。
 *
 * @param index   片段序号（从 0 开始）
 * @param content 入库正文（可能带章节标题复读前缀）
 * @param start   在规范化后原文中的起始偏移（含）
 * @param end     在规范化后原文中的结束偏移（不含）
 */
public record SplitResult(int index, String content, int start, int end) {

    /**
     * 该片段在原文中覆盖的字符数（{@code end - start}）——
     * <b>不含</b>复读前缀，所以不会大于 {@code content.length()}。
     */
    public int span() {
        return end - start;
    }
}
