package com.opsdesk.ai.service.impl;

import com.opsdesk.ai.dto.AiAdoptDTO;
import com.opsdesk.ai.entity.AiAnalysis;
import com.opsdesk.ai.service.AiAdoptService;
import com.opsdesk.ai.service.AiAnalysisService;
import com.opsdesk.audit.annotation.AuditLog;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.common.enums.AiAnalysisStatus;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.vo.TicketDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 采纳 AI 建议实现（工单 D10-05，API 文档 §8.14，规格基线 §10.2 / §3.11 / §14.4）
 *
 * <h2>🔴 §10.2：只有这里能改工单字段</h2>
 * 整条 AI 链路上（分析 → 校验 → 降级 → 落库）没有任何一处写过 {@code ticket} 表 ——
 * AI 的产出永远只是一条 {@code ai_analysis} 记录。**只有 AGENT 在本接口显式勾选采纳，字段才会变。**
 * 这个原则写在代码里，不是为了好看：它决定了"AI 出错时最坏会怎样"——
 * 最坏只是建议不好，而不是工单被悄悄改坏。
 *
 * <h2>四道校验，每一步都不能省</h2>
 * <ol>
 *   <li><b>可操作这道闸</b>：数据范围（40301）—— {@code ticket:process} 只说明"能处理工单"，
 *       不说明"能处理<b>这张</b>工单"；</li>
 *   <li><b>终态拦截</b>：{@code CLOSED} / {@code CANCELLED} → 40900。§8.14 没写这条，
 *       但终态工单改分类/优先级没有任何意义，且与状态机口径一致
 *       （{@code TicketStatus} 自带 {@code terminal} 标志，状态机里也没有以终态为 from 的流转）；</li>
 *   <li><b>建议可用</b>：最近一次分析必须存在（否则 40400）且 {@code SUCCESS}（{@code FAILED} → 40001）。
 *       ⚠️ 只看<b>最新一条</b>，不去翻更早的成功记录 —— 用陈旧结果做采纳会让人摸不着头脑；</li>
 *   <li><b>值合法</b>：采纳值必须非空且是 §3 枚举常量（见 {@link #requireCategory}）。</li>
 * </ol>
 *
 * <h2>⚠️ 已知不一致：采纳优先级后 <b>不重算 SLA</b>（本单的明确取舍）</h2>
 * §9 的 SLA 是「工单<b>创建时</b>按当时的 priority 计算并落库」。
 * 采纳把 priority 改掉之后，{@code response_deadline} / {@code resolution_deadline} 仍按旧优先级，
 * 于是「优先级是 P1、时限却是 P3 的」这种组合是可能出现的 —— <b>这是刻意留的已知不一致</b>，
 * 不是漏做：规格没有要求重算，重算会牵动 SLA 计算与超时通知（§9.5 的扫描会立刻按新时限判 BREACHED），
 * 属于另一单的范围。
 *
 * <p>→ 对外要能说清这个取舍：<b>「改优先级」与「重算 SLA」是两件独立的事</b>，
 * 第一版只做前者，把后者留在 SLA 模块统一处理。
 *
 * <h2>⚠️ 审计操作码：复用 {@code TICKET_PRIORITY_CHANGE}</h2>
 * §3.11 的操作码是白名单，里面<b>没有</b> {@code TICKET_CATEGORY_CHANGE}，也不建议为此新增枚举值。
 * API 文档 §8.14 自己写的也是「写审计（{@code TICKET_PRIORITY_CHANGE} 等）」。
 * 所以两个开关统一用这个码 —— 具体改了哪个字段看 {@code audit_log} 的
 * {@code before_data} / {@code after_data} 快照对比即可。
 *
 * <h2>⚠️ 不写 {@code ticket_history}</h2>
 * §14.4 明确 {@code ticket_history} 只记工单<b>业务过程</b>（创建 / 分派 / 状态变更 / 回复 / 关闭 / 重开），
 * 优先级与分类的修改不在其中。所以本单<b>只写 audit，不写 history</b> —— 两张表分工不同，不要互相补位。
 */
@Slf4j
@Service
public class AiAdoptServiceImpl implements AiAdoptService {

    private final TicketService ticketService;
    private final TicketDataScopeHelper dataScopeHelper;
    private final AiAnalysisService aiAnalysisService;
    private final TicketQueryService ticketQueryService;

    public AiAdoptServiceImpl(TicketService ticketService,
                              TicketDataScopeHelper dataScopeHelper,
                              AiAnalysisService aiAnalysisService,
                              TicketQueryService ticketQueryService) {
        this.ticketService = ticketService;
        this.dataScopeHelper = dataScopeHelper;
        this.aiAnalysisService = aiAnalysisService;
        this.ticketQueryService = ticketQueryService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ {@code skipIfUnchanged = true}：两个开关都 false（或采纳的值与现值相同）时，
     * before/after 快照一样，审计不落冗余记录 —— 否则每次"空采纳"都会产生一条噪音日志。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(operation = AuditOperation.TICKET_PRIORITY_CHANGE,
            resourceType = AuditResourceType.TICKET,
            resourceId = "#ticketId",
            skipIfUnchanged = true)
    public TicketDetailVO adopt(long ticketId, AiAdoptDTO dto) {
        // ① 存在性 + 数据范围（顺序：先 40400 再 40301，反了会泄露资源存在性）
        Ticket ticket = requireVisibleTicket(ticketId);

        // ② 终态工单没有采纳的意义
        if (ticket.getStatus() != null && ticket.getStatus().isTerminal()) {
            throw BizException.conflict("工单已" + ticket.getStatus().getLabel() + "，不能再采纳 AI 建议");
        }

        // ③ 最近一次分析必须存在且成功
        AiAnalysis analysis = requireSuccessfulAnalysis(ticketId);

        // ④ 按开关采纳 —— 只有显式为 true 的那一项会被改（§10.2）
        boolean changed = false;
        if (dto.isAdoptCategory()) {
            TicketCategory category = requireCategory(analysis.getCategory());
            if (!Objects.equals(ticket.getCategory(), category)) {
                ticket.setCategory(category);
                changed = true;
            }
        }
        if (dto.isAdoptPriority()) {
            TicketPriority priority = requirePriority(analysis.getPriority());
            if (!Objects.equals(ticket.getPriority(), priority)) {
                ticket.setPriority(priority);
                // ⚠️ 这里刻意不重算 SLA —— 见类注释的「已知不一致」
                changed = true;
            }
        }

        // ⑤ 只在真的有变化时落库（updateById 会自动带上 @Version 乐观锁条件）
        if (changed) {
            if (!ticketService.updateById(ticket)) {
                // 乐观锁没命中：说明有人在我们读取之后改过这张工单
                throw BizException.conflict("工单已被其他操作变更，请刷新后重试");
            }
            log.info("[AI 采纳] 已按显式采纳修改工单字段。ticketId={} category={} priority={}",
                    ticketId, ticket.getCategory(), ticket.getPriority());
        }
        else {
            log.info("[AI 采纳] 没有需要变更的字段（两个开关都 false，或采纳值与现值相同）。ticketId={}", ticketId);
        }

        // ⑥ 返回最新快照，前端可直接刷新（与 §8.12 口径一致）
        return ticketQueryService.detail(ticketId);
    }

    // ==================== 私有 ====================

    /** 存在性 + 数据范围（顺序见类注释第 ① 条） */
    private Ticket requireVisibleTicket(long ticketId) {
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            throw BizException.notFound("工单不存在");
        }
        // 未登录时 UserContext.get() 为 null → Helper 判 NONE → 40301（fail-closed）
        dataScopeHelper.assertVisible(ticket, UserContext.get());
        return ticket;
    }

    /** 取最近一次分析并要求它成功（§8.14 的"有可采纳的值"） */
    private AiAnalysis requireSuccessfulAnalysis(long ticketId) {
        AiAnalysis analysis = aiAnalysisService.lambdaQuery()
                .eq(AiAnalysis::getTicketId, ticketId)
                .orderByDesc(AiAnalysis::getId)
                .last("LIMIT 1")
                .one();
        if (analysis == null) {
            throw BizException.notFound("该工单尚未生成 AI 分析结果");
        }
        if (analysis.getStatus() != AiAnalysisStatus.SUCCESS) {
            // FAILED 记录里没有任何可采纳的值 —— 用 40001 而不是 40400：
            // 「分析失败」与「还没分析过」要能区分开（前者应当重试分析，后者应当先去触发分析）
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "最近一次 AI 分析未成功，没有可采纳的建议");
        }
        return analysis;
    }

    /**
     * 防御性校验：采纳的分类必须合法。
     *
     * <p>⚠️ 这里真正能挡住的是 {@code null}（FAILED 记录，或人工改库把值置空）。
     * <b>不写</b> {@code try { Enum.valueOf(...) } } 那种形式的"白名单校验" ——
     * {@code AiAnalysis} 的 {@code category} 用的是<b>枚举映射</b>，能映射出来就必然是本枚举常量，
     * 对已装箱的枚举再做一次 {@code valueOf} 是死代码，只会给人"已经校验过了"的错觉。
     *
     * <p>真正的白名单保证在两处：① entity 的枚举类型；② D10-02 写入时已经对模型输出做过
     * {@code Enum.valueOf} 校验与回退（非法值根本进不了库）。
     */
    private TicketCategory requireCategory(TicketCategory category) {
        if (category == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "AI 分析没有给出分类建议，无法采纳");
        }
        return category;
    }

    /** 防御性校验：采纳的优先级必须合法（说明同 {@link #requireCategory}） */
    private TicketPriority requirePriority(TicketPriority priority) {
        if (priority == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "AI 分析没有给出优先级建议，无法采纳");
        }
        return priority;
    }
}
