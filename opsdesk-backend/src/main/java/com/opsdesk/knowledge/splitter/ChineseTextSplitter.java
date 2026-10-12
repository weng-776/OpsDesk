package com.opsdesk.knowledge.splitter;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 中文按字符切分器（工单 D12-02，规格基线 §12.2）
 *
 * <h2>🔴 为什么不用框架的 {@code TokenTextSplitter}（工单点名要求说明理由）</h2>
 * {@code org.springframework.ai.transformer.splitter.TokenTextSplitter} <b>确实存在</b>
 * （{@code spring-ai-commons-1.1.8.jar}），但它按 <b>BPE token</b> 切，而规格要求<b>按中文字符</b>。
 * 反编译后的默认值：
 * <pre>
 *   private final com.knuddels.jtokkit.api.EncodingRegistry registry;   ← jtokkit 分词器
 *   DEFAULT_CHUNK_SIZE        = 800     ← 单位是 token
 *   MIN_CHUNK_SIZE_CHARS      = 350
 *   MIN_CHUNK_LENGTH_TO_EMBED = 5       ← 长度 &lt; 5 的片段【被丢弃】
 *   MAX_NUM_CHUNKS            = 10000   ← 超出后【静默丢弃】剩余内容
 * </pre>
 * 四条不用的理由：
 * <ol>
 *   <li><b>口径不一致</b>：中文 1 字 ≈ 1~2 token → {@code TokenTextSplitter(500)} 切出的中文片段
 *       只有 250~350 字，且分词边界由 BPE 词表决定、<b>对中文不可控</b>；</li>
 *   <li>{@code MAX_NUM_CHUNKS=10000} 会<b>静默丢弃</b>超出的正文 → 违反「无内容丢失」；</li>
 *   <li>{@code MIN_CHUNK_LENGTH_TO_EMBED=5} 会丢短片段 → 违反「每个片段都非空」；</li>
 *   <li>它产出 {@code List<Document>}（带元数据包装），而我们要的是写
 *       {@code knowledge_chunk(document_id, content, chunk_index)}，那层包装是多余的中间层。</li>
 * </ol>
 *
 * <h2>切分策略</h2>
 * <pre>
 * ① 规范化          \r\n / \r → \n
 * ② 按标题切「节」   行首 #{1,6} + 空白 为界（围栏代码块内的 # 不算标题）
 * ③ 节内切分        节长 ≤ 500 → 整节一片；否则固定长度切 + 相邻 50 字重叠
 * </pre>
 *
 * <h2>三个必须讲清楚的设计点</h2>
 * <ol>
 *   <li><b>标点对齐「只向前退」</b>：在 {@code [start+250, start+500]} 内找<b>最后一个</b>句末标点
 *       （{@code 。！？；.!?;} 与换行）作为切点，找不到就硬切 500。只向前退、不向后扩，
 *       保证片段长度<b>严格 ≤ 500 字</b>（向后扩会到 550，违反规格）。不含逗号/顿号 —— 太密，会把片段切碎。</li>
 *   <li><b>跨节不重叠</b>：标题边界是语义硬边界，若跨节重叠，同一段文字会同时归到两个标题的语义下，
 *       削弱「按标题切」的收益。</li>
 *   <li><b>每片复读章节标题</b>（用户拍板）：该节被切成 N 片时，{@code ## 标题} 出现在每一片开头，
 *       让每片自带上下文。首片不重复拼（它的区间本来就含标题行）。详见 {@link SplitResult} 的字段说明。</li>
 * </ol>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@code overlap < chunkSize}（否则步进 ≤ 0 → 死循环）；</li>
 *   <li>片段长度恒 ≤ {@code chunkSize}；</li>
 *   <li>不产生空片段（纯空白片段被丢弃）—— 空白不算「内容丢失」；</li>
 *   <li>超长单行（无换行）能正常切完 —— 实现<b>不按换行切</b>，换行只是「标点对齐」的候选之一。</li>
 * </ul>
 *
 * <h2>纯函数</h2>
 * 无状态、无 IO、无外部依赖。加 {@link Component} 只为让上层（D12-05 的异步流水线）能注入，
 * 与 D12-01 的 {@code TextDocumentParser} 同一模式；测试直接 {@code new} 构造，<b>不需要启动 Spring</b>。
 */
@Component
public class ChineseTextSplitter {

    /** 规格 §12.2：Chunk size = 500 字 */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    /** 规格 §12.2：overlap = 50 字 */
    public static final int DEFAULT_OVERLAP = 50;

    /**
     * 标点对齐搜索下界的<b>上限</b>（默认参数下 = 250，即搜索区间占「半个片段」的宽度）。
     *
     * <p>作用：让搜索区间不至于太窄 —— 否则会为了凑标点把片段切得很碎。
     * 实际下界见 {@link #cutPoint}：{@code max(overlap + 1, min(MIN_CHUNK_SIZE, chunkSize / 2))}。
     */
    private static final int MIN_CHUNK_SIZE = DEFAULT_CHUNK_SIZE / 2;

    /** 句末标点 / 换行（切在这些字符之后，标点归前一片）。刻意不含逗号、顿号。 */
    private static final Set<Character> BREAK_CHARS = Set.of('。', '！', '？', '；', '!', '?', ';', '\n');

    /** Markdown ATX 标题最多 6 级 */
    private static final int MAX_HEADING_LEVEL = 6;

    /**
     * 按规格默认参数切分（500 字 / overlap 50）。
     *
     * @param text 解析后的纯文本（D12-01 的 {@code KnowledgeDocumentParser} 产出）；可为 null
     * @return 片段列表；<b>空/纯空白文本返回空列表</b>（不抛异常），{@code index} 从 0 连续递增
     */
    public List<SplitResult> split(String text) {
        return split(text, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * 按指定参数切分（主要给测试用，可以构造边界场景）。
     *
     * @param chunkSize 单片最大字符数，必须 &gt; 0
     * @param overlap   相邻片段的重叠字符数，必须满足 {@code 0 <= overlap < chunkSize}
     * @throws IllegalArgumentException 参数非法（尤其 {@code overlap >= chunkSize}，那会导致死循环）
     */
    public List<SplitResult> split(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须为正数，实际：" + chunkSize);
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap 必须落在 [0, chunkSize) 内 —— 否则步进 <= 0 会死循环。实际 overlap=" + overlap
                            + "、chunkSize=" + chunkSize);
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = normalize(text);
        List<SplitResult> results = new ArrayList<>();
        for (Section section : sections(normalized)) {
            appendSection(normalized, section, chunkSize, overlap, results);
        }
        return List.copyOf(results);
    }

    // ==================== ① 规范化 ====================

    /**
     * 统一换行符。
     *
     * <p>Windows 文档是 {@code \r\n}，不归一化的话片段里会混着 {@code \r}，
     * 而「标点对齐」在找换行时会漏掉 {@code \r} 后面的 {@code \n}（其实是同一个换行）。
     */
    private String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ==================== ② 按标题切节 ====================

    /**
     * 一个「节」：从一个标题行开始，到下一个标题行（或文末）为止。
     *
     * <p>文档开头若没有标题，会先形成一个 {@code header == null} 的无标题节 ——
     * 这样「原文的每一段都归属于某个节」，不会丢内容。
     *
     * @param start  节起始（含）—— 标题行的行首
     * @param end    节结束（不含）—— 下一个标题行的行首，或文末
     * @param header 标题行原文（不含换行）；无标题节为 {@code null}
     */
    private record Section(int start, int end, String header) {
    }

    private List<Section> sections(String text) {
        List<Integer> headingStarts = headingStarts(text);
        List<Section> out = new ArrayList<>();

        int firstHeading = headingStarts.isEmpty() ? text.length() : headingStarts.get(0);
        if (firstHeading > 0 || headingStarts.isEmpty()) {
            // 文档开头的无标题段（或者整篇都没有标题）
            out.add(new Section(0, firstHeading, null));
        }

        for (int i = 0; i < headingStarts.size(); i++) {
            int start = headingStarts.get(i);
            int end = (i + 1 < headingStarts.size()) ? headingStarts.get(i + 1) : text.length();
            out.add(new Section(start, end, lineOf(text, start)));
        }
        return out;
    }

    /**
     * 找出所有标题行的起始偏移。
     *
     * <p>⚠️ <b>围栏代码块内的 {@code #} 不算标题</b> —— md 的代码块里很常见
     * {@code # 这是 shell 注释}，误判会把代码块从中间切断，切出读不通的垃圾片段。
     */
    private List<Integer> headingStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        boolean inFence = false;
        int lineStart = 0;

        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String trimmed = text.substring(lineStart, lineEnd).stripLeading();

            if (isFence(trimmed)) {
                inFence = !inFence;
            }
            else if (!inFence && isHeading(trimmed)) {
                starts.add(lineStart);
            }

            lineStart = lineEnd + 1;
        }
        return starts;
    }

    private boolean isFence(String trimmedLine) {
        return trimmedLine.startsWith("```") || trimmedLine.startsWith("~~~");
    }

    /**
     * 是否为 ATX 标题：{@code #{1,6}} 后必须跟空白。
     *
     * <p>所以 {@code #foo}（无空格）不算、{@code ####### x}（7 个 #）不算、
     * 单独一行 {@code #} 也不算。
     */
    private boolean isHeading(String trimmedLine) {
        int level = 0;
        while (level < trimmedLine.length() && trimmedLine.charAt(level) == '#') {
            level++;
        }
        if (level < 1 || level > MAX_HEADING_LEVEL || level >= trimmedLine.length()) {
            return false;
        }
        char after = trimmedLine.charAt(level);
        return after == ' ' || after == '\t';
    }

    private String lineOf(String text, int lineStart) {
        int lineEnd = text.indexOf('\n', lineStart);
        return lineEnd < 0 ? text.substring(lineStart) : text.substring(lineStart, lineEnd);
    }

    // ==================== ③ 节内切分 ====================

    private void appendSection(String text, Section section, int chunkSize, int overlap,
                               List<SplitResult> out) {
        int start = section.start();

        while (start < section.end()) {
            int end = (section.end() - start <= chunkSize)
                    // 剩余不足一片 → 全部收尾（不再对齐标点，否则会为了一个标点把尾巴切碎）
                    ? section.end()
                    : cutPoint(text, start, start + chunkSize, overlap);

            // 收紧到非空白区间：首尾空白既不进 content，也不计入 [start, end)
            int from = start;
            while (from < end && Character.isWhitespace(text.charAt(from))) {
                from++;
            }
            int to = end;
            while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
                to--;
            }

            if (from < to) {
                String body = text.substring(from, to);
                // 首片：区间本身就含标题行，直接用 body；后续片：手工复读标题作为前缀
                boolean firstOfSection = (start == section.start());
                String content = (section.header() != null && !firstOfSection)
                        ? section.header() + "\n" + body
                        : body;
                out.add(new SplitResult(out.size(), content, from, to));
            }

            if (end >= section.end()) {
                break;
            }
            start = end - overlap;
        }
    }

    /**
     * 找出切点：在 {@code [floor, hardLimit)} 内<b>向前</b>找最后一个句末标点，返回其「之后」的位置；
     * 找不到则硬切在 {@code hardLimit}。
     *
     * <p><b>只向前退、不向后扩</b> —— 保证片段长度 ≤ {@code chunkSize}。
     *
     * <p>下界 {@code floor = start + max(overlap + 1, min(MIN_CHUNK_SIZE, chunkSize / 2))}：
     * <ul>
     *   <li>{@code overlap + 1} —— 保证「本片结束位置 - overlap」严格大于本片起始，步骤一定往前推进；</li>
     *   <li>{@code chunkSize / 2}（上限 250）—— 让标点搜索区间占「半个片段」的宽度。
     *       取 {@code chunkSize / 2} 而不是 {@code chunkSize - 1}：后者在调用方传小 {@code chunkSize}
     *       （如测试里的 100）时会把下界顶到 {@code hardLimit - 1}，搜索区间退化成 1 个字符，
     *       标点对齐形同失效。</li>
     * </ul>
     * 两者取大，无论 {@code chunkSize/overlap} 多么刁钻都不会死循环。
     */
    private int cutPoint(String text, int start, int hardLimit, int overlap) {
        int chunkSize = hardLimit - start;
        int floor = start + Math.max(overlap + 1, Math.min(MIN_CHUNK_SIZE, chunkSize / 2));

        for (int i = hardLimit - 1; i >= floor; i--) {
            if (BREAK_CHARS.contains(text.charAt(i))) {
                return i + 1;
            }
        }
        return hardLimit;
    }
}
