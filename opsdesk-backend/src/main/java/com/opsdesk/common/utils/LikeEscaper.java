package com.opsdesk.common.utils;

/**
 * {@code LIKE} 通配符转义（补单 · 知识库文档列表 / 工单导出 引入）
 *
 * <h2>为什么需要它</h2>
 * MyBatis 的 {@code like()} / {@code #{} 预编译} 只防 SQL 注入，<b>不防通配符</b>：
 * 用户输入 {@code %} 会被 MySQL 当成「匹配任意长度」，于是
 * {@code ?keyword=%} 就变成「把整表捞出来」，{@code ?keyword=_} 变成「任意单字符」。
 * 这不是注入，但是一次**静默的功能越界** —— 用户本想搜 "%" 这个字符。
 *
 * <h2>转义规则</h2>
 * MySQL 的默认转义符是反斜杠，且反斜杠自身也要先转义（否则 {@code \%} 会被拆成
 * 「转义符 + 通配符」，达不到"字面百分号"的效果）：
 * <pre>
 *   \  →  \\
 *   %  →  \%
 *   _  →  \_
 * </pre>
 * 顺序<b>必须</b>是先反斜杠、后通配符，反过来会把刚加的转义符再转义一遍。
 *
 * <h2>⚠️ 同类逻辑在项目里还有两处私有副本</h2>
 * {@code TicketQueryServiceImpl} 与 {@code KnowledgeSearchServiceImpl} 各有一份
 * （后者注释里写着「第三次用到时应该抽到 {@code common/utils/}」）。
 * 本次补单是第三、第四次用到，所以新增本类；但<b>没有回头改那两个类</b> ——
 * 它们已经过验收，改动的收益是"少一份重复"，代价是一次多余的回归风险。
 * 等做专项重构单时再统一替换。
 */
public final class LikeEscaper {

    private LikeEscaper() {
    }

    /**
     * 转义 {@code LIKE} 的通配符，让用户输入按<b>字面</b>匹配。
     *
     * @param raw 用户原始输入；{@code null} 原样返回（{@code null} 表示"不做过滤"，由调用方判断）
     * @return 可直接放进 {@code #{}} 的转义结果
     */
    public static String escape(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
