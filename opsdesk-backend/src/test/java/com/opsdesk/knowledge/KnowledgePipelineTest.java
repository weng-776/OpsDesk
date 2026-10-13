package com.opsdesk.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.JwtHelper;
import com.opsdesk.common.enums.KnowledgeDocStatus;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.config.properties.MinioProperties;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import com.opsdesk.mq.AbstractMqIntegrationTest;
import com.opsdesk.mq.event.KnowledgeIndexEvent;
import com.opsdesk.mq.support.OutboxPublisher;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D12-05 验收：删除文档（三处一致）+ 索引流水线接 MQ
 *
 * <h2>这个测试真跑了什么</h2>
 * 上传走 <b>MockMvc → 真落 MinIO → 真写 event_outbox</b>；
 * 投递调 {@link OutboxPublisher#publishPending()}（<b>不依赖调度器</b> —— 见下）；
 * 消费者真跑解析 / 切分 / Embedding / Milvus。
 *
 * <h2>⚠️ 为什么不注入 {@code OutboxPublishJob}</h2>
 * 它带 {@code @ConditionalOnProperty(opsdesk.outbox.publish.enabled, matchIfMissing = true)}，
 * 而 {@code src/test/resources/application.properties} <b>把它设成了 false</b>（D9-01 有意为之：
 * 定时任务在测试 JVM 里也会真跑、且走自己的事务不回滚，会打红「PENDING → SENT」这类精确断言）。
 * 所以测试直接调 publisher —— 这正是 D9-01 把逻辑全放在 publisher 里的原因：
 * <b>投递行为可以被测试直接调用验证，不依赖调度器</b>。
 * （一开始我注入了 job，6 个用例全 ERROR：{@code No qualifying bean of type OutboxPublishJob}。）
 *
 * <h2>⚠️ 于是测试环境里没有"后台自己投递"的噪音</h2>
 * 调度器关着，outbox 里的事件只会在本测试显式调用 {@code publishPending()} 时才发出去 ——
 * 时序完全可控（验收 5 的计数就靠这一点）。
 *
 * <h2>🔴 必须继承 {@link AbstractMqIntegrationTest}（否则消费者根本不在跑）</h2>
 * {@code src/test/resources/application.properties} 里
 * {@code spring.rabbitmq.listener.simple.auto-startup=false}（D9-02 有意为之：缓存上下文会
 * 同时起多份 {@code @RabbitListener} 容器、在同一批队列上竞争消费，导致「单独跑绿、全量随机红」）。
 * 代价是：**不启动容器就没有消费者** —— 消息会静静堆在 {@code opsdesk.knowledge.index.queue} 上，
 * 文档永远停在 PROCESSING，表现为「等 READY 超时」。
 * 基类在 {@code @BeforeEach} 显式启动本上下文的容器、{@code @AfterEach} 停掉。
 * （踩坑时队列里堆了 11 条消息、{@code consumers: 0} —— 这是最直接的诊断信号。）
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgePipelineTest extends AbstractMqIntegrationTest {

    private static final long ADMIN_ID = 1L;
    private static final long AGENT_ZHANG_ID = 2L;
    private static final long EMP_WANG_ID = 4L;
    private static final List<Long> TOUCHED = List.of(ADMIN_ID, AGENT_ZHANG_ID, EMP_WANG_ID);

    private static final String VPN_CONTENT = """
            # VPN 故障排查手册

            VPN 连接失败时的处理步骤：先确认本机网络可以正常访问外网，再检查客户端是否为最新版本，
            然后尝试退出账号重新登录以排除凭据过期。如果以上都无法解决，请提交工单并附上错误截图。
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtHelper jwtHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 投递器（不是调度器，见类注释 —— 调度器在测试环境被 @ConditionalOnProperty 关掉了） */
    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private ObjectStorage objectStorage;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioProperties minioProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private ObjectProvider<VectorStore> vectorStoreProvider;

    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void cleanAuthCache() {
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            deleteVectors(id);
            String objectName = jdbcTemplate.query(
                    "SELECT file_path FROM knowledge_document WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, id);
            removeObjectQuietly(objectName);
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", id);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", id);
            jdbcTemplate.update("DELETE FROM audit_log WHERE resource_type = 'KNOWLEDGE_DOC' AND resource_id = ?", id);
        }
        createdIds.clear();
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    // ==================== 验收 1：流水线自动跑完 ====================

    @Test
    @DisplayName("验收1：上传 → outbox 投递 → 消费者索引 → READY + chunk_count 正确 + Milvus 里有向量")
    void 验收1_上传后流水线自动跑完() throws Exception {
        long id = uploadViaApi("pipeline.md", VPN_CONTENT);

        assertThat(outboxEventCount(id)).as("上传应写一条 KnowledgeIndexEvent（§13.3）").isPositive();

        outboxPublisher.publishPending();

        awaitStatus(id, "READY");

        int chunkCount = jdbcTemplate.queryForObject(
                "SELECT chunk_count FROM knowledge_document WHERE id = ?", Integer.class, id);
        assertThat(chunkCount).as("§12.3：索引完成后 chunk_count = 实际条数").isPositive();
        assertThat(vectorIdsOf(id)).hasSize(chunkCount).allSatisfy(
                vectorId -> assertThat(vectorId).isNotBlank());

        assertThat(searchByDocumentId(id))
                .as("Milvus 里必须真的能按 documentId 过滤查到这批向量")
                .isNotEmpty();
    }

    // ==================== 验收 2：删除三处一致 ====================

    @Test
    @DisplayName("验收2：删除 → chunk 清空 + Milvus 向量被删 + MinIO 对象被删 + 文档行物理删除")
    void 验收2_删除清掉三处() throws Exception {
        long id = uploadViaApi("to-delete.md", VPN_CONTENT);
        outboxPublisher.publishPending();
        awaitStatus(id, "READY");

        String objectName = filePathOf(id);
        assertThat(searchByDocumentId(id)).as("前提：删除前 Milvus 里确实有向量").isNotEmpty();
        assertThat(objectStorage.exists(objectName)).as("前提：对象确实在 MinIO 里").isTrue();

        deleteViaApi(id).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        assertThat(docExists(id)).as("knowledge_document 是物理删除（DDL 没有 deleted 列）").isFalse();
        assertThat(rawChunkRows(id)).as("knowledge_chunk 清空").isZero();
        awaitNoVectors(id);
        assertThat(objectStorage.exists(objectName)).as("MinIO 对象一并清理（本单补的 delete）").isFalse();
    }

    // ==================== 验收 4：上传后立刻删除 ====================

    @Test
    @DisplayName("验收4：上传后立刻删除（outbox 里还留着 PENDING）→ 消费者跳过、不报错、不留垃圾")
    void 验收4_上传后立刻删除不留垃圾() throws Exception {
        long id = uploadViaApi("deleted-fast.md", VPN_CONTENT);
        assertThat(outboxEventCount(id)).as("上传确实写了索引事件").isPositive();

        deleteViaApi(id).andExpect(status().isOk());

        // 现在把那条事件投出去 —— 消费者会发现文档已不存在。这里【不该】有任何异常：
        // 抛出去会被重试 3 次然后进 DLQ，看起来像代码 bug，实际只是"消息比用户慢了一步"
        assertThatCode(() -> outboxPublisher.publishPending()).doesNotThrowAnyException();
        sleep(2500);

        assertThat(docExists(id)).as("文档不该被索引流程复活").isFalse();
        assertThat(rawChunkRows(id)).as("不能留下任何 chunk").isZero();
    }

    // ==================== 验收 5：重复投递的幂等 ====================

    @Test
    @DisplayName("验收5：同一条事件（相同 messageId）重复投递 → guard 挡掉，不重复建 chunk")
    void 验收5_重复投递幂等() throws Exception {
        // 不经过上传，直接造一份 PROCESSING 文档 —— 把 outbox / 时序变量全收干净
        long id = createProcessingDocument("idempotent-pipeline.md", VPN_CONTENT);
        String messageId = UUID.randomUUID().toString();

        sendIndexEvent(id, messageId);
        awaitStatus(id, "READY");
        int firstCount = rawChunkRows(id);
        assertThat(firstCount).isPositive();

        sendIndexEvent(id, messageId); // 逐字重复的一条
        sleep(2500);

        assertThat(rawChunkRows(id)).as("消费守卫按 messageId 挡住第二次").isEqualTo(firstCount);
    }

    // ==================== 验收 6：权限 ====================

    @Test
    @DisplayName("验收6：EMPLOYEE / AGENT 删除 → 40300（无 knowledge:manage）；未登录 → 40100")
    void 验收6_权限校验() throws Exception {
        long id = createProcessingDocument("perm.md", VPN_CONTENT);

        deleteViaApi(id, "Bearer " + jwtHelper.createToken(AGENT_ZHANG_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        deleteViaApi(id, "Bearer " + jwtHelper.createToken(EMP_WANG_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(delete("/api/knowledge/documents/{id}", id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        assertThat(docExists(id)).as("被拒的请求不能真的删掉东西").isTrue();
    }

    @Test
    @DisplayName("删除不存在的文档 → 40400（删不存在的 id 不该返回成功）")
    void 删除不存在返回40400() throws Exception {
        deleteViaApi(999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    // ==================== 工具 ====================

    private long uploadViaApi(String fileName, String content) throws Exception {
        String body = mockMvc.perform(multipart("/api/knowledge/documents")
                        .file(new MockMultipartFile("file", fileName, "text/markdown",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtHelper.createToken(ADMIN_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).path("data").path("id").asLong();
        createdIds.add(id);
        return id;
    }

    private ResultActions deleteViaApi(long id) throws Exception {
        return deleteViaApi(id, "Bearer " + jwtHelper.createToken(ADMIN_ID));
    }

    private ResultActions deleteViaApi(long id, String token) throws Exception {
        return mockMvc.perform(delete("/api/knowledge/documents/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    /** 直接造一份 PROCESSING 文档（不写 outbox），用于把时序变量收干净 */
    private long createProcessingDocument(String fileName, String content) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(fileName);
        document.setFileName(fileName);
        String objectName = "knowledge/test/" + UUID.randomUUID() + ".md";
        document.setFilePath(objectName);
        document.setStatus(KnowledgeDocStatus.PROCESSING);
        document.setChunkCount(0);
        document.setUploaderId(ADMIN_ID);
        knowledgeDocumentService.save(document);

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            objectStorage.put(objectName, in, bytes.length, "text/markdown");
        }
        catch (Exception ex) {
            throw new IllegalStateException("测试准备失败：写入对象存储", ex);
        }

        createdIds.add(document.getId());
        return document.getId();
    }

    /** 直接往队列投一条 KnowledgeIndexEvent（绕过 outbox，用于精确控制 messageId） */
    private void sendIndexEvent(long documentId, String messageId) throws Exception {
        String payload = objectMapper.writeValueAsString(new KnowledgeIndexEvent(documentId));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_KNOWLEDGE_INDEX,
                objectMapper.readTree(payload), (Message message) -> {
                    message.getMessageProperties().setMessageId(messageId);
                    return message;
                });
    }

    private void awaitStatus(long documentId, String expected) {
        for (int i = 1; i <= 30; i++) {
            String status = jdbcTemplate.query(
                    "SELECT status FROM knowledge_document WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, documentId);
            if (expected.equals(status)) {
                return;
            }
            sleep(1000);
        }
        fail("等待文档状态变成 " + expected + " 超时（documentId=" + documentId + "）");
    }

    /**
     * 等 Milvus 的删除生效。
     *
     * <p>Milvus 的 search 与 delete 之间可能存在短暂的一致性延迟，所以轮询而不是断言一次。
     */
    private void awaitNoVectors(long documentId) {
        for (int i = 1; i <= 8; i++) {
            if (searchByDocumentId(documentId).isEmpty()) {
                return;
            }
            sleep(1000);
        }
        fail("删除后仍能按 documentId 过滤查到向量，说明 Milvus 里的向量没删掉");
    }

    /** 按 metadata 里的 documentId 过滤检索 —— 与删除用的过滤条件完全一致 */
    private List<Document> searchByDocumentId(long documentId) {
        try {
            return vectorStoreProvider.getObject().similaritySearch(SearchRequest.builder()
                    .query("VPN")
                    .topK(20)
                    .filterExpression("documentId == " + documentId)
                    .build());
        }
        catch (Exception ex) {
            throw new IllegalStateException("按 documentId 过滤检索失败", ex);
        }
    }

    private long outboxEventCount(long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE event_type = 'KnowledgeIndexEvent'"
                        + " AND payload LIKE ?",
                Integer.class, "%\"documentId\":" + documentId + "%");
        return count == null ? 0 : count;
    }

    private List<String> vectorIdsOf(long documentId) {
        return jdbcTemplate.queryForList(
                "SELECT vector_id FROM knowledge_chunk WHERE document_id = ?", String.class, documentId);
    }

    private int rawChunkRows(long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = ?", Integer.class, documentId);
        return count == null ? 0 : count;
    }

    private boolean docExists(long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document WHERE id = ?", Integer.class, documentId);
        return count != null && count > 0;
    }

    private String filePathOf(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT file_path FROM knowledge_document WHERE id = ?", String.class, documentId);
    }

    private void deleteVectors(long documentId) {
        List<String> ids = vectorIdsOf(documentId);
        if (ids.isEmpty()) {
            return;
        }
        try {
            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore != null) {
                vectorStore.delete(ids);
            }
        }
        catch (Exception ex) {
            System.out.println("[测试清理] 删向量失败 size=" + ids.size() + " cause=" + ex.getMessage());
        }
    }

    private void removeObjectQuietly(String objectName) {
        if (objectName == null) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName()).object(objectName).build());
        }
        catch (Exception ex) {
            System.out.println("[测试清理] 删对象失败 object=" + objectName + " cause=" + ex.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
