package com.opsdesk.knowledge.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.PageQuery;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.knowledge.service.KnowledgeSearchService;
import com.opsdesk.knowledge.vo.KnowledgeSearchItemVO;
import com.opsdesk.knowledge.vo.RagAnswerVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库检索接口（工单 D12-04，API §10.5 / §10.6）
 *
 * <pre>
 * GET  /api/knowledge/search?keyword=VPN&amp;page=1&amp;size=10    关键词检索（分页）
 * POST /api/knowledge/rag-query                               语义检索 + 带引用的回答
 * </pre>
 *
 * <h2>为什么两个路径都在 {@code /api/knowledge} 下、却不合成一个接口</h2>
 * 规格 §12.4 明确「两者接口分离，避免语义混淆」—— 一个是字面命中、一个是语义召回，
 * 返回结构也不同（分页列表 vs answer + references）。
 *
 * <h2>⚠️ 类级没有 {@code @RequestMapping}</h2>
 * 两个路径虽然同前缀，但**方法不同**（GET / POST），且本项目其它 Controller 也统一写全路径
 * （{@code KnowledgeDocumentController} 同样如此）—— 保持一致，避免"类级前缀 + 方法级路径"
 * 拼出来到底是什么要来回对。
 */
@RestController
public class KnowledgeSearchController {

    /** §10.6：question ≤ 500 */
    private static final int MAX_QUESTION_LENGTH = 500;

    private final KnowledgeSearchService knowledgeSearchService;

    public KnowledgeSearchController(KnowledgeSearchService knowledgeSearchService) {
        this.knowledgeSearchService = knowledgeSearchService;
    }

    /**
     * §10.6 的请求体。
     *
     * <p>⚠️ 做成 Controller 的<b>内嵌 record</b> 而不是独立 DTO 文件：工单「允许改动」只列了
     * Controller / Service+impl / 两个 VO，没有 DTO。校验仍然走 Jakarta Validation
     * （失败由全局处理器转 40001，与 D11-04 的 {@code AiChatDTO} 同一套）。
     * 若将来前端要复用这个契约，再像 D11-04 那样提升成独立的 `RagQueryDTO`。
     */
    public record RagQueryRequest(
            @NotBlank(message = "问题不能为空")
            @Size(max = MAX_QUESTION_LENGTH, message = "问题不能超过 " + MAX_QUESTION_LENGTH + " 字")
            String question,

            /** 召回条数；null 取默认 5，超过上限 10 时收敛为 10（§10.6） */
            Integer topK) {
    }

    /**
     * 关键词检索（§10.5）。鉴权 {@code knowledge:view}（EMPLOYEE / AGENT / ADMIN 都有）。
     *
     * <p>{@code keyword} 允许为空：用户没输关键词不是错误，返回空分页即可（不报 40001）。
     * {@code page} / {@code size} 由 {@link PageQuery} 绑定并收敛（size 上限 50）。
     */
    @RequirePermission(PermissionCodes.KNOWLEDGE_VIEW)
    @GetMapping("/api/knowledge/search")
    public Result<PageResult<KnowledgeSearchItemVO>> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            PageQuery pageQuery) {
        return Result.ok(knowledgeSearchService.search(keyword, pageQuery));
    }

    /**
     * RAG 语义问答（§10.6）。鉴权 {@code knowledge:view}。
     *
     * <p>Milvus / Embedding 不可用时<b>内部降级为关键词检索</b>（§24.3，接口不变）；
     * 只有连 LLM 也不可用才返回 {@code 50300}。
     */
    @RequirePermission(PermissionCodes.KNOWLEDGE_VIEW)
    @PostMapping("/api/knowledge/rag-query")
    public Result<RagAnswerVO> ragQuery(@Valid @RequestBody RagQueryRequest request) {
        return Result.ok(knowledgeSearchService.ragQuery(request.question(), request.topK()));
    }
}
