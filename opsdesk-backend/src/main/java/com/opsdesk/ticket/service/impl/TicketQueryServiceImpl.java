package com.opsdesk.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.vo.TicketListVO;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单查询实现（工单 D3-02，SOP §5 红区：数据范围）
 *
 * <p>规格依据：API 文档 §8.2 / §8.3 / §16.7、规格基线 §8.3（可见范围）、§18.4（索引）。
 *
 * <h2>条件拼接顺序：数据范围在前，筛选在后</h2>
 * <pre>
 * WHERE deleted = 0                       ← @TableLogic 自动追加
 *   AND (数据范围条件)                     ← 必须走 TicketDataScopeHelper，不许自己拼
 *   AND status = ? AND priority = ? ...    ← 客户端筛选，只能「再缩小」
 *   ORDER BY <白名单列> , id DESC          ← id 是 tiebreaker
 * LIMIT ?, ?
 * </pre>
 * 顺序本身不影响语义（都是 AND），但**数据范围必须在**：
 * 客户端传任何筛选参数都只能在可见集合里再筛，越不过数据范围。
 *
 * <h2>三件容易做错的事</h2>
 * <ol>
 *   <li><b>{@code /mine} 不能走角色范围</b>：§8.2 写的是「数据范围：SELF」，
 *       ADMIN 调它也只能看到自己创建的（见 {@link #mine}）</li>
 *   <li><b>{@code sort} 必须白名单</b>：客户端传的是<b>列名</b>，直接拼进 ORDER BY 就是注入
 *       （SOP §5 红区「动态排序 / 动态列」）</li>
 *   <li><b>必须追加 {@code id DESC} 做 tiebreaker</b>：{@code created_at} 精度到秒，
 *       同秒创建的多张工单若只按它排序，翻页会跳行 / 重复（种子数据就有同秒的）</li>
 * </ol>
 */
@Slf4j
@Service
public class TicketQueryServiceImpl implements TicketQueryService {

    /** 单页最大条数兜底（§20.1 的 50 由 PageQuery 收敛，这里是第二道防线） */
    private static final long MAX_PAGE_SIZE = 50L;

    private final TicketService ticketService;
    private final UserService userService;
    private final TicketDataScopeHelper dataScopeHelper;

    public TicketQueryServiceImpl(TicketService ticketService,
                                  UserService userService,
                                  TicketDataScopeHelper dataScopeHelper) {
        this.ticketService = ticketService;
        this.userService = userService;
        this.dataScopeHelper = dataScopeHelper;
    }

    // ==================== 对外 ====================

    @Override
    public PageResult<TicketListVO> page(TicketQuery query) {
        return doPage(query, ScopeMode.ROLE_BASED);
    }

    @Override
    public PageResult<TicketListVO> mine(TicketQuery query) {
        return doPage(query, ScopeMode.SELF_ONLY);
    }

    /** 数据范围模式：按角色叠加 / 强制只看自己 */
    private enum ScopeMode {
        ROLE_BASED,
        SELF_ONLY
    }

    // ==================== 主流程 ====================

    private PageResult<TicketListVO> doPage(TicketQuery query, ScopeMode scopeMode) {
        long pageNo = query.pageOrDefault();
        long size = Math.min(query.sizeOrDefault(), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        // 只取 VO 需要的列（既避免 SELECT *，也不把 version / deleted / SLA 内部标志位捞出来）
        wrapper.select(Ticket::getId, Ticket::getTicketNo, Ticket::getTitle,
                Ticket::getType, Ticket::getCategory, Ticket::getPriority, Ticket::getStatus,
                Ticket::getCreatorId, Ticket::getAssigneeId,
                Ticket::getSlaResolutionState, Ticket::getResolutionDeadline, Ticket::getCreatedAt);

        // ① 数据范围 —— 必须走 Helper
        if (scopeMode == ScopeMode.SELF_ONLY) {
            dataScopeHelper.applySelfScope(wrapper, UserContext.requireUserId());
        }
        else {
            dataScopeHelper.applyScope(wrapper, UserContext.get());
        }

        // ② 客户端筛选（只能在可见集合里再缩小）
        applyFilters(wrapper, query);

        // ③ 排序（白名单 + tiebreaker）
        applySort(wrapper, query.getSort());

        Page<Ticket> page = ticketService.page(new Page<>(pageNo, size), wrapper);
        log.debug("[工单列表] mode={} page={} size={} total={} 命中 {} 行",
                scopeMode, pageNo, size, page.getTotal(), page.getRecords().size());

        return PageResult.of(toListVOList(page.getRecords()), page.getTotal(), pageNo, size);
    }

    private void applyFilters(LambdaQueryWrapper<Ticket> wrapper, TicketQuery query) {
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
            String escaped = escapeLikeWildcards(keyword);
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
    private void applySort(LambdaQueryWrapper<Ticket> wrapper, String sort) {
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

    // ==================== VO 装配（批量，无 N+1） ====================

    /**
     * 整页一次性装配：创建人 / 处理人姓名<b>批量</b>查（1 条 {@code IN} 查询），
     * 与行数无关 —— 不是每行查一次。
     */
    private List<TicketListVO> toListVOList(List<Ticket> tickets) {
        if (tickets.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = new HashSet<>();
        for (Ticket ticket : tickets) {
            if (ticket.getCreatorId() != null) {
                userIds.add(ticket.getCreatorId());
            }
            if (ticket.getAssigneeId() != null) {
                userIds.add(ticket.getAssigneeId());
            }
        }
        Map<Long, String> displayNames = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, this::displayNameOf));

        return tickets.stream().map(ticket -> {
            TicketListVO vo = new TicketListVO();
            vo.setId(ticket.getId());
            vo.setTicketNo(ticket.getTicketNo());
            vo.setTitle(ticket.getTitle());
            vo.setType(ticket.getType());
            vo.setCategory(ticket.getCategory());
            vo.setPriority(ticket.getPriority());
            // ⚠️ 这是 VO 赋值（把实体里的值搬到出参上），与「状态机流转」毫无关系。
            //    项目审查脚本的 M1「直接 setStatus」会把它报出来 —— 人工判定为**误报**：
            //    真正要拦的是「对已存在的工单直接改状态」（§8），不是「构造出参对象」。
            vo.setStatus(ticket.getStatus());
            vo.setCreatorName(displayNames.get(ticket.getCreatorId()));
            vo.setAssigneeName(ticket.getAssigneeId() == null
                    ? null : displayNames.get(ticket.getAssigneeId()));
            vo.setSlaResolutionState(ticket.getSlaResolutionState());
            vo.setResolutionDeadline(ticket.getResolutionDeadline());
            vo.setCreatedAt(ticket.getCreatedAt());
            return vo;
        }).toList();
    }

    /** 姓名优先用 nickname，缺失时退回 username（两者都不该为 null，但别让 toMap 抛 NPE） */
    private String displayNameOf(User user) {
        return user.getNickname() == null ? user.getUsername() : user.getNickname();
    }

    // ==================== 小工具 ====================

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 转义 LIKE 通配符。
     *
     * <p>参数本身是预编译的（没有 SQL 注入），但用户输入的 {@code %} / {@code _}
     * 会被 MySQL 当成通配符，让 {@code ?keyword=%} 变成「匹配所有工单」。
     * MySQL 默认转义符是 {@code \}，所以先补一层转义。
     */
    private static String escapeLikeWildcards(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
