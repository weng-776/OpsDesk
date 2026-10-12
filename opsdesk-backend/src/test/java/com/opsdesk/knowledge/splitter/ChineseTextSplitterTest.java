package com.opsdesk.knowledge.splitter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D12-02：中文按字符切分器的单测（纯单测，不启 Spring、不碰任何中间件）
 *
 * <h2>为什么切分器必须有自己的测试</h2>
 * 本单只交付切分器本身，真正调用它的是 D12-05 的异步流水线 ——
 * 也就是说：切分器写错了，<b>在 D12-05 之前没有任何集成测试会发现</b>。
 * 而且切分质量是"静默出错"的高发区（切歪了不会报错，只会让检索效果变差），
 * 所以它的正确性只能靠这里保证。
 */
class ChineseTextSplitterTest {

    private final ChineseTextSplitter splitter = new ChineseTextSplitter();

    // ==================== 验收 1：1200 字 → 3 片、每片 ≤500、相邻重叠 50 ====================

    @Test
    @DisplayName("验收1：1200 字无标点文本 → 切成 3 片，每片 ≤ 500，相邻精确重叠 50 字")
    void 验收1_切成三片且重叠五十字() {
        String text = "段落内容".repeat(300); // 4 × 300 = 1200 字，无标点无空白

        List<SplitResult> results = splitter.split(text);

        assertThat(results).as("1200 / (500-50) 向上取整 = 3 片").hasSize(3);
        assertThat(results).allSatisfy(r -> assertThat(r.span())
                .as("片 %d 长度必须 ≤ 500", r.index()).isLessThanOrEqualTo(500));
        assertThat(results).extracting(SplitResult::index).containsExactly(0, 1, 2);

        // 相邻重叠正好 50 字：上一片的末尾 50 字 == 下一片的开头 50 字
        for (int i = 1; i < results.size(); i++) {
            String prevTail = text.substring(results.get(i).start(), results.get(i - 1).end());
            String curHead = text.substring(results.get(i).start(), results.get(i).start() + 50);
            assertThat(prevTail).as("片 %d 与片 %d 的重叠必须是 50 字", i - 1, i).hasSize(50).isEqualTo(curHead);
        }
        assertCoversOriginal(text, results);
    }

    // ==================== 验收 2：标题边界优先 ====================

    @Test
    @DisplayName("验收2：两个标题 → 片段不会跨标题（各自归属自己的节）")
    void 验收2_不跨标题切() {
        String text = "# 标题A\n" + "甲方内容。".repeat(5) + "\n## 标题B\n" + "乙方内容。".repeat(5);

        List<SplitResult> results = splitter.split(text);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).contains("# 标题A").doesNotContain("## 标题B");
        assertThat(results.get(1).content()).contains("## 标题B").doesNotContain("# 标题A");
        assertCoversOriginal(text, results);
    }

    @Test
    @DisplayName("验收2 补充：一节超长被切多片 → 每片都复读该节标题（拍板项），且不混入别的标题")
    void 每片复读章节标题() {
        String text = "# 网络排查\n" + "排查步骤说明".repeat(200) + "\n## 另一节\n短内容";

        List<SplitResult> results = splitter.split(text);
        List<SplitResult> networkSection = results.stream()
                .filter(r -> r.content().contains("排查步骤说明"))
                .toList();

        assertThat(networkSection).as("这一节 1200 字，应被切成多片").hasSizeGreaterThan(1);
        assertThat(networkSection).allSatisfy(r -> assertThat(r.content())
                .as("每片都要带本节标题，作为自带上下文")
                .startsWith("# 网络排查\n")
                .doesNotContain("## 另一节"));
        assertThat(results).allSatisfy(r -> assertThat(r.span())
                .as("size=500 约束的是【正文区间】，标题复读前缀是附加上下文（见 SplitResult 字段说明）")
                .isLessThanOrEqualTo(500));
        assertCoversOriginal(text, results);
    }

    @Test
    @DisplayName("标题识别：#{1,6} + 空白才算标题（#foo 不算、####### 不算、单独的 # 不算）")
    void 标题识别边界() {
        String text = "#foo 不是标题\n####### 七级也不是标题\n#\n## 真标题\n正文";

        List<SplitResult> results = splitter.split(text);

        // 只有 "## 真标题" 被认成标题 → 前面的内容属于开头的无标题节
        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).contains("#foo").contains("#######").doesNotContain("## 真标题");
        assertThat(results.get(1).content()).startsWith("## 真标题");
        assertCoversOriginal(text, results);
    }

    @Test
    @DisplayName("🔴 围栏代码块内的 # 不算标题（否则会把代码块从中间切断）")
    void 代码块内的井号不算标题() {
        String text = "# 真标题\n" + "说明文字。".repeat(200)
                + "\n```\n# 这是 shell 注释，不是标题\n```\n" + "结尾说明。".repeat(50);

        List<SplitResult> results = splitter.split(text);

        assertThat(results).extracting(SplitResult::index)
                .as("index 从 0 连续递增")
                .isEqualTo(java.util.stream.IntStream.range(0, results.size()).boxed().toList());
        assertThat(results).as("只应有一个节（那个 shell 注释不算标题）").allSatisfy(r ->
                assertThat(r.content()).doesNotStartWith("# 这是 shell 注释"));
        // 第 2 片起复读的是「真标题」
        assertThat(results.get(1).content()).startsWith("# 真标题\n");
        assertCoversOriginal(text, results);
    }

    // ==================== 验收 3：空文档 ====================

    @Test
    @DisplayName("验收3：空 / 纯空白 / null → 返回空列表，不抛异常（chunk_count 会是 0）")
    void 验收3_空文档返回空列表() {
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split("     ")).isEmpty();
        assertThat(splitter.split("\n\n\n")).isEmpty();
        assertThat(splitter.split("  \n \t \n ")).isEmpty();
        assertThat(splitter.split(null)).isEmpty();
    }

    // ==================== 验收 4：超长单行 ====================

    @Test
    @DisplayName("验收4：10000 字无换行（无任何标点）→ 能正常切完，不死循环")
    void 验收4_超长单行能切完() {
        String text = "测试".repeat(5000); // 10000 字，一个换行都没有

        List<SplitResult> results = splitter.split(text);

        assertThat(results).as("步进 450：ceil((10000-500)/450)+1 = 23 片").hasSize(23);
        assertThat(results.get(results.size() - 1).end()).as("必须切到原文末尾").isEqualTo(text.length());
        assertThat(results).allSatisfy(r -> assertThat(r.span()).isLessThanOrEqualTo(500));
        assertCoversOriginal(text, results);
    }

    // ==================== 验收 5：不产生空片段 ====================

    @Test
    @DisplayName("验收5：任何情况下都不产生空白片段")
    void 验收5_不产生空片段() {
        List<String> samples = List.of(
                "# 标题\n\n\n\n" + "正文。".repeat(300),
                "   \n\n" + "内容".repeat(400) + "\n\n   ",
                "内容1\n\n\n\n内容2  \n  \n内容3",
                "单字");

        for (String sample : samples) {
            List<SplitResult> results = splitter.split(sample);
            assertThat(results).as("样本：%s", sample.substring(0, Math.min(20, sample.length())))
                    .allSatisfy(r -> assertThat(r.content()).isNotBlank());
        }
    }

    // ==================== 验收 6：无内容丢失 ====================

    @Test
    @DisplayName("验收6：切分结果拼回去能覆盖原文（非空白字符一个都不少）")
    void 验收6_覆盖原文() {
        List<String> samples = List.of(
                "内容".repeat(600),
                "# 一\n甲。\n## 二\n乙。\n### 三\n丙。",
                "没有标题的一大段。" + "很长很长。".repeat(200),
                "a".repeat(1000),
                "# 标题\n" + "句子一。句子二？句子三！句子四；句子五\n".repeat(80),
                "混合\r\n换行\r风格\r\n的文本".repeat(100));

        for (String sample : samples) {
            List<SplitResult> results = splitter.split(sample);
            assertCoversOriginal(sample, results);
        }
    }

    // ==================== 标点对齐 ====================

    @Test
    @DisplayName("标点对齐：在范围内【向前】找最后一个句末标点，切在标点之后")
    void 标点对齐向前退() {
        // chunkSize=100 / overlap=20：标点对齐区间为 [start+50, start+100)
        String text = "A".repeat(90) + "。" + "B".repeat(200);

        List<SplitResult> results = splitter.split(text, 100, 20);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).span()).as("应切在索引 90 的句号【之后】").isEqualTo(91);
        assertThat(results.get(0).content()).endsWith("。");
    }

    @Test
    @DisplayName("标点对齐的兜底：范围内没有标点 → 硬切 chunkSize，不越界")
    void 标点太靠前则硬切() {
        String text = "A".repeat(30) + "。" + "B".repeat(200); // 句号在下界之外

        List<SplitResult> results = splitter.split(text, 100, 20);

        assertThat(results.get(0).span()).as("范围内没标点 → 硬切 100").isEqualTo(100);
        assertThat(results.get(0).content()).doesNotEndWith("。");
    }

    @Test
    @DisplayName("🔴 只向前退不向后扩 → 片段长度严格 ≤ chunkSize（向后扩会到 chunkSize+50）")
    void 片段长度恒不超过chunkSize() {
        String text = "句子。".repeat(400) + "尾巴";

        List<SplitResult> results = splitter.split(text);

        assertThat(results).allSatisfy(r -> assertThat(r.span()).isLessThanOrEqualTo(500));
    }

    // ==================== 参数校验（防死循环） ====================

    @Test
    @DisplayName("🔴 overlap ≥ chunkSize → 直接拒绝（否则步进 ≤ 0，无限循环）")
    void overlap不能大于等于chunkSize() {
        assertThatThrownBy(() -> splitter.split("一些内容", 500, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("死循环");
        assertThatThrownBy(() -> splitter.split("一些内容", 100, 999))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> splitter.split("一些内容", 100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("chunkSize ≤ 0 → 拒绝")
    void chunkSize必须为正() {
        assertThatThrownBy(() -> splitter.split("一些内容", 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkSize");
    }

    @Test
    @DisplayName("极端参数也不死循环（chunkSize=1 / overlap=0）")
    void 极端参数不死循环() {
        List<SplitResult> results = splitter.split("abcdef", 1, 0);

        assertThat(results).hasSize(6);
        assertThat(results).allSatisfy(r -> assertThat(r.span()).isEqualTo(1));
    }

    // ==================== 换行归一化 ====================

    @Test
    @DisplayName("\\r\\n / \\r 归一化为 \\n（否则片段里会混着 \\r）")
    void 换行归一化() {
        String text = "第一行。\r\n第二行。\r第三行。".repeat(100);

        List<SplitResult> results = splitter.split(text);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> assertThat(r.content()).doesNotContain("\r"));
        assertCoversOriginal(text, results);
    }

    // ==================== 纯函数（元测试） ====================

    @Test
    @DisplayName("验收：切分器是纯函数 —— 无状态字段、不依赖任何中间件（可直接 new，不启 Spring）")
    void 纯函数无中间件依赖() {
        // 字段全是 static final 常量（本类确实没有实例字段）
        assertThat(ChineseTextSplitter.class.getDeclaredFields())
                .as("切分器不能有实例状态 —— 否则并发调用会互相污染")
                .allSatisfy(f -> assertThat(java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                        .as("字段 %s 必须是 static", f.getName()).isTrue());

        // 类上除了 @Component（只为可注入）之外，不应有任何 Spring 依赖注入相关的注解/字段类型
        assertThat(ChineseTextSplitter.class.getAnnotations())
                .as("只允许 @Component；出现 @Autowired/@Value 之类说明它开始依赖容器了")
                .hasSize(1)
                .allSatisfy(a -> assertThat(a.annotationType()).isEqualTo(Component.class));

        // 同一个输入调两次结果一致（无状态）
        String text = "内容。".repeat(300);
        assertThat(splitter.split(text)).isEqualTo(splitter.split(text));
    }

    // ==================== 辅助 ====================

    /** 与实现同口径的换行归一化（测试里独立实现，不依赖被测对象的私有方法） */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 验收 6 的判定：片段把它负责的原文区间「全覆盖」了一遍（非空白字符一个都不少）。
     *
     * <p>同时校验 {@code content.endsWith(原文[start,end))} —— 这条不变式成立，
     * 才说明 {@code start}/{@code end} 是可信的（否则覆盖断言本身也没意义）。
     * 用 {@code endsWith} 而非 {@code isEqualTo}：每片会复读章节标题，前缀不属于该区间。
     */
    private static void assertCoversOriginal(String rawText, List<SplitResult> results) {
        String text = normalize(rawText);
        if (text.isBlank()) {
            assertThat(results).isEmpty();
            return;
        }

        boolean[] covered = new boolean[text.length()];
        for (SplitResult r : results) {
            assertThat(r.content())
                    .as("片 %d 的正文必须是原文 [%d,%d) 的内容（复读前缀除外）", r.index(), r.start(), r.end())
                    .endsWith(text.substring(r.start(), r.end()));
            for (int i = r.start(); i < r.end(); i++) {
                covered[i] = true;
            }
        }

        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (!covered[i] && !Character.isWhitespace(text.charAt(i))) {
                missing.append(text.charAt(i));
            }
        }
        assertThat(missing.toString())
                .as("以下非空白字符没有被任何片段覆盖 —— 内容丢失了")
                .isEmpty();
    }
}
