package com.opsdesk.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.enums.KnowledgeDocStatus;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.knowledge.entity.KnowledgeChunk;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.parser.KnowledgeDocumentParser;
import com.opsdesk.knowledge.service.KnowledgeChunkService;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import com.opsdesk.knowledge.service.KnowledgeIndexService;
import com.opsdesk.knowledge.splitter.ChineseTextSplitter;
import com.opsdesk.knowledge.splitter.SplitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 文档索引实现（工单 D12-03，规格基线 §12.3 的管理流程）
 *
 * <h2>链路</h2>
 * <pre>
 *   读 MinIO → 解析 → 切分 → 写 knowledge_chunk（含预生成的 vector_id）
 *                               → 批量写 Milvus → 文档置 READY / FAILED
 * </pre>
 *
 * <h2>★ 为什么写入顺序是「先 chunk 后 Milvus」</h2>
 * Milvus 是<b>外部依赖、失败率更高</b>。把它放后面，一旦失败，补偿动作就是"清掉本地那批 chunk"
 * （同一进程内、可控）；反过来先写 Milvus 的话，失败补偿要删远端向量，更难保证。
 *
 * <h2>★ 为什么"两边一致"这件事能做得这么简单</h2>
 * 因为 {@code vector_id} 是<b>我们自己预先生成</b>的 UUID（而不是让 Spring AI 生成后回读）：
 * 同一个 id 既写 {@code knowledge_chunk.vector_id}，又作为 Milvus 的 {@code Document.id} ——
 * 两边在写之前就已经对齐了，不需要"写完回读再对账"。
 *
 * <h2>★ 幂等怎么保证</h2>
 * 每次索引都是「<b>先清掉该文档的全部旧 chunk（含旧向量），再写新的</b>」。
 * MQ 层的 {@code ConsumeIdempotencyGuard} 只防"同一条消息重复消费"，
 * 防不了"同一文档被两条消息索引"，所以业务层必须自己幂等。
 *
 * <h2>★ 失败为什么不能往外抛</h2>
 * 调用方是 MQ 消费者。抛出去 → 消息重试 3 次 → 进 DLQ，看起来像"代码 bug"，实际是 Milvus 没起。
 * 所以：<b>任何异常都在这里消化掉</b>，文档置 {@code FAILED} 并记日志，
 * 由运维在依赖恢复后手工重投（与 {@code TicketAnalyzeService#analyze} 同一模式）。
 * <b>{@code 50300} 不在这里用</b> —— 那是给 D12-04 的同步检索接口的（用户正等着结果的场景）。
 */
@Slf4j
@Service
public class KnowledgeIndexServiceImpl implements KnowledgeIndexService {

    private static final String META_DOCUMENT_ID = "documentId";
    private static final String META_CHUNK_INDEX = "chunkIndex";

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ObjectStorage objectStorage;
    private final List<KnowledgeDocumentParser> parsers;
    private final ChineseTextSplitter splitter;

    /**
     * ★ 用 {@link ObjectProvider} 而不是直接注入 {@link VectorStore}。
     *
     * <p>Milvus 的两个 bean 被 {@code LazyMilvusBeansPostProcessor} 设成了延迟创建，
     * 但<b>构造器注入会立刻触发创建</b>（必需依赖）—— 那样等于白改，启动时照样连 Milvus。
     * 用 provider 才能把"创建"推迟到真正要写向量的那一刻。
     */
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    private final TransactionTemplate transactionTemplate;

    public KnowledgeIndexServiceImpl(KnowledgeDocumentService knowledgeDocumentService,
                                     KnowledgeChunkService knowledgeChunkService,
                                     ObjectStorage objectStorage,
                                     List<KnowledgeDocumentParser> parsers,
                                     ChineseTextSplitter splitter,
                                     ObjectProvider<VectorStore> vectorStoreProvider,
                                     PlatformTransactionManager transactionManager) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.knowledgeChunkService = knowledgeChunkService;
        this.objectStorage = objectStorage;
        this.parsers = parsers;
        this.splitter = splitter;
        this.vectorStoreProvider = vectorStoreProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void index(Long documentId) {
        if (documentId == null) {
            log.warn("[知识库索引] documentId 为空，忽略本次调用");
            return;
        }
        try {
            doIndex(documentId);
        }
        catch (Exception ex) {
            // §«外部依赖降级»：绝不把异常丢给 MQ 消费者（会重试 3 次然后进 DLQ，看起来像代码 bug）
            log.error("[知识库索引] 索引失败，文档已标记为 FAILED。documentId={}", documentId, ex);
            fallbackMarkFailed(documentId);
        }
    }

    private void doIndex(Long documentId) {
        KnowledgeDocument document = knowledgeDocumentService.getById(documentId);
        if (document == null) {
            log.warn("[知识库索引] 文档不存在，跳过。documentId={}", documentId);
            return;
        }

        // ① 读原文 → 解析 → 切分
        String text = parseText(document);
        List<SplitResult> results = splitter.split(text);

        // ② 空文档：解析本身是成功的，只是没内容 → READY + chunk_count=0（不算索引失败，
        //    否则「因依赖不可用而失败」与「文件是空的」会混进同一个统计口径里）
        if (results.isEmpty()) {
            log.info("[知识库索引] 文档内容为空，清空旧数据并置 READY(chunkCount=0)。documentId={}", documentId);
            List<String> oldVectorIds = vectorIdsOf(documentId);
            replaceChunks(documentId, List.of());
            deleteVectorsQuietly(oldVectorIds);
            updateStatus(documentId, KnowledgeDocStatus.READY, 0);
            return;
        }

        // ③ 预生成 vectorId，同时组装两边的数据（id 已经对齐，见类注释）
        List<KnowledgeChunk> chunks = new ArrayList<>(results.size());
        List<Document> documents = new ArrayList<>(results.size());
        for (SplitResult result : results) {
            String vectorId = UUID.randomUUID().toString();
            chunks.add(toChunk(documentId, result, vectorId));
            documents.add(toVectorDocument(documentId, result, vectorId));
        }

        // ④ 幂等：先取出旧向量 id（删 Milvus 要用），再在同一个事务里把旧 chunk 换成新的
        List<String> oldVectorIds = vectorIdsOf(documentId);
        replaceChunks(documentId, chunks);

        // ⑤ 清掉旧的远端向量（尽力而为），再批量写新的
        deleteVectorsQuietly(oldVectorIds);
        VectorStore vectorStore = requireVectorStore();
        try {
            vectorStore.add(documents); // 一次传一批；内部分批由框架的 BatchingStrategy 负责
        }
        catch (Exception ex) {
            // 就地补偿：此刻 vectorIds 就在手上，用同一个 client 删，不会再触发一次"取向量库"
            log.warn("[知识库索引] 写入向量库失败，清理本轮已写入的向量。documentId={} 条数={}",
                    documentId, documents.size(), ex);
            List<String> written = documents.stream().map(Document::getId).toList();
            deleteVectorsQuietly(written);
            throw ex; // 交给外层统一置 FAILED
        }

        // ⑥ 成功
        updateStatus(documentId, KnowledgeDocStatus.READY, chunks.size());
        log.info("[知识库索引] 完成。documentId={} chunkCount={}", documentId, chunks.size());
    }

    // ==================== 读原文 / 解析 ====================

    private String parseText(KnowledgeDocument document) {
        String extension = extensionOf(document.getFilePath());
        KnowledgeDocumentParser parser = parsers.stream()
                .filter(candidate -> candidate.supports(extension))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR,
                        "没有可处理 ." + extension + " 的解析器（文档 id=" + document.getId() + "）"));
        try (InputStream in = objectStorage.get(document.getFilePath())) {
            return parser.parse(in, extension);
        }
        catch (BizException ex) {
            throw ex; // 解析器自己的业务异常（如非 UTF-8）原样透传，外层统一置 FAILED
        }
        catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "读取文档内容失败：" + document.getFilePath(), ex);
        }
    }

    /** 扩展名从对象名取（它由服务端自造、已归一化，比原始文件名可靠） */
    private String extensionOf(String objectName) {
        int dot = objectName == null ? -1 : objectName.lastIndexOf('.');
        return dot < 0 ? "" : objectName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ==================== chunk 落库 / 向量库 ====================

    /**
     * 用新 chunk 替换该文档的全部旧 chunk。
     *
     * <p>「删旧 + 插新」在<b>同一个事务</b>里：不然删掉之后插失败，就会留下一个"什么都没有"的文档。
     * MyBatis-Plus 的 {@code saveBatch} 在条数超过 batchSize 时会拆成多条 INSERT，本身不原子，
     * 所以这里必须显式包事务。
     */
    private void replaceChunks(Long documentId, List<KnowledgeChunk> chunks) {
        transactionTemplate.executeWithoutResult(status -> {
            knowledgeChunkService.remove(new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getDocumentId, documentId));
            if (!chunks.isEmpty()) {
                knowledgeChunkService.saveBatch(chunks);
            }
        });
    }

    private List<KnowledgeChunk> chunksOf(Long documentId) {
        return knowledgeChunkService.list(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId, documentId));
    }

    private List<String> vectorIdsOf(Long documentId) {
        return chunksOf(documentId).stream()
                .map(KnowledgeChunk::getVectorId)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 删掉 Milvus 里的指定向量，失败只记 warn。
     *
     * <p>删不掉不致命：新向量用的是<b>新 UUID</b>，不会与旧数据撞 id；
     * 残留的旧向量最多是"能检索到、但回查不到 chunk"的脏数据。
     * 而这里如果因为 Milvus 不可用而抛出去，会把"重建失败"升级成"整个索引失败"——
     * 没必要为了清垃圾而搞砸主流程。
     */
    private void deleteVectorsQuietly(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return; // 提前返回很重要：避免为"没有旧数据"的无用清空去触发一次向量库创建（Milvus 不可用时那是 10 秒）
        }
        try {
            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore != null) {
                vectorStore.delete(vectorIds);
            }
        }
        catch (Exception ex) {
            log.warn("[知识库索引] 清理旧向量失败（不阻断重建），条数={}", vectorIds.size(), ex);
        }
    }

    /**
     * 取向量库 —— 首次调用时才真正创建（见 {@code LazyMilvusBeansPostProcessor}）。
     *
     * <p>{@code getIfAvailable()} 的两种"没有"要分清：返回 {@code null} 表示<b>压根没装配</b>这个 bean；
     * 而<b>创建失败</b>（Milvus 连不上）会抛 {@link BeansException}。后者翻译成一句人话，
     * 让日志一眼能看出"是环境没起"而不是"代码有 bug"。
     */
    private VectorStore requireVectorStore() {
        try {
            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                        "向量库未装配（spring.ai.vectorstore.type 是否已启用？）");
            }
            return vectorStore;
        }
        catch (BeansException ex) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                    "向量库（Milvus）不可用，请确认服务已启动", ex);
        }
    }

    // ==================== 组装 ====================

    private KnowledgeChunk toChunk(Long documentId, SplitResult result, String vectorId) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(documentId);
        chunk.setContent(result.content());
        chunk.setChunkIndex(result.index());
        chunk.setVectorId(vectorId);
        return chunk;
    }

    /**
     * 组装交给 Milvus 的 {@link Document}。
     *
     * <p>★ {@code id} <b>由我们指定</b>（不是让 Spring AI 生成）—— 这样它和
     * {@code knowledge_chunk.vector_id} 是同一个值，不需要回读 Milvus。
     *
     * <p>metadata 带 {@code documentId} / {@code chunkIndex}：检索命中后要靠它回查
     * {@code knowledge_document}（拿文档标题做引用，§12.2「回答必须返回命中的文档标题 + 片段」）
     * 与 {@code knowledge_chunk} 原文。
     */
    private Document toVectorDocument(Long documentId, SplitResult result, String vectorId) {
        return Document.builder()
                .id(vectorId)
                .text(result.content())
                .metadata(Map.of(
                        META_DOCUMENT_ID, documentId,
                        META_CHUNK_INDEX, result.index()))
                .build();
    }

    // ==================== 状态 ====================

    private void updateStatus(Long documentId, KnowledgeDocStatus status, Integer chunkCount) {
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(documentId);
        update.setStatus(status);
        update.setChunkCount(chunkCount); // MyBatis-Plus 默认忽略 null 字段，不需要额外的分支
        knowledgeDocumentService.updateById(update);
    }

    /**
     * 最外层兜底：清掉该文档的 chunk 行 + 置 {@code FAILED}。
     *
     * <p>这里<b>刻意不碰 Milvus</b>：走到这一步说明失败点不明确，而"取向量库"本身就可能正是失败原因
     * （Milvus 连不上会阻塞约 10 秒）—— 兜底路径再阻塞一次没有意义。
     * 真正"写向量失败"的那条路径已经在 {@link #doIndex} 里就地清理过了。
     *
     * <p>补偿自身失败也只记日志：孤儿 chunk 行不致命，<b>下次索引的第一步就是清掉该文档的旧 chunk</b>，
     * 所以这套流程天然自愈。
     */
    private void fallbackMarkFailed(Long documentId) {
        try {
            replaceChunks(documentId, List.of());
        }
        catch (Exception ex) {
            log.error("[知识库索引] 失败补偿：清理 chunk 未完成（下次索引会自愈）documentId={}", documentId, ex);
        }
        try {
            updateStatus(documentId, KnowledgeDocStatus.FAILED, 0);
        }
        catch (Exception ex) {
            log.error("[知识库索引] 失败补偿：置 FAILED 未成功 documentId={}", documentId, ex);
        }
    }
}
