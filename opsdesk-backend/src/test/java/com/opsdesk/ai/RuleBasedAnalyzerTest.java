package com.opsdesk.ai;

import com.opsdesk.ai.dto.AiAnalysisResult;
import com.opsdesk.ai.support.RuleBasedAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * D10-03 验收 1 / 2 / 4 —— 降级规则引擎<b>纯单测</b>
 *
 * <h2>⚠️ 这个类刻意<b>不加</b> {@code @SpringBootTest}</h2>
 * 规则引擎的价值就在于「不依赖任何中间件」—— LLM 挂了、网断了、Key 过期了，
 * 它仍然是唯一还能给出分类与优先级的路径。所以它的测试也不该依赖 Spring 容器：
 * 没有上下文、没有数据库、没有 MQ，整个类跑完不到一秒。
 *
 * <p>这正是验收 4 要验的东西：<b>规则引擎不依赖 Spring 容器 / 网络即可测</b>。
 * 反过来说 —— 如果哪天有人往 {@link RuleBasedAnalyzer} 里注入了 Bean 或发了 HTTP 请求，
 * 这个测试类会立刻编译不过 / 跑不起来，问题当场暴露。
 */
class RuleBasedAnalyzerTest {

    @ParameterizedTest(name = "[{0}] {1} → {2}")
    @DisplayName("验收1：§10.4 表格的三组关键词 + 默认，分别命中对应类目")
    @CsvSource({
            // VPN / 网络 / 连不上 → NETWORK
            "公司 VPN 无法连接,今天上午开始 VPN 一直连接失败,NETWORK",
            "网络中断,办公区网络全部不通了,NETWORK",
            "系统连不上,点了登录一直连不上,NETWORK",
            // 账号 / 密码 / 权限 → ACCOUNT
            "账号被锁定,我的账号登录不了,ACCOUNT",
            "忘记密码,密码重置后仍然登录失败,ACCOUNT",
            "权限申请,需要开通财务系统的权限,ACCOUNT",
            // 打印机 / 键盘 / 鼠标 → PERIPHERAL
            "打印机不出纸,三楼打印机卡纸了,PERIPHERAL",
            "键盘失灵,键盘有几个键按不出来,PERIPHERAL",
            "鼠标没反应,无线鼠标换了电池还是没反应,PERIPHERAL",
            // 默认 → OTHER
            "会议室空调坏了,会议室空调不制冷,OTHER",
    })
    void 按关键词命中分类(String title, String description, String expectedCategory) {
        AiAnalysisResult result = RuleBasedAnalyzer.analyze(title, description);

        assertThat(result.getCategory()).isEqualTo(expectedCategory);
        assertThat(result.getPriority()).as("§10.4：priority 默认 P3").isEqualTo("P3");
    }

    @Test
    @DisplayName("验收2：同时命中多组关键词 → 按 §10.4 表格顺序，先匹配先返回")
    void 同时命中时按表顺序返回() {
        // 「VPN/网络」在表里排在「密码」之前
        assertThat(RuleBasedAnalyzer.analyze("VPN 连不上", "可能是密码过期了").getCategory())
                .as("NETWORK 优先于 ACCOUNT").isEqualTo("NETWORK");

        // 「密码」排在「打印机」之前
        assertThat(RuleBasedAnalyzer.analyze("密码错误", "顺便说三楼打印机也坏了").getCategory())
                .as("ACCOUNT 优先于 PERIPHERAL").isEqualTo("ACCOUNT");

        // 三类全中 → 仍然是 NETWORK
        assertThat(RuleBasedAnalyzer.analyze("VPN 连不上", "密码过期，打印机也坏了").getCategory())
                .isEqualTo("NETWORK");
    }

    @Test
    @DisplayName("验收2：同一输入反复调用结果完全一致（纯函数，无隐藏状态）")
    void 结果稳定可复现() {
        String title = "VPN 连不上";
        String description = "密码也过期了，打印机也不出纸";

        String first = RuleBasedAnalyzer.analyze(title, description).getCategory();
        for (int i = 0; i < 50; i++) {
            assertThat(RuleBasedAnalyzer.analyze(title, description).getCategory())
                    .as("第 %d 次调用", i + 1)
                    .isEqualTo(first);
        }
        assertThat(first).isEqualTo("NETWORK");
    }

    @Test
    @DisplayName("匹配范围含标题：描述里没有任何关键词，只靠标题也能命中")
    void 标题也参与匹配() {
        assertThat(RuleBasedAnalyzer.analyze("打印机坏了", "急，尽快处理").getCategory())
                .isEqualTo("PERIPHERAL");
        assertThat(RuleBasedAnalyzer.analyze("密码问题", "见上").getCategory())
                .isEqualTo("ACCOUNT");
    }

    @ParameterizedTest(name = "[{0}] 也要能识别")
    @DisplayName("关键词匹配不区分大小写（vpn / Vpn / VPN 都算命中）")
    @ValueSource(strings = {"vpn 连不上", "Vpn 连不上", "VPN 连不上"})
    void 大小写不敏感(String title) {
        assertThat(RuleBasedAnalyzer.analyze(title, null).getCategory()).isEqualTo("NETWORK");
    }

    @ParameterizedTest(name = "输入 {0} 不抛异常")
    @DisplayName("空 / null 输入不抛异常，走默认值")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 空输入走默认值(String empty) {
        assertThatCode(() -> RuleBasedAnalyzer.analyze(empty, empty)).doesNotThrowAnyException();

        AiAnalysisResult result = RuleBasedAnalyzer.analyze(empty, empty);
        assertThat(result.getCategory()).as("§10.4：默认 OTHER").isEqualTo("OTHER");
        assertThat(result.getPriority()).isEqualTo("P3");
    }

    @Test
    @DisplayName("摘要取描述前 100 字；描述为空时退回标题")
    void 摘要生成规则() {
        assertThat(RuleBasedAnalyzer.analyze("标题", "很短的描述").getSummary()).isEqualTo("很短的描述");
        assertThat(RuleBasedAnalyzer.analyze("标题", "x".repeat(300)).getSummary()).hasSize(100);
        assertThat(RuleBasedAnalyzer.analyze("只有标题", null).getSummary()).as("描述为空 → 用标题").isEqualTo("只有标题");
    }

    @Test
    @DisplayName("产出格式与 AI 完全同构：同一个 DTO，suggestions 给通用建议，model 标记 rule-based")
    void 产出格式与AI一致() {
        AiAnalysisResult result = RuleBasedAnalyzer.analyze("VPN 连不上", "连不上内网");

        assertThat(result).isInstanceOf(AiAnalysisResult.class);
        assertThat(result.getSuggestions()).as("§10.3 允许为空，但给通用建议更有用").isNotEmpty();
        assertThat(result.getType()).as("§10.4 没有 type 规则 → 留 null，由上层回退为原工单值").isNull();
        assertThat(RuleBasedAnalyzer.MODEL).isEqualTo("rule-based");
    }

    @Test
    @DisplayName("验收4：规则引擎不依赖 Spring 容器 / 网络（本类无 @SpringBootTest 即可运行）")
    void 不依赖容器() {
        // 元测试：这个类跑起来本身就已经证明了不依赖容器；这里再把约束显式写下来，
        // 将来谁把 RuleBasedAnalyzer 改成需要注入 Bean 的 @Component，这两条断言会立刻红。
        assertThat(Modifier.isFinal(RuleBasedAnalyzer.class.getModifiers()))
                .as("工具类应当是 final 且只有静态入口").isTrue();
        assertThat(RuleBasedAnalyzer.class.getAnnotations())
                .as("不应带任何 Spring 注解（@Component / @Service …）").isEmpty();
        assertThat(RuleBasedAnalyzer.class.getDeclaredFields())
                .as("除常量外不应有可注入的成员（都是 static final）")
                .allMatch(f -> Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()));
    }
}
