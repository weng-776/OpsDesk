package com.opsdesk.ticket.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.utils.LikeEscaper;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.entity.Ticket;

import java.util.Locale;

/**
 * 工单查询条件构造（补单 · 工单导出 引入，从 {@code TicketQueryServiceImpl} 抽出）
 *
 * <h2>为什么要抽出来</h2>
 * 「工单列表」（§8.3）与「工单导出」（§8.6）必须用<b>同一套</b>筛选与排序条件 ——
 * §8.6 的入参写的就是「同 §8.3 的筛选参数」。如果两边各写一份，
 * 迟早出现「列表里 8 条、导出 10 条」这种没人能解释的差异，
 * 而复制粘贴的 {@code AND (...)} 分组、{@code LIKE} 转义、{@code id} tiebreaker
 * 一定会少某处。所以条件构造只此一份。
 *
 * <h2>⚠️ 本类<b>不含</b>数据范围</h2>
 * 数据范围（§8.3 的 AGENT 三段式 / EMPLOYEE 的 SELF）必须由调用方通过
 * {@code TicketDataScopeHelper} 追加，且<b>必须先于</b>本类的筛选条件。
 * 把数据范围也塞进来的话，导出接口就有可能「只调了筛选、忘了范围」——
 * 现在这种误用是**结构上不可能**发生的：本类根本不碰范围。
 *
 * <h2>⚠️ 顺序</h2>
 * 调用约定：<pre>
 *   ① dataScopeHelper.applyScope(wrapper, user)
 *   ② TicketQueryConditions.applyFilters(wrapper, query)
 *   ③ TicketQueryConditions.applySort(wrapper, query.getSort())
 * </pre>
 * 三者都是 AND 关系，先后不影响语义，但按这个顺序写，review 时一眼能确认「范围在」。
 */
public final class TicketQueryConditions {

    private TicketQueryConditions() {
    }

    /**
     * 追加客户端筛选条件（§8.3 参数表）。
     *
     * <p>所有条件都是<b>可选</b>：不传就不过滤。它们只能在数据范围之内再缩小 ——
     * 客户端传什么都越不过 {@code TicketDataScopeHelper}。
     */
    public static void applyFilters(LambdaQueryWrapper<Ticket> wrapper, TicketQuery query) {
        if (query.getStatus() != null) {
            wrapper.eq(Ticket::getStatus, query.getStatus());
        }
        if (query.getPriority() != null) {
            wrapper.eq(Ticket::getPriority, query.getPriority());
        }
        if (query.getCategory() != null) {
            wrapper.eq(Ticket::getCategory, query.getCategory());
        }
        if (query.getType() != null) {
            wrapper.eq(Ticket::getType, query.getType());
        }

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            String escaped = LikeEscaper.escape(keyword);
            // 关键字匹配 ticket_no / title，用 AND (...) 分组，避免把 OR 泄漏到外层
            wrapper.and(w -> w.like(Ticket::getTicketNo, escaped).or().like(Ticket::getTitle, escaped));
        }

        if (query.getAssigneeId() != null) {
            wrapper.eq(Ticket::getAssigneeId, query.getAssigneeId());
        }
        if (query.getStartTime() != null) {
            wrapper.ge(Ticket::getCreatedAt, query.getStartTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(Ticket::getCreatedAt, query.getEndTime());
        }
    }

    /**
     * 排序白名单（SOP §5 红区）。
     *
     * <p>只认 {@code createdAt} / {@code priority} × {@code asc} / {@code desc} 四种组合
     * （§8.3 文档列了 {@code createdAt,desc} 与 {@code priority,asc}，这里把另外两个方向也放进来 ——
     * 都是固定列名，不存在拼接）。其余值一律 40001，<b>绝不</b>把客户端字符串拼进 ORDER BY。
     */
    public static void applySort(LambdaQueryWrapper<Ticket> wrapper, String sort) {
        String normalized = sort == null
                ? "" : sort.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        switch (normalized) {
            case "", "createdat,desc" -> wrapper.orderByDesc(Ticket::getCreatedAt);
            case "createdat,asc" -> wrapper.orderByAsc(Ticket::getCreatedAt);
            // priority 存的是 'P1'/'P2'/'P3' 字符串，字典序恰好等于紧急度顺序（P1 最紧急）
            case "priority,asc" -> wrapper.orderByAsc(Ticket::getPriority);
            case "priority,desc" -> wrapper.orderByDesc(Ticket::getPriority);
            default -> throw new BizException(ErrorCode.PARAM_INVALID,
                    "sort 只支持 createdAt,desc / createdAt,asc / priority,asc / priority,desc");
        }
        // ⚠️ tiebreaker：created_at 精度到秒，同秒的多张工单若只按它排序，
        //    MySQL 不保证稳定顺序 → 翻页会跳行/重复。加唯一列兜住
        wrapper.orderByDesc(Ticket::getId);
    }

    /** 空白串归一成 {@code null}（{@code ?keyword=} 视为"不筛"，不是"搜空串"） */
    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
