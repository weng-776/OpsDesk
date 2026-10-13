package com.opsdesk.knowledge;

import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.JwtHelper;
import com.opsdesk.common.enums.KnowledgeDocStatus;
import com.opsdesk.knowledge.entity.KnowledgeChunk;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.service.KnowledgeChunkService;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D12-05 验收 3：<b>Milvus 不可用时删除文档</b>
 *
 * <h2>这条验收是整个删除设计的存在理由</h2>
 * §12.3 要求「同步删除 chunk 与对应向量，<b>避免孤儿向量</b>」。
 * 而删除顺序「先向量、后 DB」的全部意义就是：<b>向量删不掉时，DB 一行都不动</b> ——
 * 用户拿到 50300 重试即可，数据自始至终一致。
 *
 * <p>如果反过来先删 DB：{@code vector_id} 随行一起消失，Milvus 里那批向量就<b>再也定位不到了</b>
 * （既不知道有哪些 id，也无法按 documentId 找回来 —— 那正是"孤儿向量"）。
 *
 * <h2>怎么模拟</h2>
 * {@code @SpringBootTest(properties = ...)} 把 Milvus 指向无人监听的端口。
 * 造数据直接写两张表（不经过索引流水线 —— Milvus 不可用时也索引不了）。
 */
@SpringBootTest(properties = {
        "spring.ai.vectorstore.milvus.client.host=127.0.0.1",
        "spring.ai.vectorstore.milvus.client.port=19531" // 无人监听 → 连接必然失败
})
@AutoConfigureMockMvc
class KnowledgeDeleteMilvusDownTest {

    private static final long ADMIN_ID = 1L;
    private static final String DOC_TITLE = "D12-05 删除降级测试手册";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtHelper jwtHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeChunkService knowledgeChunkService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void cleanAuthCache() {
        redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + ADMIN_ID);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", id);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", id);
            jdbcTemplate.update("DELETE FROM audit_log WHERE resource_type = 'KNOWLEDGE_DOC' AND resource_id = ?", id);
        }
        createdIds.clear();
        redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + ADMIN_ID);
    }

    @Test
    @DisplayName("🔴 验收3：Milvus 停掉后删文档 → 50300，且 DB 的文档 / chunk 一行都不动")
    void 验收3_Milvus不可用时删除被拒且DB不变() throws Exception {
        long id = createReadyDocumentWithTwoChunks();

        mockMvc.perform(delete("/api/knowledge/documents/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf()))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(50300));

        // ↓↓↓ 这三条是本用例的核心：删除被拒之后，数据必须保持原样
        assertThat(docExists(id)).as("文档行必须还在").isTrue();
        assertThat(statusOf(id)).as("状态也不能被改动").isEqualTo(KnowledgeDocStatus.READY.name());
        assertThat(chunkRows(id)).as("chunk 一行都不能少").isEqualTo(2);

        // 审计也不该留下"删除成功"的痕迹（切面在异常时不应落库）
        Integer deleteAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE resource_type = 'KNOWLEDGE_DOC'"
                        + " AND resource_id = ? AND operation = 'KNOWLEDGE_DOC_DELETE'",
                Integer.class, id);
        assertThat(deleteAudits).as("删除失败就不该有删除审计").isZero();
    }

    @Test
    @DisplayName("删除不存在的文档 → 40400（这一步不碰 Milvus，所以不返回 50300）")
    void 不存在时先返回40400() throws Exception {
        mockMvc.perform(delete("/api/knowledge/documents/{id}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    // ==================== 工具 ====================

    private long createReadyDocumentWithTwoChunks() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(DOC_TITLE);
        document.setFileName("delete-down.md");
        document.setFilePath("knowledge/test/" + UUID.randomUUID() + ".md");
        document.setStatus(KnowledgeDocStatus.READY);
        document.setChunkCount(2);
        document.setUploaderId(ADMIN_ID);
        knowledgeDocumentService.save(document);
        createdIds.add(document.getId());

        for (int i = 0; i < 2; i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(document.getId());
            chunk.setContent("删除降级测试片段 " + i + "：VPN 客户端无法连接时的排查步骤说明。");
            chunk.setChunkIndex(i);
            chunk.setVectorId("down-test-vec-" + document.getId() + "-" + i);
            knowledgeChunkService.save(chunk);
        }
        return document.getId();
    }

    private boolean docExists(long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document WHERE id = ?", Integer.class, documentId);
        return count != null && count > 0;
    }

    private String statusOf(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_document WHERE id = ?", String.class, documentId);
    }

    private int chunkRows(long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = ?", Integer.class, documentId);
        return count == null ? 0 : count;
    }

    private String tokenOf() {
        return "Bearer " + jwtHelper.createToken(ADMIN_ID);
    }
}
