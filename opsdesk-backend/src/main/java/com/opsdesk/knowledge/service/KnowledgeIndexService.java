package com.opsdesk.knowledge.service;

/**
 * 知识文档索引（工单 D12-03，规格基线 §12.2 / §12.3）
 *
 * <h2>职责</h2>
 * 把一篇已上传的文档跑完整条链路：
 * <pre>
 *   读对象存储 → 解析（{@code KnowledgeDocumentParser}）→ 切分（{@code ChineseTextSplitter}）
 *     → 写 knowledge_chunk（含 vector_id）→ 批量写 Milvus → 更新文档状态
 * </pre>
 *
 * <h2>🔴 三条不能违反的约定</h2>
 * <ol>
 *   <li><b>幂等</b>：同一文档重复索引不产生重复 chunk（先清旧数据再重建）。
 *       MQ 层的 {@code ConsumeIdempotencyGuard} 只防「同一条消息重复消费」，
 *       防不了「同一文档被两条不同消息索引」，所以业务层必须自己幂等；</li>
 *   <li><b>绝不抛异常</b>：与 {@code TicketAnalyzeService#analyze} 同一模式。调用方是 MQ 消费者，
 *       抛出去会被重试 3 次然后进 DLQ —— 看起来像代码 bug，实际是外部依赖没起（§「外部依赖降级」）。
 *       失败一律 → 文档置 {@code FAILED} + 记日志，由运维在依赖恢复后手工重投；</li>
 *   <li><b>外部依赖不可用不能拖垮应用</b>：Milvus 由 {@code LazyMilvusBeansPostProcessor} 延迟创建，
 *       这里通过 {@code ObjectProvider} 取用，连不上只影响本次索引。</li>
 * </ol>
 *
 * <h2>调用方</h2>
 * 生产环境由 MQ 消费者调用（{@code KnowledgeIndexListener}，接线属 <b>D12-05</b>）；
 * 在那之前由测试直接调用验证。
 *
 * @see com.opsdesk.knowledge.splitter.ChineseTextSplitter
 */
public interface KnowledgeIndexService {

    /**
     * 索引指定文档。结果不通过返回值暴露 —— 去看 {@code knowledge_document.status}
     * （{@code READY} / {@code FAILED}）与 {@code chunk_count}。
     *
     * @param documentId 文档 id；为空或文档不存在时只记日志、直接返回
     */
    void index(Long documentId);
}
