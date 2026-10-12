package com.opsdesk.knowledge.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.knowledge.service.KnowledgeDocumentManageService;
import com.opsdesk.knowledge.service.KnowledgeDocumentManageService.KnowledgeDocumentUploadResult;
import com.opsdesk.knowledge.service.KnowledgeDocumentManageService.KnowledgeDocumentVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档接口（工单 D12-01，API 文档 §10.2 上传 / §10.3 详情）
 *
 * <pre>
 * POST /api/knowledge/documents          multipart：file（≤20MB）+ title（可空）  权限 knowledge:manage
 * GET  /api/knowledge/documents/{id}     详情（供前端轮询状态）                    权限 knowledge:view
 * </pre>
 *
 * <h2>🔴 上传接口立即返回，不做解析</h2>
 * §12.3 的流水线（解析 → 切分 → Embedding → 写 Milvus）**不在这个请求里执行** ——
 * 20MB 文档解析 + 向量化会远超 HTTP 超时，而且失败重试也没地方放。
 * 所以接口只做「落库 PROCESSING + 落对象存储」，前端拿 id 去轮询 §10.3。
 *
 * <p>解析器（{@code knowledge/parser}）本单已交付并单测覆盖，由 D12-05 的 MQ 消费者调用。
 */
@RestController
public class KnowledgeDocumentController {

    private final KnowledgeDocumentManageService knowledgeDocumentManageService;

    public KnowledgeDocumentController(KnowledgeDocumentManageService knowledgeDocumentManageService) {
        this.knowledgeDocumentManageService = knowledgeDocumentManageService;
    }

    /**
     * 上传文档（§10.2）。
     *
     * @param file  文档（md / txt，≤20MB）
     * @param title 标题，可空 → 默认取文件名（去扩展名）
     * @return {@code { "id": 3, "status": "PROCESSING" }}
     */
    @RequirePermission(PermissionCodes.KNOWLEDGE_MANAGE)
    @PostMapping("/api/knowledge/documents")
    public Result<KnowledgeDocumentUploadResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        return Result.ok(knowledgeDocumentManageService.upload(file, title));
    }

    /**
     * 文档详情（§10.3）—— 前端靠它轮询 {@code PROCESSING → READY / FAILED}。
     *
     * <p>权限用 {@code knowledge:view}：§22 的权限表里「知识库查看」是所有登录角色都有的，
     * 只有「知识库管理」才是 ADMIN。
     */
    @RequirePermission(PermissionCodes.KNOWLEDGE_VIEW)
    @GetMapping("/api/knowledge/documents/{id}")
    public Result<KnowledgeDocumentVO> detail(@PathVariable long id) {
        return Result.ok(knowledgeDocumentManageService.detail(id));
    }
}
