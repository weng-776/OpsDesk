package com.opsdesk.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.audit.annotation.AuditLog;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.common.enums.KnowledgeDocStatus;
import com.opsdesk.common.enums.OutboxStatus;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.common.utils.LikeEscaper;
import com.opsdesk.knowledge.dto.KnowledgeDocumentQuery;
import com.opsdesk.knowledge.entity.KnowledgeChunk;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.parser.KnowledgeDocumentTypes;
import com.opsdesk.knowledge.service.KnowledgeChunkService;
import com.opsdesk.knowledge.service.KnowledgeDocumentManageService;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import com.opsdesk.mq.entity.EventOutbox;
import com.opsdesk.mq.event.KnowledgeIndexEvent;
import com.opsdesk.mq.service.EventOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * 知识文档管理实现（上传 D12-01 / 详情 D12-01 / 删除 D12-05，SOP §5 红区：文件安全边界）
 *
 * <h2>上传的校验与处理顺序</h2>
 * <pre>
 * ① 文件非空            → 40001
 * ② 大小 ≤ 20MiB        → 40001
 * ③ 扩展名 + MIME 白名单 → 40001
 * ④ 落库（PROCESSING）→ 拿 documentId
 * ⑤ 落 MinIO（对象名含 documentId）
 * ⑥ 回写 file_path
 * ⑦ 写 event_outbox（KnowledgeIndexEvent，§13.3）← 由 OutboxPublishJob 投递到 MQ
 * ⑧ 立即返回（解析/切分/向量化由消费者异步做）
 * </pre>
 * ⚠️ 与 {@code AttachmentServiceImpl} 的顺序<b>不同</b>，这不是疏漏：
 * 附件要先判「工单是否存在 + 有没有数据范围」（否则能从报错形态推断出工单存不存在），
 * 而知识文档<b>没有"属于谁"这个概念</b>（§22：所有登录角色都能查看知识库），
 * 所以这里直接从文件校验开始。
 *
 * <h2>删除：先向量、再 DB（§12.3「避免孤儿向量」）</h2>
 * 见 {@link #delete(Long)} 的注释 —— 顺序反了会让 Milvus 里的向量变成<b>永久孤儿</b>。
 *
 * <h2>🔴 防路径穿越：沿用 D3-05 的硬规矩</h2>
 * <pre>
 * 对象名 = "knowledge/" + documentId + "/" + UUID + "." + 已验证扩展名
 * </pre>
 * <b>原始文件名一个字符都不进路径</b>。因此 {@code ../../etc/passwd.md} 这类输入
 * 在结构上无法生效 —— 它只会作为展示字符串落进 {@code file_name} 列。
 * 这与「先 sanitize 再用」有本质区别：sanitize 是黑名单（总有绕过方式），
 * 自造名是白名单（<b>根本没有外部输入进去</b>）。
 *
 * <h2>⚠️ 为什么是「insert → put → update」三步，而不是像附件那样「put → save」</h2>
 * 附件的对象名是 {@code ticket/{ticketId}/...}，而 ticketId <b>上传前就知道</b>；
 * 知识文档的对象名含 {@code documentId}，而它是<b>自增主键、insert 之后才有</b> → 只能：
 * <pre>
 * insert（file_path 先写占位 knowledge/pending/{uuid}.{ext}）
 *   → 拿到 id → put 到 knowledge/{id}/{uuid}.{ext}
 *   → update file_path
 * </pre>
 * 代价是多一次 UPDATE，换来的是「对象按文档目录聚合」。整个流程在一个事务里；
 * put 失败则事务回滚、DB 不留记录（MinIO 里可能残留一个孤儿对象 —— 与 D3-05 同一已知取舍）。
 */
@Slf4j
@Service
public class KnowledgeDocumentManageServiceImpl implements KnowledgeDocumentManageService {

    /**
     * 文档大小上限 20MB（§12.3）。
     *
     * <p>⚠️ 用 {@code 20 * 1024 * 1024} 而不是 {@code 20_000_000}：
     * yml 里的 {@code 20MB} 是 Spring 的 DataSize 语法 = 20 × 1024 × 1024。
     * 两处口径不同（一边 20MiB、一边 20MB）会造出「yml 放行了、业务层拒了」的诡异边界。
     */
    private static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    /** 对象名前缀：所有知识库文档都在这个目录下，便于 bucket 内做生命周期策略 */
    private static final String OBJECT_PREFIX = "knowledge/";

    /** 占位目录：insert 时还不知道自增 id，先把 file_path 写在这下面 */
    private static final String PENDING_PREFIX = OBJECT_PREFIX + "pending/";

    /** 标题列是 VARCHAR(255) */
    private static final int MAX_TITLE_LENGTH = 255;

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ObjectStorage objectStorage;

    /**
     * ⚠️ 用 {@link ObjectProvider} 而不是直接注入 {@link VectorStore}：
     * Milvus 的两个 bean 被 {@code LazyMilvusBeansPostProcessor} 设成了延迟创建（D12-03），
     * 直接注入会立刻触发创建、把启动拖下水。只有真要删向量时才取。
     */
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /** 上传后写 {@code event_outbox}（§13.3 / §13.5），由 OutboxPublishJob 投递 */
    private final EventOutboxService eventOutboxService;

    private final ObjectMapper objectMapper;

    public KnowledgeDocumentManageServiceImpl(KnowledgeDocumentService knowledgeDocumentService,
                                              KnowledgeChunkService knowledgeChunkService,
                                              ObjectStorage objectStorage,
                                              ObjectProvider<VectorStore> vectorStoreProvider,
                                              EventOutboxService eventOutboxService,
                                              ObjectMapper objectMapper) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.knowledgeChunkService = knowledgeChunkService;
        this.objectStorage = objectStorage;
        this.vectorStoreProvider = vectorStoreProvider;
        this.eventOutboxService = eventOutboxService;
        this.objectMapper = objectMapper;
    }

    // ==================== 上传（§10.2） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(operation = AuditOperation.KNOWLEDGE_DOC_UPLOAD,
            resourceType = AuditResourceType.KNOWLEDGE_DOC,
            resourceId = "#result.id",
            skipIfAlreadyAudited = true)
    public KnowledgeDocumentUploadResult upload(MultipartFile file, String title) {
        // ① 文件非空
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "上传文件不能为空");
        }

        // ② 大小（业务层闸门，与 yml 的 20MB 同口径）
        long size = file.getSize();
        if (size > MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "文档大小不能超过 20MB（当前 " + humanSize(size) + "）");
        }

        // ③ 扩展名 + MIME 白名单（md / txt；docx / pdf 会给"暂不支持"的说明）
        String originalName = file.getOriginalFilename();
        String ext = KnowledgeDocumentTypes.validate(originalName, file.getContentType());

        // ④ 落库：状态 PROCESSING、chunk_count = 0（§12.3）
        String uuid = UUID.randomUUID().toString();
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(resolveTitle(title, originalName));
        // 原始文件名只落这一列，仅用于展示（DDL 注释：不参与路径拼接）
        document.setFileName(sanitizeDisplayName(originalName));
        document.setFilePath(PENDING_PREFIX + uuid + "." + ext);
        document.setStatus(KnowledgeDocStatus.PROCESSING);
        document.setChunkCount(0);
        document.setUploaderId(UserContext.requireUserId());
        knowledgeDocumentService.save(document);

        // ⑤ 落 MinIO：对象名含 documentId（此时才知道），UUID 复用④里那个
        String objectName = buildObjectName(document.getId(), uuid, ext);
        assertSafeObjectName(objectName);
        try (InputStream in = file.getInputStream()) {
            objectStorage.put(objectName, in, size, file.getContentType());
        }
        catch (IOException ex) {
            log.error("[知识文档上传] 读取上传流失败 documentId={} fileName={}", document.getId(), originalName, ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "读取上传文件失败");
        }

        // ⑥ 回写真实对象名（覆盖④的占位值）
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(document.getId());
        update.setFilePath(objectName);
        knowledgeDocumentService.updateById(update);

        // ⑦ 写 outbox：KnowledgeIndexEvent（§13.3）。必须与落库在【同一事务】里 ——
        //    否则事务回滚了事件却已经发出，消费者会拿着一个并不存在的 documentId 去索引
        writeIndexOutbox(document.getId());

        log.info("[知识文档上传] 成功。documentId={} uploaderId={} size={} object={} ext={}",
                document.getId(), document.getUploaderId(), size, objectName, ext);

        // ⑧ 立即返回 —— 解析/切分/向量化由 MQ 消费者异步执行（D12-05），与本次请求无关
        return new KnowledgeDocumentUploadResult(document.getId(), KnowledgeDocStatus.PROCESSING);
    }

    /**
     * 写 {@code KnowledgeIndexEvent} 到 outbox（§13.3 / §13.5）。
     *
     * <p>载荷直接序列化事件对象，<b>不手搓 Map</b> —— D9-02 修过这个坑：
     * 手写 Map 与事件类的字段集合一旦漂移，消费端反序列化时缺的字段会静默变成 {@code null}。
     */
    private void writeIndexOutbox(Long documentId) {
        KnowledgeIndexEvent event = new KnowledgeIndexEvent(documentId);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        }
        catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "事件载荷序列化失败", ex);
        }

        EventOutbox outbox = new EventOutbox();
        outbox.setEventType(KnowledgeIndexEvent.EVENT_TYPE);
        // messageId 是消费端幂等键（§13.6），必须唯一（uk_outbox_message_id）
        outbox.setMessageId(UUID.randomUUID().toString());
        // 路由键复用事件类里的常量，不写裸字符串
        outbox.setRoutingKey(KnowledgeIndexEvent.ROUTING_KEY);
        outbox.setPayload(payloadJson);
        outbox.setStatus(OutboxStatus.PENDING);
        eventOutboxService.save(outbox);
    }

    // ==================== 列表（补单 · 知识库文档列表，§10.1） ====================

    /**
     * 文档分页列表（§10.1）。
     *
     * <h2>为什么只查 {@code knowledge_document} 一张表</h2>
     * {@code chunkCount} 直接读表里的 {@code chunk_count} 列（D12-03 回写）。
     * 若改成 {@code count(*) knowledge_chunk} 子查询，10 行就是 10 次额外查询，
     * 而且"快照列 vs 实时统计"必然对不上 —— 详情接口读的也是这一列，
     * 列表和详情必须是同一个来源，否则用户会发现"列表说 12 段、详情说 13 段"。
     *
     * <h2>⚠️ 没有数据范围过滤</h2>
     * §22：知识库对所有登录角色可见，{@code knowledge_document} 也没有"属于某个部门"的字段。
     * 所以本方法<b>刻意不加</b>任何可见性条件 —— 这里不是"忘了",是规格如此。
     */
    @Override
    public PageResult<KnowledgeDocumentVO> page(KnowledgeDocumentQuery query) {
        KnowledgeDocumentQuery q = query == null ? new KnowledgeDocumentQuery() : query;
        long pageNo = q.pageOrDefault();
        long size = q.sizeOrDefault();

        // 只取 VO 需要的 6 列 —— 不 SELECT *，也就不会把 file_path 带出来
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<KnowledgeDocument>()
                .select(KnowledgeDocument::getId, KnowledgeDocument::getTitle, KnowledgeDocument::getFileName,
                        KnowledgeDocument::getStatus, KnowledgeDocument::getChunkCount,
                        KnowledgeDocument::getCreatedAt);

        if (q.getStatus() != null) {
            wrapper.eq(KnowledgeDocument::getStatus, q.getStatus());
        }

        String keyword = trimToNull(q.getKeyword());
        if (keyword != null) {
            String escaped = LikeEscaper.escape(keyword);
            // ⚠️ 用 and(...) 把 OR 括起来：否则 OR 会跟其它条件平级，
            //    把 status 过滤"泄漏"出去（与 TicketQueryServiceImpl / KnowledgeSearchServiceImpl 同一个坑）
            wrapper.and(w -> w.like(KnowledgeDocument::getTitle, escaped)
                    .or().like(KnowledgeDocument::getFileName, escaped));
        }

        // created_at 精度到秒：同秒上传的多份文档只按它排，MySQL 不保证稳定顺序 → 翻页跳行/重复。
        // 追加唯一列 id 做 tiebreaker（与 §8.3 工单列表同一个理由）
        wrapper.orderByDesc(KnowledgeDocument::getCreatedAt).orderByDesc(KnowledgeDocument::getId);

        Page<KnowledgeDocument> page = knowledgeDocumentService.page(new Page<>(pageNo, size), wrapper);
        List<KnowledgeDocumentVO> list = page.getRecords().stream().map(this::toVO).toList();
        log.debug("[知识库列表] page={} size={} status={} keyword={} total={} 命中 {} 行",
                pageNo, size, q.getStatus(), keyword, page.getTotal(), list.size());
        return PageResult.of(list, page.getTotal(), pageNo, size);
    }

    // ==================== 详情（§10.3） ====================

    @Override
    public KnowledgeDocumentVO detail(Long id) {
        KnowledgeDocument document = id == null ? null : knowledgeDocumentService.getById(id);
        if (document == null) {
            throw BizException.notFound("文档不存在");
        }
        return toVO(document);
    }

    /**
     * 实体 → 出参（列表与详情共用，保证两边字段口径一致）。
     *
     * <p>⚠️ <b>不带 {@code filePath}</b>：对象键是存储内部细节，对前端无用，
     * 暴露出去只是多一个信息点。列表和详情都必须遵守这一条。
     */
    private KnowledgeDocumentVO toVO(KnowledgeDocument document) {
        return new KnowledgeDocumentVO(
                document.getId(),
                document.getTitle(),
                document.getFileName(),
                document.getStatus(),
                document.getChunkCount(),
                document.getCreatedAt());
    }

    // ==================== 删除（§10.4，工单 D12-05） ====================

    /**
     * 删除文档：清 Milvus 向量 → 清 chunk → 清文档 → 清对象（§12.3「避免孤儿向量」）。
     *
     * <p>🔴 <b>顺序不能反</b>：先删向量，删失败就直接 50300、DB 一行不动，用户重试即可；
     * 反之先删 DB 的话，{@code vector_id} 随行一起消失，Milvus 里那批向量就<b>再也定位不到</b>了。
     *
     * <p>{@code knowledge_document} 是<b>物理删除</b>（DDL 没有 {@code deleted} 列，已核实）——
     * 所以工单里担心的「逻辑删除 + 向量物理删除 = 记录还在但向量没了」这种不一致在本项目不会发生。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(operation = AuditOperation.KNOWLEDGE_DOC_DELETE,
            resourceType = AuditResourceType.KNOWLEDGE_DOC,
            resourceId = "#id")
    public void delete(Long id) {
        // ① 先判存在性 —— 删一个不存在的 id 不该返回成功，否则调用方分不清"删掉了"和"本来就没有"
        KnowledgeDocument document = id == null ? null : knowledgeDocumentService.getById(id);
        if (document == null) {
            throw BizException.notFound("文档不存在");
        }

        // ② 删 Milvus 向量 —— 失败就抛 50300，DB 保持原样（"先向量后 DB"的全部意义在此）
        deleteVectorsOf(id);

        // ③ 删 chunk
        knowledgeChunkService.remove(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId, id));

        // ④ 删文档（物理删除）
        knowledgeDocumentService.removeById(id);

        // ⑤ 删对象存储里的文件 —— 放最后，且失败只记 warn：
        //    走到这一步 DB 已经干净了，为"顺手删个文件"把整个删除事务回滚掉是本末倒置；
        //    而且存储里的孤儿对象只占空间，不像孤儿向量那样会被检索命中（危害小一档）。
        deleteObjectQuietly(document.getFilePath());

        log.info("[知识文档删除] 完成。documentId={} title={}", id, document.getTitle());
    }

    /**
     * 按 {@code documentId} 过滤删除 Milvus 里的向量。
     *
     * <p>用「按 metadata 过滤」而不是「先查 chunk 拿 vector_id 列表再按 id 删」：
     * 即使库里的某个 {@code vector_id} 与 Milvus 实际值对不上（上次索引半途失败留下的脏数据），
     * 过滤删除也能一次清干净 —— 而按 id 列表删会漏掉那些对不上的。
     */
    private void deleteVectorsOf(Long documentId) {
        VectorStore vectorStore;
        try {
            vectorStore = vectorStoreProvider.getIfAvailable();
        }
        catch (BeansException ex) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                    "向量库（Milvus）不可用，暂时无法删除文档，请稍后重试", ex);
        }
        if (vectorStore == null) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                    "向量库未装配（spring.ai.vectorstore.type 是否已启用？）");
        }

        try {
            vectorStore.delete(new FilterExpressionBuilder().eq("documentId", documentId).build());
        }
        catch (Exception ex) {
            log.error("[知识文档删除] 清理向量失败，已中止删除（DB 保持不变，可重试）documentId={}", documentId, ex);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                    "向量库（Milvus）不可用，暂时无法删除文档，请稍后重试", ex);
        }
    }

    /** 删对象存储里的文件；失败只记 warn（理由见 {@link #delete} 的第 ⑤ 步说明） */
    private void deleteObjectQuietly(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return;
        }
        try {
            objectStorage.delete(objectName);
        }
        catch (Exception ex) {
            log.warn("[知识文档删除] 清理对象存储失败（不影响删除结果，仅残留一个孤儿对象）object={} cause={}",
                    objectName, ex.getMessage());
        }
    }

    // ==================== 私有 ====================

    /**
     * 拼对象名：{@code knowledge/{documentId}/{UUID}.{ext}}。
     *
     * <p>三个可变部分的来源都可信：
     * <ul>
     *   <li>{@code documentId} —— 自增主键，纯数字</li>
     *   <li>{@code UUID} —— 纯生成，无外部输入</li>
     *   <li>{@code ext} —— 已过 {@link KnowledgeDocumentTypes#validate}，
     *       且它的 {@code extensionOf} 保证只含字母数字（含 {@code /}、{@code .}、空白的都被拒）</li>
     * </ul>
     * 拼出来的名字不可能逃出 {@code knowledge/} 前缀。
     */
    private String buildObjectName(Long documentId, String uuid, String ext) {
        return OBJECT_PREFIX + documentId + "/" + uuid + "." + ext;
    }

    /**
     * 防御性断言：对象名必须以 {@code knowledge/} 开头、且不含 {@code ..}。
     *
     * <p>正常路径<b>永远不会触发</b>（见 {@link #buildObjectName}）。写它的理由是：
     * 一旦将来有人「优化」了拼接逻辑或换了调用方，这行会<b>立刻炸</b>，
     * 而不是静默产出一个可穿越的对象名。属于「让错误在离它最近的地方暴露」。
     */
    private void assertSafeObjectName(String objectName) {
        if (!objectName.startsWith(OBJECT_PREFIX) || objectName.contains("..")) {
            // 代码 bug，不是用户输入问题 → 50000
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文档存储路径非法");
        }
    }

    /** 标题：显式传入优先，否则取文件名（去扩展名）；截断到列宽 */
    private String resolveTitle(String title, String originalName) {
        String value;
        if (title != null && !title.isBlank()) {
            value = title.trim();
        }
        else {
            value = stripExtension(sanitizeDisplayName(originalName));
        }
        if (value.isEmpty()) {
            value = "未命名文档";
        }
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH);
    }

    /**
     * 清洗「仅用于展示」的文件名。
     *
     * <p>⚠️ <b>这不是防路径穿越的手段</b>（穿越已由「对象名自造」在结构上杜绝）。
     * 只做两件展示层的事：剥掉客户端可能带上来的目录部分（{@code C:\Users\x\a.md} 这种）、
     * 截断到列宽 255。与 {@code AttachmentServiceImpl#sanitizeDisplayName} 同一手法。
     */
    private String sanitizeDisplayName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "document";
        }
        String name = originalName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            return "document";
        }
        return name.length() <= MAX_TITLE_LENGTH ? name : name.substring(0, MAX_TITLE_LENGTH);
    }

    /** 去掉最后一个扩展名（给"标题默认取文件名"用） */
    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }

    /**
     * 空白串归一成 {@code null}。
     *
     * <p>列表的 {@code keyword} 用得上：前端搜索框清空后往往传 {@code ?keyword=}，
     * 那不是"搜空串"，是"不筛" —— 不归一的话会拼出 {@code LIKE '%%'}，白白扫一遍表。
     */
    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
