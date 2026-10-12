package com.opsdesk.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.ai.dto.AiAnalysisResult;
import com.opsdesk.ai.entity.AiAnalysis;
import com.opsdesk.ai.prompt.TicketAnalyzePrompts;
import com.opsdesk.ai.service.AiAnalysisService;
import com.opsdesk.ai.service.TicketAnalyzeService;
import com.opsdesk.ai.support.AiChatGateway;
import com.opsdesk.ai.support.RuleBasedAnalyzer;
import com.opsdesk.ai.vo.AiAnalysisVO;
import com.opsdesk.common.BizException;
import com.opsdesk.common.enums.AiAnalysisStatus;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 工单 AI 分析实现（工单 D10-02，规格基线 §10.1 / §10.2 / §10.3）
 *
 * <h2>整体流程</h2>
 * <pre>
 * analyze(ticketId)
 *   ├─ 读工单（不存在 → 返回 null，不分析也不落记录）
 *   ├─ gateway.call(...)  ── 走 D10-01 的出口：30s 总超时 + 统一 50300 语义
 *   │     └─ 返回无法解析 → 重试 1 次（§10.3 第 1 条）
 *   ├─ 逐字段校验枚举白名单（§10.3 第 2 条）→ 非法就回退为原工单值 + 告警
 *   ├─ 成功 → 落库 SUCCESS
 *   └─ 任何异常（含超时 / 开关关闭）→ <b>§10.4 降级为关键词规则引擎</b>并落库；
 *        规则引擎也失败 → 才落 FAILED（全程不向外抛，§10.2）
 * </pre>
 *
 * <h2>🔴 三条不能违反的约定</h2>
 * <ol>
 *   <li><b>绝不把异常抛给调用方</b>（§10.2）—— 整个方法体被 try-catch 兜住；</li>
 *   <li><b>失败也要落库</b>（§10.4 / §24.3）—— 先降级到规则引擎，连规则都失败才落 FAILED。
 *       总之不能让「这张工单为什么没有分类」变成一个查不到的问题；</li>
 *   <li><b>白名单校验只用枚举 {@code valueOf}</b>，不写字符串数组比对（§0 约定）。</li>
 * </ol>
 */
@Slf4j
@Service
public class TicketAnalyzeServiceImpl implements TicketAnalyzeService {

    /** {@code ai_analysis.summary} 是 VARCHAR(512) —— ⚠️ 超长会让 INSERT 直接失败，必须截断 */
    private static final int SUMMARY_MAX_LENGTH = 512;

    /** {@code raw_response} 是 TEXT，但模型可能返回超长内容（含大段解释）→ 截断防爆 */
    private static final int RAW_RESPONSE_MAX_LENGTH = 20_000;

    private final TicketService ticketService;
    private final AiAnalysisService aiAnalysisService;
    private final AiChatGateway aiChatGateway;
    private final ObjectMapper objectMapper;

    public TicketAnalyzeServiceImpl(TicketService ticketService,
                                    AiAnalysisService aiAnalysisService,
                                    AiChatGateway aiChatGateway,
                                    ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.aiAnalysisService = aiAnalysisService;
        this.aiChatGateway = aiChatGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiAnalysisVO analyze(long ticketId) {
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            log.warn("[AI 工单分析] 工单不存在，跳过分析。ticketId={}", ticketId);
            return null;
        }

        // 在 try 外声明：失败落库时要把「已经拿到的原始返回与模型名」一起存下去，方便排查
        String rawText = null;
        String model = null;
        try {
            Attempt attempt = callWithRetry(ticket);
            rawText = attempt.rawText();
            model = attempt.model();

            AiAnalysis entity = buildSuccess(ticket, attempt);
            aiAnalysisService.save(entity);
            // ⚠️ save 之后要重查一次：created_at 由 DB 的 DEFAULT CURRENT_TIMESTAMP 填，
            //    而 entity 上标了 FieldStrategy.NEVER（插入时不写该列）→ 不重查的话 VO 里没有生成时间
            return toVO(aiAnalysisService.getById(entity.getId()));
        }
        catch (Exception ex) {
            // §10.4 降级：AI 不可用（开关关闭 / 超时 / 报错）→ 改用关键词规则兜底。
            // ⚠️ 三种触发情况（① opsdesk.ai.enabled=false ② 调用异常 ③ 超时）在这里汇成一处：
            //    ① 与 ③ 由 D10-01 的出口统一抛 BizException(50300)，② 是各类上游异常。
            log.warn("[AI 工单分析] AI 不可用，降级为关键词规则引擎（§10.4）。ticketId={} 原因={}",
                    ticketId, ex.toString());
            return fallbackToRules(ticket, rawText);
        }
    }

    // ==================== 调用（含 1 次重试） ====================

    /**
     * 调用一次；返回内容无法解析时按 §10.3 第 1 条<b>重试 1 次</b>。
     *
     * <p>⚠️ 只有「拿到响应但解析不了」才重试。{@link BizException}（来自 D10-01 出口的 50300：
     * LLM 不可用 / 超时）不重试 —— 对端既然不可用，再等一次 30s 只是白耗时间，
     * §10.3 第 4 条也把「超时」直接定为失败。
     */
    private Attempt callWithRetry(Ticket ticket) {
        try {
            return callOnce(ticket);
        }
        catch (BizException ex) {
            throw ex;   // LLM 不可用 / 超时：直接判定失败，不重试
        }
        catch (Exception first) {
            log.warn("[AI 工单分析] 首次返回无法解析，重试 1 次（§10.3）。ticketId={} 原因={}",
                    ticket.getId(), first.toString());
            return callOnce(ticket);   // 第二次仍失败 → 异常冒到 analyze 的 catch，落 FAILED
        }
    }

    /**
     * 真正的一次调用。
     *
     * <p>用 {@code responseEntity(Class)} 而不是 {@code entity(Class)}：前者能同时拿到
     * 「解析后的对象 + 原始文本 + 模型名」，而 {@code raw_response} 必须入库（§16.12 只说不返回给前端）。
     */
    private Attempt callOnce(Ticket ticket) {
        ResponseEntity<ChatResponse, AiAnalysisResult> response = aiChatGateway.call("工单分析",
                client -> client.prompt()
                        .system(TicketAnalyzePrompts.SYSTEM)
                        .user(TicketAnalyzePrompts.userPrompt(ticket.getTitle(), ticket.getDescription()))
                        .options(aiChatGateway.structuredOptions())   // 温度 0.1（D10-01 提供，不动 yml 全局值）
                        .call()
                        .responseEntity(AiAnalysisResult.class));

        AiAnalysisResult result = response.getEntity();
        if (result == null) {
            throw new IllegalStateException("模型没有返回可解析的分析结果");
        }
        ChatResponse chatResponse = response.getResponse();
        return new Attempt(result, extractRawText(chatResponse), extractModel(chatResponse));
    }

    // ==================== 校验与组装 ====================

    /**
     * 校验枚举白名单并把结果组装成 Entity（§10.3 第 2 条：非法值回退为<b>原工单值</b>并告警）。
     *
     * <p>为什么回退目标是「原工单值」而不是硬编码 {@code OTHER}：
     * {@code TicketType} 这个枚举<b>根本没有 OTHER</b>（只有 INCIDENT / SERVICE_REQUEST），
     * 写死会立刻踩坑；而且语义上「AI 没判断出来 → 保持工单原本的值」也最自然。
     */
    private AiAnalysis buildSuccess(Ticket ticket, Attempt attempt) {
        AiAnalysisResult result = attempt.result();
        long ticketId = ticket.getId();

        TicketType type = resolve(result.getType(), TicketType.class, ticket.getType(), "type", ticketId);
        TicketCategory category = resolve(result.getCategory(), TicketCategory.class, ticket.getCategory(),
                "category", ticketId);
        TicketPriority priority = resolve(result.getPriority(), TicketPriority.class, ticket.getPriority(),
                "priority", ticketId);

        AiAnalysis entity = new AiAnalysis();
        entity.setTicketId(ticketId);
        entity.setModel(attempt.model());
        // ⚠️ entity 的 type 列是 VARCHAR(32)，而 category / priority 在实体里就是枚举
        entity.setType(type == null ? null : type.name());
        entity.setCategory(category);
        entity.setPriority(priority);
        entity.setSummary(truncate(result.getSummary(), SUMMARY_MAX_LENGTH));
        entity.setSuggestions(toJson(result.getSuggestions()));
        entity.setRawResponse(truncate(attempt.rawText(), RAW_RESPONSE_MAX_LENGTH));
        entity.setStatus(AiAnalysisStatus.SUCCESS);
        return entity;
    }

    /**
     * 枚举白名单校验：合法就返回枚举，非法/缺失就回退并记告警。
     *
     * <p>⚠️ 用 {@code Enum.valueOf} 而不是字符串数组比对 —— 白名单的唯一来源是枚举本身（§0 约定）。
     * <p>⚠️ {@code toUpperCase(Locale.ROOT)}：模型偶尔会返回小写；用 {@code Locale.ROOT} 是为了
     * 避开某些语言环境下 {@code i/I} 转换的特例。
     */
    private <E extends Enum<E>> E resolve(String rawValue, Class<E> enumType, E fallback,
                                          String field, long ticketId) {
        if (rawValue == null || rawValue.isBlank()) {
            log.warn("[AI 工单分析] {} 字段缺失，已回退为原工单值。ticketId={} 回退值={}",
                    field, ticketId, fallback);
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, rawValue.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            log.warn("[AI 工单分析] {} 取值不在白名单，已回退为原工单值。ticketId={} 非法值={} 回退值={}",
                    field, ticketId, rawValue, fallback);
            return fallback;
        }
    }

    // ==================== §10.4 降级 ====================

    /**
     * 规则引擎兜底：产出结果 → 走<b>同一套</b>校验与落库 → 返回。
     *
     * <p>⚠️ 这里刻意复用 {@link #buildSuccess}：降级产出的 {@link AiAnalysisResult}
     * 与 AI 产出的<b>是同一个类型</b>，所以枚举白名单校验、字段截断、JSON 序列化都不用重写，
     * 上层拿到的 {@code AiAnalysisVO} 也没有任何区别 —— 不需要为"降级"加分支。
     *
     * <p>⚠️ {@code status} 仍然是 {@code SUCCESS}：业务上这次分析<b>确实成功了</b>
     * （拿到了分类与优先级），只是产出方式不同；区分方式看 {@code model = rule-based}。
     * 把它们都标成 FAILED 会让"AI 分析成功率"这类统计完全失真。
     *
     * <p>⚠️ 降级也必须落库（§24.3 原文：「规则引擎兜底，{@code ai_analysis} 仍落库」）——
     * 否则事后看这张工单，会发现"既没有 AI 结果、也没有规则结果"，完全不知道分类是哪来的。
     */
    private AiAnalysisVO fallbackToRules(Ticket ticket, String rawText) {
        try {
            AiAnalysisResult result = RuleBasedAnalyzer.analyze(ticket.getTitle(), ticket.getDescription());
            // model 标记为 rule-based：事后排查分析质量时，第一眼就能区分 AI 产出与规则产出
            AiAnalysis entity = buildSuccess(ticket, new Attempt(result, rawText, RuleBasedAnalyzer.MODEL));
            aiAnalysisService.save(entity);
            log.info("[AI 工单分析] 已用规则引擎兜底并落库。ticketId={} category={} priority={}",
                    ticket.getId(), entity.getCategory(), entity.getPriority());
            return toVO(aiAnalysisService.getById(entity.getId()));
        }
        catch (Exception ex) {
            // 连规则兜底都失败（多半是数据库问题）→ 落一条 FAILED，仍然不向外抛（§10.2）
            log.error("[AI 工单分析] 规则引擎兜底也失败，落 FAILED 记录。ticketId={}", ticket.getId(), ex);
            return saveFailedQuietly(ticket.getId(), null, rawText);
        }
    }

    // ==================== 失败落库 ====================

    /**
     * 落一条 {@code FAILED} 记录。
     *
     * <p>§10.3 说失败时「前端不展示」，所以 {@code type/category/priority/summary/suggestions}
     * 一律留 {@code null}；只保留 {@code rawResponse}（如果有）供排查。
     *
     * <p>⚠️ 连失败记录都写不进去（比如数据库也挂了）时，只记日志、<b>绝不向外抛</b>（§10.2）。
     */
    private AiAnalysisVO saveFailedQuietly(long ticketId, String model, String rawText) {
        try {
            AiAnalysis entity = new AiAnalysis();
            entity.setTicketId(ticketId);
            entity.setModel(model);
            entity.setRawResponse(truncate(rawText, RAW_RESPONSE_MAX_LENGTH));
            entity.setStatus(AiAnalysisStatus.FAILED);
            aiAnalysisService.save(entity);
            return toVO(aiAnalysisService.getById(entity.getId()));
        }
        catch (Exception ex) {
            log.error("[AI 工单分析] 连 FAILED 记录都落库失败（不再向上抛，避免影响工单主流程）。ticketId={}",
                    ticketId, ex);
            return null;
        }
    }

    // ==================== 转换与工具 ====================

    /**
     * Entity → VO。
     *
     * <p>⚠️ {@code rawResponse} <b>不映射</b>：§16.12 明确不返回给前端
     * （由 {@code TicketAnalyzeServiceTest} 的反射断言钉住）。
     */
    private AiAnalysisVO toVO(AiAnalysis entity) {
        if (entity == null) {
            return null;
        }
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

    /** 建议列表 → JSON 字符串（{@code suggestions} 是 JSON 列）。空列表存 {@code null}，不存 {@code "[]"}。 */
    private String toJson(List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return null;   // §10.3：suggestions 为空是允许的，不算失败
        }
        try {
            return objectMapper.writeValueAsString(suggestions);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("处理建议序列化失败", ex);
        }
    }

    /** JSON 字符串 → 建议列表；脏数据（不该出现）时返回空列表并告警，不让出参变成 null */
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        }
        catch (JsonProcessingException ex) {
            log.warn("[AI 工单分析] suggestions 列不是合法 JSON，已按空数组返回。值={}", json);
            return List.of();
        }
    }

    private String extractRawText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private String extractModel(ChatResponse response) {
        return response == null || response.getMetadata() == null ? null : response.getMetadata().getModel();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    /** 一次成功调用的产物 */
    private record Attempt(AiAnalysisResult result, String rawText, String model) {
    }
}
