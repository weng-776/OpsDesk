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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * D12-03 验收 3 + 4：<b>Milvus 不可用</b>时的行为（本批特有约束「外部依赖不可用不能拖垮启动」）
 *
 * <h2>做法：把 Milvus 指向一个没人监听的端口</h2>
 * 通过 {@code @SpringBootTest(properties = ...)} 覆盖 host/port，模拟"Milvus 没起"。
 * 这比 mock 更真实 —— 它连的是 {@code MilvusServiceClient} 真实的连接检查逻辑
 * （实测：构造时会 {@code wait_for_ready} 约 10 秒然后抛 {@code DEADLINE_EXCEEDED}）。
 *
 * <h2>⚠️ 这个测试在没有 {@code LazyMilvusBeansPostProcessor} 时会直接失败</h2>
 * 不是断言失败，而是<b>上下文根本起不来</b>（{@code UnsatisfiedDependencyException}）。
 * 所以它同时是「验收 4」的回归网。
 *
 * <h2>代价</h2>
 * 会多启一个 Spring 上下文（properties 与主测试不同，缓存不共享），并且
 * 「索引失败」那条用例要等一次约 10 秒的 gRPC deadline。这是必要的成本：
 * 这两条正是"环境没起"时最容易踩坏的路径。
 */
@SpringBootTest(properties = {
        "spring.ai.vectorstore.milvus.client.host=127.0.0.1",
        "spring.ai.vectorstore.milvus.client.port=19531" // 无人监听 → 连接必然失败
})
class MilvusUnavailableTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private ObjectStorage objectStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioProperties minioProperties;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
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
                    // 清理失败不影响测试结论
                    System.out.println("[测试清理] 删对象失败 object=" + objectName + " cause=" + ex.getMessage());
                }
            }
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", id);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", id);
        }
        createdIds.clear();
    }

    @Test
    @DisplayName("验收4：Milvus 不可用时应用仍能正常启动 —— 向量库 bean 在启动阶段【没有被创建】")
    void 应用能在Milvus不可用时启动() {
        assertThat(applicationContext).as("上下文能起来，就说明启动没被 Milvus 拖垮").isNotNull();

        var beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        assertThat(beanFactory.containsSingleton("vectorStore"))
                .as("🔴 lazy 生效：启动时不该实例化它（否则连不上 Milvus 会直接让上下文 refresh 失败）")
                .isFalse();
        assertThat(beanFactory.containsSingleton("milvusClient"))
                .as("🔴 milvusClient 才是第一个炸的 bean（它在构造函数里就连），两个都必须 lazy")
                .isFalse();
        assertThat(applicationContext.getBeanNamesForType(
                org.springframework.ai.vectorstore.VectorStore.class))
                .as("bean 定义仍在 —— 只是延迟创建，Milvus 恢复后无需重启即可用")
                .isNotEmpty();
    }

    @Test
    @DisplayName("验收3：Milvus 不可用时索引 → 文档置 FAILED，且调用方不抛异常（不是 500、不是启动失败）")
    void Milvus不可用时置FAILED() {
        long id = createDocument("milvus-down.md");

        assertThatCode(() -> knowledgeIndexService.index(id))
                .as("§«外部依赖降级»：抛出去会让 MQ 重试 3 次然后进 DLQ，看起来像代码 bug")
                .doesNotThrowAnyException();

        var doc = jdbcTemplate.queryForMap(
                "SELECT status, chunk_count FROM knowledge_document WHERE id = ?", id);
        assertThat(doc.get("status"))
                .as("环境没起 → 文档标失败，运维在 Milvus 恢复后手工重投")
                .isEqualTo(KnowledgeDocStatus.FAILED.name());
        assertThat(jdbcTemplate.queryForList(
                "SELECT vector_id FROM knowledge_chunk WHERE document_id = ?", String.class, id))
                .as("失败时不能留下半截 chunk（补偿已把它清掉）")
                .isEmpty();
    }

    private long createDocument(String fileName) {
        byte[] content = ("# 标题\n" + "Milvus 不可用时的内容。".repeat(60)).getBytes(StandardCharsets.UTF_8);
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
}
