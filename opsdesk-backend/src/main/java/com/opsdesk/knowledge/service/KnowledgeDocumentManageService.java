package com.opsdesk.knowledge.service;

import com.opsdesk.common.enums.KnowledgeDocStatus;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * 知识文档管理（工单 D12-01，API 文档 §10.2 / §10.3，规格基线 §12.3）
 *
 * <h2>本单只做「上传 + 落库（PROCESSING）」</h2>
 * §12.3 的完整流水线是
 * {@code 解析 → 切分（500 字 / overlap 50）→ Embedding → 写 Milvus}，
 * 其中**解析器本单已交付**（{@code knowledge/parser}），但**不在上传请求里执行** ——
 * 那会让接口超时（工单明令禁止）。整条流水线由 D12-05 的 MQ 消费者驱动：
 * {@code KnowledgeIndexListener} 里已经留好了 {@code TODO(D12-05)} 的调用点。
 */
public interface KnowledgeDocumentManageService {

    /**
     * 上传文档（§10.2）：落库 {@code PROCESSING} + 落对象存储，**立即返回**。
     *
     * @param file  上传文件（md / txt，≤20MB）
     * @param title 文档标题；<b>可空</b> —— 为空时取文件名（去掉扩展名）
     * @return 新文档的 id 与状态（恒为 {@code PROCESSING}）
     * @throws com.opsdesk.common.BizException 40001 —— 空文件 / 超 20MB / 类型不在白名单
     */
    KnowledgeDocumentUploadResult upload(MultipartFile file, String title);

    /**
     * 文档详情（§10.3，供前端轮询 {@code PROCESSING → READY / FAILED}）。
     *
     * @throws com.opsdesk.common.BizException 40400 —— 文档不存在
     */
    KnowledgeDocumentVO detail(Long id);

    /**
     * 删除文档（工单 D12-05，API §10.4，权限 {@code knowledge:manage}）。
     *
     * <h2>🔴 三处一起清，顺序是「先向量、再 DB」</h2>
     * <pre>
     *   ① 删 Milvus 向量（按 metadata 里的 documentId 过滤）
     *   ② 删 knowledge_chunk
     *   ③ 删 knowledge_document（物理删除 —— DDL 没有 deleted 列）
     *   ④ 删 MinIO 对象（失败只记 warn，不阻断）
     * </pre>
     * 规格 §12.3 的原话是「同步删除 {@code knowledge_chunk} 与对应向量，<b>避免孤儿向量</b>」。
     * 之所以<b>先删向量</b>：万一删向量失败，此时 DB 还完整、用户重试即可；
     * 反过来先删 DB 的话，{@code vector_id} 随行一起消失，Milvus 里那批向量就<b>再也找不回来了</b>。
     *
     * @throws com.opsdesk.common.BizException 40400 文档不存在；50300 向量库不可用（<b>此时 DB 一行都不动</b>）
     */
    void delete(Long id);

    /**
     * 上传结果（API §10.2 的响应 {@code data}）。
     *
     * <p>就两个字段 —— 上传接口不返回文档内容、也不返回解析结果（都还没有）。
     */
    record KnowledgeDocumentUploadResult(Long id, KnowledgeDocStatus status) {
    }

    /**
     * 文档详情（API §10.3）。
     *
     * <p>⚠️ <b>不返回 {@code filePath}（对象名）</b>：那是内部存储细节，
     * 对前端没有任何用处，暴露出去只是给攻击者多一个信息点（文件名里有 UUID 也无所谓，
     * 但没必要给）。
     */
    record KnowledgeDocumentVO(Long id, String title, String fileName, KnowledgeDocStatus status,
                               Integer chunkCount, LocalDateTime createdAt) {
    }
}
