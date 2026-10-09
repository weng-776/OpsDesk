package com.opsdesk.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsdesk.common.BizException;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.ticket.dto.CommentCreateDTO;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.entity.TicketComment;
import com.opsdesk.ticket.entity.TicketHistory;
import com.opsdesk.ticket.service.TicketCommentBizService;
import com.opsdesk.ticket.service.TicketCommentService;
import com.opsdesk.ticket.service.TicketHistoryService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.vo.TicketCommentVO;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单评论业务实现（工单 D3-04）。
 *
 * <p>规格依据：API 文档 §8.7 / §8.8；DDL {@code ticket_comment.internal}。
 *
 * <h2>两道可见性门槛（不要混为一谈）</h2>
 * <ol>
 *   <li><b>工单可见性</b>（能不能看这张工单）—— 复用 {@code TicketDataScopeHelper.assertVisible}。
 *       顺序与 D3-03 详情一致：<b>先 getById 判 null → 40400，再 assertVisible → 40301</b>。
 *       顺序反了会泄露「这个工单 id 是否存在」。</li>
 *   <li><b>内部备注过滤</b>（能不能看这条<b>内部</b>评论）—— 判断依据是<b>角色</b>，
 *       不是数据范围：EMPLOYEE 哪怕能看到这张工单，也看不到 {@code internal = 1} 的评论（§8.7）。
 *       这一条在 <b>SQL 层</b>过滤，不是查回来再筛 —— 否则分页的 {@code total} 会算错。</li>
 * </ol>
 */
@Service
public class TicketCommentBizServiceImpl implements TicketCommentBizService {

    private static final Logger log = LoggerFactory.getLogger(TicketCommentBizServiceImpl.class);

    /** 外部可见的评论（DDL：0 否 / 1 是） */
    private static final int INTERNAL_NO = 0;

    private final TicketService ticketService;
    private final TicketCommentService ticketCommentService;
    private final TicketHistoryService ticketHistoryService;
    private final UserService userService;
    private final TicketDataScopeHelper dataScopeHelper;

    public TicketCommentBizServiceImpl(TicketService ticketService,
                                       TicketCommentService ticketCommentService,
                                       TicketHistoryService ticketHistoryService,
                                       UserService userService,
                                       TicketDataScopeHelper dataScopeHelper) {
        this.ticketService = ticketService;
        this.ticketCommentService = ticketCommentService;
        this.ticketHistoryService = ticketHistoryService;
        this.userService = userService;
        this.dataScopeHelper = dataScopeHelper;
    }

    // ==================== 列表（§8.7） ====================

    @Override
    public PageResult<TicketCommentVO> list(Long ticketId, TicketQuery query) {
        Ticket ticket = loadVisibleTicket(ticketId);

        query.normalize();
        long pageNo = query.pageOrDefault();
        long size = query.sizeOrDefault();

        // 用 ticketId 二次确认可见性 —— loadVisibleTicket 已经 assert 过了，
        // 这里再显式带上是为了让「按工单查评论」这个约束落在 SQL 上（不会误查全表）
        LambdaQueryWrapper<TicketComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TicketComment::getTicketId, ticket.getId());

        // §8.7：EMPLOYEE 查询时服务端自动过滤 internal = 1。
        // 判断依据是「角色」，不是「数据范围」—— 见类注释
        if (!canSeeInternal()) {
            wrapper.eq(TicketComment::getInternal, INTERNAL_NO);
        }

        // 按时间正序（先说的先出现）；id 做 tiebreaker（同一秒多条时顺序稳定）
        wrapper.orderByAsc(TicketComment::getCreatedAt).orderByAsc(TicketComment::getId);

        Page<TicketComment> page = ticketCommentService.page(new Page<>(pageNo, size), wrapper);
        return PageResult.of(toVOList(page.getRecords()), page.getTotal(), pageNo, size);
    }

    // ==================== 新增（§8.8） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketCommentVO add(Long ticketId, CommentCreateDTO dto) {
        Ticket ticket = loadVisibleTicket(ticketId);
        UserContext.CurrentUser user = UserContext.get();

        TicketComment comment = new TicketComment();
        comment.setTicketId(ticket.getId());
        comment.setUserId(user.userId());
        comment.setContent(dto.getContent());
        comment.setInternal(resolveInternal(dto));
        // created_at 由 DB 填（实体上 insertStrategy = NEVER），insert 后不回填 —— 见下
        ticketCommentService.save(comment);

        // ⚠️ created_at 是 DB 默认值，MP 的 insert 不回填（known-traps #5）。
        //    详情/列表都要把它显示出来，所以查回来取一次（与 D3-01 算 SLA 是同一个坑）
        TicketComment saved = ticketCommentService.getById(comment.getId());

        // 同事务写历史（§D3-04 要点：action = COMMENT）
        TicketHistory history = new TicketHistory();
        history.setTicketId(ticket.getId());
        history.setOperatorId(user.userId());
        history.setAction(TicketHistoryAction.COMMENT);
        // 评论不改变状态：from / to 都填当前状态（§7.2 的状态机矩阵里 COMMENT 不流转状态）
        history.setFromStatus(ticket.getStatus());
        history.setToStatus(ticket.getStatus());
        history.setRemark(truncateRemark(dto.getContent()));
        ticketHistoryService.save(history);

        // TODO(D6): 评论成功后触发通知 —— 通知模块是 Day 6 的交付物（本单只留调用点）。
        //   接通后在此处发 TicketCommentedEvent（收件人 = 创建人 + 受理人；internal=1 时不通知创建人）。
        log.info("[工单评论] 已添加。ticketId={} commentId={} userId={} internal={}",
                ticket.getId(), saved.getId(), user.userId(), saved.getInternal());

        return toVO(saved, loadDisplayNames(List.of(saved)));
    }

    // ==================== 私有 ====================

    /**
     * 按 id 取工单并做数据范围校验。
     *
     * <p>⚠️ 顺序与 D3-03 详情接口完全一致，<b>不能反</b>：
     * 先判「不存在」→ 40400，再判「不可见」→ 40301。
     * 若先判可见性，不存在的 id 也会得到 40301，攻击者能从错误码推断出该 id 是否存在（§2.7）。
     */
    private Ticket loadVisibleTicket(Long ticketId) {
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            throw BizException.notFound("工单不存在");
        }
        dataScopeHelper.assertVisible(ticket, UserContext.get());
        return ticket;
    }

    /**
     * 当前用户能否看到 {@code internal = 1} 的评论。
     *
     * <p>规则（§8.7）：<b>仅 AGENT / ADMIN 可见</b>。
     * 注意是「有 AGENT 或 ADMIN 这个角色」——多角色取并集，与 §8.2 的叠加授权一致。
     */
    private boolean canSeeInternal() {
        UserContext.CurrentUser user = UserContext.get();
        if (user == null) {
            // 未登录：正常轮不到这里（拦截器先返 40100），保守返回 false（fail-closed）
            return false;
        }
        Set<Role> roles = user.roles();
        return roles.contains(Role.AGENT) || roles.contains(Role.ADMIN);
    }

    /**
     * 解析 {@code internal} 的最终值。
     *
     * <p>§8.8 说 {@code internal = true}「仅 AGENT / ADMIN 可设为 true」。
     * EMPLOYEE 传 true 时<b>静默降级为 false</b>（已与用户确认）：
     * {@code internal} 是「IT 内部备注」这个语义的开关，员工本不该有这个诉求；
     * 降级成公开评论符合他的真实意图（想说话），报错反而让他白写一遍内容。
     */
    private int resolveInternal(CommentCreateDTO dto) {
        boolean wanted = Boolean.TRUE.equals(dto.getInternal());
        if (!wanted) {
            return INTERNAL_NO;
        }
        if (canSeeInternal()) {
            return 1;
        }
        log.info("[工单评论] EMPLOYEE 请求 internal=true，已降级为 false。userId={}",
                UserContext.get() == null ? null : UserContext.get().userId());
        return INTERNAL_NO;
    }

    /** 历史备注只存评论摘要（评论全文在 ticket_comment 里，不重复落一遍） */
    private String truncateRemark(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= 200 ? content : content.substring(0, 200);
    }

    private List<TicketCommentVO> toVOList(List<TicketComment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }
        Map<Long, String> displayNames = loadDisplayNames(comments);
        return comments.stream().map(c -> toVO(c, displayNames)).toList();
    }

    private TicketCommentVO toVO(TicketComment comment, Map<Long, String> displayNames) {
        TicketCommentVO vo = new TicketCommentVO();
        vo.setId(comment.getId());
        vo.setUserId(comment.getUserId());
        vo.setUserName(displayNames.get(comment.getUserId()));
        vo.setContent(comment.getContent());
        vo.setInternal(comment.getInternal() != null && comment.getInternal() == 1);
        vo.setCreatedAt(comment.getCreatedAt());
        return vo;
    }

    /** 整页一次性把评论人姓名批量查出来（1 条 IN 查询，无 N+1） */
    private Map<Long, String> loadDisplayNames(List<TicketComment> comments) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (TicketComment c : comments) {
            if (c.getUserId() != null) {
                userIds.add(c.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, this::displayNameOf));
    }

    /** 与 D3-02 列表的取值口径一致：昵称优先，无昵称回落用户名 */
    private String displayNameOf(User user) {
        return user.getNickname() == null ? user.getUsername() : user.getNickname();
    }
}
