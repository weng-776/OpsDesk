package com.opsdesk.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.ai.entity.AiAnalysis;
import com.opsdesk.ai.service.AiAnalysisQueryService;
import com.opsdesk.ai.service.AiAnalysisService;
import com.opsdesk.ai.service.TicketAnalyzeService;
import com.opsdesk.ai.vo.AiAnalysisVO;
import com.opsdesk.common.BizException;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 分析触发与查询实现（工单 D10-04，API 文档 §11.1 / §8.13）
 *
 * <h2>两个方法共用的第一段逻辑：可见性校验</h2>
 * <pre>
 * ① getById 查不到 → 40400「工单不存在」
 * ② 查到 → {@code TicketDataScopeHelper.assertVisible} → 不可见 → 40301
 * </pre>
 * ⚠️ <b>顺序不能反</b>：先判可见性的话，一个不存在的 id 也会得到 40301，
 * 攻击者就能从「是 40301 而不是 40400」推断出这个 id 存在（§2.7 不泄露资源存在性）。
 * 与 {@code TicketQueryServiceImpl#detail} 用的是同一套判定（{@code TicketDataScopeHelper}），
 * 所以「在详情页看得见」与「能查它的 AI 分析」永远是同一个结论 —— 不会出现两处判定打架。
 *
 * <h2>⚠️ 为什么不调 {@code TicketQueryService.detail(id)} 复用</h2>
 * 那个方法会顺带把工单详情（含部门名、处理人等）整份查出来并组装，而本单只需要
 * 「存在性 + 可见性」这一个结论。直接调 {@code TicketDataScopeHelper.assertVisible}
 * 是同一个判定、更小的代价。
 */
@Slf4j
@Service
public class AiAnalysisQueryServiceImpl implements AiAnalysisQueryService {

    private final TicketService ticketService;
    private final TicketDataScopeHelper dataScopeHelper;
    private final AiAnalysisService aiAnalysisService;
    private final TicketAnalyzeService ticketAnalyzeService;
    private final ObjectMapper objectMapper;

    public AiAnalysisQueryServiceImpl(TicketService ticketService,
                                      TicketDataScopeHelper dataScopeHelper,
                                      AiAnalysisService aiAnalysisService,
                                      TicketAnalyzeService ticketAnalyzeService,
                                      ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.dataScopeHelper = dataScopeHelper;
        this.aiAnalysisService = aiAnalysisService;
        this.ticketAnalyzeService = ticketAnalyzeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiAnalysisVO triggerAnalyze(long ticketId) {
        requireVisibleTicket(ticketId);

        log.info("[AI 分析] 手动触发。ticketId={}", ticketId);
        // 落新记录而不是覆盖旧的：旧结果留着可对比，查询按最新一条返回（见接口注释）
        return ticketAnalyzeService.analyze(ticketId);
    }

    @Override
    public AiAnalysisVO latest(long ticketId) {
        requireVisibleTicket(ticketId);

        // ⚠️ 同一工单可能有多条（每次触发/每次自动分析都新增一条）→ 取最新
        // id 自增与 created_at 同序，用 id 倒序即可命中 idx_ai_ticket 的前缀
        AiAnalysis entity = aiAnalysisService.lambdaQuery()
                .eq(AiAnalysis::getTicketId, ticketId)
                .orderByDesc(AiAnalysis::getId)
                .last("LIMIT 1")
                .one();

        if (entity == null) {
            // §8.13 的 40400 指的是「尚未生成」，与上面「工单不存在」是两条不同的 40400
            throw BizException.notFound("该工单尚未生成 AI 分析结果");
        }
        // ⚠️ status=FAILED 的记录要**正常返回**（200 + VO），不能也报 40400 ——
        //    「分析失败了」与「还没分析过」是两件事，前端据此决定是"重试"还是"等待"
        return toVO(entity);
    }

    // ==================== 私有 ====================

    /** 存在性 + 可见性校验（顺序见类注释），返回工单供调用方按需使用 */
    private Ticket requireVisibleTicket(long ticketId) {
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            throw BizException.notFound("工单不存在");
        }
        // 未登录时 UserContext.get() 为 null → Helper 判 NONE → 40301（fail-closed）
        dataScopeHelper.assertVisible(ticket, UserContext.get());
        return ticket;
    }

    /**
     * Entity → VO。
     *
     * <p>⚠️ 刻意<b>不映射 {@code rawResponse}</b>：§16.12 明确「模型原始返回仅入库用于排查」。
     * 这条由 {@code TicketAnalyzeServiceTest} 的反射断言钉住（字段清单精确匹配）。
     *
     * <p>与 {@code TicketAnalyzeServiceImpl#toVO} 是同构的转换 —— 一个在写路径、一个在读路径。
     * 暂不抽公共类（本单「允许改动」不含新工具类），但两边都会随 §16.12 的变更一起改。
     */
    private AiAnalysisVO toVO(AiAnalysis entity) {
        AiAnalysisVO vo = new AiAnalysisVO();
        vo.setId(entity.getId());
        vo.setTicketId(entity.getTicketId());
        vo.setModel(entity.getModel());
        vo.setType(entity.getType());
        vo.setCategory(entity.getCategory());
        vo.setPriority(entity.getPriority());
        vo.setSummary(entity.getSummary());
        vo.setSuggestions(fromJson(entity.getSuggestions()));
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /** JSON 列 → 数组；脏数据（不该出现）返回空数组并告警，不让出参变 null */
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        }
        catch (JsonProcessingException ex) {
            log.warn("[AI 分析] suggestions 列不是合法 JSON，已按空数组返回。值={}", json);
            return List.of();
        }
    }
}
