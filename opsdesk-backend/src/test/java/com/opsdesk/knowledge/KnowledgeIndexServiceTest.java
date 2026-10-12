package com.opsdesk.knowledge;

import com.opsdesk.common.enums.KnowledgeDocStatus;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.config.properties.MinioProperties;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import com.opsdesk.knowledge.service.KnowledgeIndexService;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * D12-03 验收：文档索引流水线（真跑 MinIO + MySQL + Milvus + 通义 embedding）
 *
 * <h2>为什么造数据不用上传接口</h2>
 * 上传接口的行为已由 {@code KnowledgeDocumentUploadTest} 覆盖。索引的输入契约是
 * 「一条 PROCESSING 的 {@code knowledge_document} 记录 + 一个真实存在的 MinIO 对象」，
 * 这里直接按契约造数据，测试更聚焦、也不受上传层校验的干扰。
 *
 * <h2>⚠️ 这个测试会真写 Milvus、真调 embedding</h2>
 * 所以文档刻意做得小（约 1200 字 → 3 个片段）；{@code @AfterEach} 会把
 * chunk 行、文档行、MinIO 对象、Milvus 向量<b>全部清掉</b>（验收：测试自清）。
 */
@SpringBootTest
class KnowledgeIndexServiceTest {

    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private ObjectStorage objectStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioProperties minioProperties;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            deleteVectorsOf(id);
            String objectName = jdbcTemplate.query(
                    "SELECT file_path FROM knowledge_document WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, id);
            if (objectName != null) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .build());
                }
                catch (Exception ex) {
                    System.out.println("[测试清理] 删对象失败 object=" + objectName + " cause=" + ex.getMessage());
                }
            }
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", id);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", id);
        }
        createdIds.clear();
    }

    // ==================== 验收 1 + 2：索引成功、vector_id 非空 ====================

    @Test
    @DisplayName("验收1+2：索引 md → READY、chunk_count>0、每条 chunk 都有 vector_id，且向量真进了 Milvus")
    void 索引成功且向量可检索() {
        long id = createDocument("vpn-guide.md", """
                # VPN 使用手册

                连接失败时的排查步骤：先检查客户端版本是否为最新，再确认本地网络是否正常，
                然后确认 VPN 服务端状态。如果仍然无法连接，请检查账号是否被锁定，
                以及是否需要重新导入证书。""".repeat(12)); // 约 1200 字 → 3 个片段

        knowledgeIndexService.index(id);

        // ---- 文档状态 ----
        var doc = jdbcTemplate.queryForMap(
                "SELECT status, chunk_count FROM knowledge_document WHERE id = ?", id);
        assertThat(doc.get("status")).isEqualTo(KnowledgeDocStatus.READY.name());
        int chunkCount = ((Number) doc.get("chunk_count")).intValue();
        assertThat(chunkCount).as("§12.3：成功后 chunk_count 应更新").isPositive();

        // ---- chunk 行 ----
        List<String> vectorIds = jdbcTemplate.queryForList(
                "SELECT vector_id FROM knowledge_chunk WHERE document_id = ? ORDER BY chunk_index",
                String.class, id);
        assertThat(vectorIds).hasSize(chunkCount);
        assertThat(vectorIds).as("🔴 vector_id 不能为空 —— 否则检索命中后无法映射回正文")
                .allSatisfy(v -> assertThat(v).isNotBlank());

        // ---- chunk_index 从 0 连续 ----
        List<Integer> indexes = jdbcTemplate.queryForList(
                "SELECT chunk_index FROM knowledge_chunk WHERE document_id = ? ORDER BY chunk_index",
                Integer.class, id);
        assertThat(indexes).isEqualTo(java.util.stream.IntStream.range(0, chunkCount).boxed().toList());

        // ---- 真的写进 Milvus 了（不是只写了库）----
        VectorStore vectorStore = vectorStoreProvider.getObject();
        List<Document> hits = vectorStore.similaritySearch("VPN 连接失败怎么排查");
        assertThat(hits).as("检索应能命中刚写入的片段").isNotEmpty();
        assertThat(hits).extracting(Document::getId)
                .as("命中的向量 id 必须来自我们预先生成的那批")
                .anyMatch(vectorIds::contains);

        // ---- metadata 带上了回查所需的信息 ----
        assertThat(hits).filteredOn(h -> vectorIds.contains(h.getId()))
                .isNotEmpty()
                .allSatisfy(h -> {
                    assertThat(h.getMetadata()).containsKey("documentId").containsKey("chunkIndex");
                    assertThat(((Number) h.getMetadata().get("documentId")).longValue()).isEqualTo(id);
                });
    }

    // ==================== 验收 5：幂等 ====================

    @Test
    @DisplayName("验收5：同一文档重复索引 → 不产生重复 chunk（清旧重建，条数与 id 集合都稳定）")
    void 重复索引不产生重复数据() {
        long id = createDocument("idempotent.md", "# 标题\n" + "重复索引的内容。".repeat(150));

        knowledgeIndexService.index(id);
        List<String> firstIds = vectorIdsOf(id);
        int firstCount = chunkCountOf(id);

        knowledgeIndexService.index(id); // 第二次
        List<String> secondIds = vectorIdsOf(id);

        assertThat(chunkCountOf(id)).as("条数不能翻倍").isEqualTo(firstCount);
        assertThat(jdbcTemplate.queryForList(
                "SELECT chunk_index FROM knowledge_chunk WHERE document_id = ?", Integer.class, id))
                .as("chunk_index 不能出现重复（0,1,2,... 各一份）")
                .doesNotHaveDuplicates();
        assertThat(secondIds).as("重建后是新的一批 id（旧向量已被替换），但数量一致")
                .hasSize(firstIds.size())
                .doesNotContainAnyElementsOf(firstIds);
    }

    // ==================== 空文档 ====================

    @Test
    @DisplayName("空文档 → READY + chunk_count=0（解析成功但没内容，不算索引失败）")
    void 空文档置READY且零片段() {
        long id = createDocument("blank.md", "   \n\n  \t \n   ");

        assertThatCode(() -> knowledgeIndexService.index(id)).doesNotThrowAnyException();

        var doc = jdbcTemplate.queryForMap(
                "SELECT status, chunk_count FROM knowledge_document WHERE id = ?", id);
        assertThat(doc.get("status")).isEqualTo(KnowledgeDocStatus.READY.name());
        assertThat(((Number) doc.get("chunk_count")).intValue()).isZero();
        assertThat(vectorIdsOf(id)).isEmpty();
    }

    // ==================== 失败路径：不抛异常 + 置 FAILED ====================

    @Test
    @DisplayName("🔴 解析失败（非 UTF-8）→ 文档置 FAILED，且调用方【不抛异常】（§「不能拖垮消费者」）")
    void 解析失败置FAILED且不抛异常() {
        // 故意写 GBK 字节：D12-01 的解析器会直接拒绝（而不是让乱码进向量库）
        byte[] gbk = "公司 VPN 无法连接".getBytes(Charset.forName("GBK"));
        long id = createDocumentWithBytes("gbk.md", gbk);

        assertThatCode(() -> knowledgeIndexService.index(id))
                .as("调用方是 MQ 消费者：抛出去会被重试 3 次然后进 DLQ，看起来像代码 bug")
                .doesNotThrowAnyException();

        var doc = jdbcTemplate.queryForMap(
                "SELECT status, chunk_count FROM knowledge_document WHERE id = ?", id);
        assertThat(doc.get("status")).isEqualTo(KnowledgeDocStatus.FAILED.name());
        assertThat(((Number) doc.get("chunk_count")).intValue()).as("失败不留半截数据").isZero();
        assertThat(vectorIdsOf(id)).isEmpty();
    }

    @Test
    @DisplayName("文档不存在 → 只记日志，不抛异常（消息可能是脏的）")
    void 文档不存在不抛异常() {
        assertThatCode(() -> knowledgeIndexService.index(999_999_999L)).doesNotThrowAnyException();
    }

    // ==================== 工具 ====================

    /** 按「一条 PROCESSING 记录 + 一个真实 MinIO 对象」的契约造数据 */
    private long createDocument(String fileName, String content) {
        return createDocumentWithBytes(fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    private long createDocumentWithBytes(String fileName, byte[] content) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(fileName);
        document.setFileName(fileName);
        String objectName = "knowledge/test/" + UUID.randomUUID() + ".md";
        document.setFilePath(objectName);
        document.setStatus(KnowledgeDocStatus.PROCESSING);
        document.setChunkCount(0);
        document.setUploaderId(1L);
        knowledgeDocumentService.save(document);

        try (InputStream in = new ByteArrayInputStream(content)) {
            objectStorage.put(objectName, in, content.length, "text/markdown");
        }
        catch (Exception ex) {
            throw new IllegalStateException("测试准备失败：写入对象存储", ex);
        }

        createdIds.add(document.getId());
        return document.getId();
    }

    private List<String> vectorIdsOf(long documentId) {
        return jdbcTemplate.queryForList(
                "SELECT vector_id FROM knowledge_chunk WHERE document_id = ? ORDER BY chunk_index",
                String.class, documentId).stream().filter(Objects::nonNull).toList();
    }

    private int chunkCountOf(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT chunk_count FROM knowledge_document WHERE id = ?", Integer.class, documentId);
    }

    private void deleteVectorsOf(long documentId) {
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
            System.out.println("[测试清理] 删向量失败 ids=" + ids.size() + " cause=" + ex.getMessage());
        }
    }
}
