package com.opsdesk.ai.support;

import com.opsdesk.ai.dto.AiAnalysisResult;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;

import java.util.List;
import java.util.Locale;

/**
 * 降级规则引擎（工单 D10-03，规格基线 §10.4 降级方案）
 *
 * <h2>它存在的原因</h2>
 * §10.4 的原文要求：AI 不可用（无 Key / 超时 / 配额耗尽）时用关键词规则兜底，
 * 「保证<b>任何情况下工单都有分类与优先级</b>」。
 * 没有它的话，LLM 一挂，工单就变成没有分类、没有优先级 —— 而 SLA 与派单都依赖这两个值。
 *
 * <h2>🔴 三条设计约束</h2>
 * <ol>
 *   <li><b>纯函数</b>：不依赖 Spring 容器、不依赖任何中间件、不调 LLM ——
 *       所以它能被<b>纯单测</b>覆盖（本单的主要交付价值）。{@code static} 方法就是这个约束的体现。</li>
 *   <li><b>产出格式与 AI 完全一致</b>（同一个 {@link AiAnalysisResult}）——
 *       上层拿到的东西没有区别，不需要为"降级"再写一条分支。</li>
 *   <li><b>关键词集中在本类一处</b>，不散落到别的类（§0 约定：禁止散落字符串字面量）。</li>
 * </ol>
 *
 * <h2>⚠️ 命中顺序：按 §10.4 表格顺序，先匹配先返回</h2>
 * 一张工单的描述里完全可能同时出现「网络」和「密码」（例如「VPN 连不上，可能是密码过期了」），
 * 这时必须有一个<b>确定</b>的答案，否则同样的输入两次跑出不同结果。
 * 约定：<b>按 §10.4 表格自上而下匹配，命中即返回</b> →
 * {@code NETWORK} 优先于 {@code ACCOUNT}，{@code ACCOUNT} 优先于 {@code PERIPHERAL}。
 *
 * <p>为什么不做「命中关键词最多的那类」：那会让结果依赖计数细节（描述改一个字就翻盘），
 * 而派单方需要的是<b>可预测</b>。表格顺序本身就是规格给出的优先级。
 *
 * <h2>⚠️ 匹配范围</h2>
 * <b>标题 + 描述都要扫</b>——只看描述会漏掉「标题写『打印机坏了』、描述只写『急』」这种工单。
 */
public final class RuleBasedAnalyzer {

    /**
     * 规则产出的 {@code model} 标记。
     *
     * <p>⚠️ 写进 {@code ai_analysis.model} 是为了<b>事后能区分</b>「这条是 AI 分析的」
     * 还是「这条是规则兜底的」—— 排查分析质量时这是第一个要看的信息。
     */
    public static final String MODEL = "rule-based";

    /** 摘要取描述开头这么多个字 */
    private static final int SUMMARY_MAX_LENGTH = 100;

    /**
     * 关键词规则表（§10.4 原文，顺序即优先级）。
     *
     * <p>⚠️ 顺序不能随手调整：它就是「同时命中时听谁的」这条约定的唯一来源。
     */
    private static final List<Rule> RULES = List.of(
            new Rule(TicketCategory.NETWORK, List.of("VPN", "网络", "连不上")),
            new Rule(TicketCategory.ACCOUNT, List.of("账号", "密码", "权限")),
            new Rule(TicketCategory.PERIPHERAL, List.of("打印机", "键盘", "鼠标")));

    /** §10.4：默认分类 */
    private static final TicketCategory DEFAULT_CATEGORY = TicketCategory.OTHER;

    /** §10.4：priority 默认 P3 */
    private static final TicketPriority DEFAULT_PRIORITY = TicketPriority.P3;

    /** 规则引擎给出的通用建议（§10.3 允许 suggestions 为空，但给两条比留空更有用） */
    private static final List<String> GENERIC_SUGGESTIONS = List.of(
            "AI 分析当前不可用，已按关键词规则给出初步分类与优先级",
            "建议人工复核分类与优先级，必要时手动调整");

    private RuleBasedAnalyzer() {
    }

    /**
     * 按关键词规则分析一张工单。
     *
     * @param title       工单标题（参与匹配）
     * @param description 工单描述（参与匹配）
     * @return 与 AI 同构的结果对象；{@code type} 留空由上层回退为原工单值（§10.4 没有 type 的规则）
     */
    public static AiAnalysisResult analyze(String title, String description) {
        String text = normalize(title) + " " + normalize(description);

        AiAnalysisResult result = new AiAnalysisResult();
        // ⚠️ type 不猜：§10.4 只给了 category 与 priority 的规则。
        //    留 null 让上层的白名单回退逻辑填成「原工单值」，比随便猜一个更可靠。
        result.setType(null);
        result.setCategory(matchCategory(text).name());
        result.setPriority(DEFAULT_PRIORITY.name());
        result.setSummary(summaryOf(title, description));
        result.setSuggestions(GENERIC_SUGGESTIONS);
        return result;
    }

    /**
     * 按规则表顺序匹配分类；都不命中则返回默认分类 {@code OTHER}。
     *
     * <p>返回第一个命中的规则 —— 这就是「先匹配先返回」的实现。
     */
    private static TicketCategory matchCategory(String normalizedText) {
        for (Rule rule : RULES) {
            if (rule.matches(normalizedText)) {
                return rule.category();
            }
        }
        return DEFAULT_CATEGORY;
    }

    /** 摘要取描述开头；描述为空时退回标题 */
    private static String summaryOf(String title, String description) {
        String source = hasText(description) ? description : title;
        if (source == null) {
            return null;
        }
        String trimmed = source.trim();
        return trimmed.length() <= SUMMARY_MAX_LENGTH ? trimmed : trimmed.substring(0, SUMMARY_MAX_LENGTH);
    }

    /**
     * 归一化：转大写后做子串匹配。
     *
     * <p>转大写是为了让 {@code VPN} 也能命中用户写的 {@code vpn} / {@code Vpn}
     * （中文关键词不受影响）。用 {@link Locale#ROOT} 避开某些语言环境下 {@code i/I} 的特殊转换。
     */
    private static String normalize(String text) {
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    /** 一条规则：命中任意关键词即算命中该类目 */
    private record Rule(TicketCategory category, List<String> keywords) {

        boolean matches(String normalizedText) {
            return keywords.stream().anyMatch(normalizedText::contains);
        }
    }
}
