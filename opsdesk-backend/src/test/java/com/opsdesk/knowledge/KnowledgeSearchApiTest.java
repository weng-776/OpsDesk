package com.opsdesk.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.JwtHelper;
import com.opsdesk.common.enums.KnowledgeDocStatus;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.config.properties.MinioProperties;
import com.opsdesk.knowledge.entity.KnowledgeChunk;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.service.KnowledgeChunkService;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import com.opsdesk.knowledge.service.KnowledgeIndexService;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D12-04 验收：关键词检索（§10.5）+ RAG 语义问答（§10.6）
 *
 * <h2>两类用例的依赖不同</h2>
 * <ul>
 *   <li><b>关键词检索</b>只依赖 MySQL —— 直接用<b>种子文档</b>
 *       （id=1「公司 VPN 使用手册」，片段里含「VPN」「排查」等词），一条 Milvus 都不碰；</li>
 *   <li><b>RAG</b>依赖 Milvus + Embedding + LLM —— 测试自己造一份小文档并真索引
 *       （⚠️ 种子文档的 {@code vector_id} 是写死的假值，Milvus 里并没有对应向量，不能直接用）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeSearchApiTest {

    private static final long ADMIN_ID = 1L;
    private static final long EMP_WANG_ID = 4L;
    private static final long AGENT_ZHANG_ID = 2L;
    private static final List<Long> TOUCHED = List.of(ADMIN_ID, EMP_WANG_ID, AGENT_ZHANG_ID);

    /** 种子文档：公司 VPN 使用手册（READY，3 个片段） */
    private static final long SEED_DOC_ID = 1L;

    private static final String RAG_DOC_TITLE = "D12-04 VPN 故障排查手册";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtHelper jwtHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private KnowledgeChunkService knowledgeChunkService;

    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

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
            if (objectName != null) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(minioProperties.getBucketName()).object(objectName).build());
                }
                catch (Exception ex) {
                    System.out.println("[测试清理] 删对象失败 object=" + objectName + " cause=" + ex.getMessage());
                }
            }
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", id);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", id);
        }
        createdIds.clear();
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    // ==================== 验收 1：关键词检索 ====================

    @Test
    @DisplayName("验收1：GET /api/knowledge/search?keyword=VPN → 分页结果，snippet 含关键词")
    void 验收1_关键词检索命中且snippet含关键词() throws Exception {
        String body = mockMvc.perform(get("/api/knowledge/search")
                        .param("keyword", "VPN")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        var list = objectMapper.readTree(body).path("data").path("list");
        assertThat(list.size()).as("种子里就有 VPN 手册，应至少命中一条").isPositive();

        for (var item : list) {
            assertThat(item.path("documentId").asLong()).as("命中文档").isPositive();
            assertThat(item.path("documentTitle").asText()).isNotBlank();
            assertThat(item.path("chunkIndex").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(item.path("snippet").asText())
                    .as("🔴 §16.14：snippet 必须含关键词（高亮由前端做，后端保证含词）")
                    .containsIgnoringCase("VPN");
        }
    }

    @Test
    @DisplayName("验收6：EMPLOYEE 也能调（knowledge:view 所有登录角色都有）")
    void 验收6_EMPLOYEE可调用() throws Exception {
        mockMvc.perform(get("/api/knowledge/search")
                        .param("keyword", "VPN")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/knowledge/rag-query")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(AGENT_ZHANG_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"VPN 连不上怎么办\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("关键词为空 → 空分页，不报错（用户没输关键词不是错误）")
    void 关键词为空返回空页() throws Exception {
        mockMvc.perform(get("/api/knowledge/search")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ==================== 验收 7：LIKE 转义 ====================

    @Test
    @DisplayName("🔴 验收7：keyword=% 不会变成通配符（转义生效）")
    void 验收7_百分号被转义() throws Exception {
        // 未转义时 `LIKE '%%%'` 会匹配【所有】片段 —— 这里断言它什么都匹配不到
        // （种子片段里没有字面含 % 的内容）
        mockMvc.perform(get("/api/knowledge/search")
                        .param("keyword", "%")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));

        // 下划线同理：未转义时 `LIKE '_'` 会匹配所有「长度 ≥1」的片段
        mockMvc.perform(get("/api/knowledge/search")
                        .param("keyword", "_")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("关键词命中标题（正文不含）时也能搜到，snippet 退化为片段开头")
    void 标题命中也算命中() throws Exception {
        String body = mockMvc.perform(get("/api/knowledge/search")
                        .param("keyword", "手册")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        var first = objectMapper.readTree(body).path("data").path("list").get(0);
        assertThat(first.path("documentTitle").asText()).contains("手册");
        assertThat(first.path("snippet").asText()).as("正文没提「手册」，snippet 不含它是信息本身").isNotBlank();
    }

    // ==================== 参数校验与鉴权 ====================

    @Test
    @DisplayName("question 为空 / 超 500 字 → 40001；未登录 → 40100")
    void 参数校验与鉴权() throws Exception {
        mockMvc.perform(post("/api/knowledge/rag-query")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(post("/api/knowledge/rag-query")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + "问".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(get("/api/knowledge/search").param("keyword", "VPN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        mockMvc.perform(post("/api/knowledge/rag-query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"VPN\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ==================== 验收 2 + 3 + 4：RAG ====================

    @Test
    @DisplayName("验收2+3：rag-query → answer + 非空 references，且 documentTitle / chunkIndex 与库里一致")
    void 验收2和3_RAG带引用() throws Exception {
        long documentId = createAndIndexVpnDocument();

        String body = mockMvc.perform(post("/api/knowledge/rag-query")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"VPN 连接失败应该怎么排查\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(body).path("data");
        var references = data.path("references");

        assertThat(references.size()).as("🔴 §12.2：回答必须带引用").isPositive();

        var first = references.get(0);
        assertThat(first.path("documentId").asLong()).isPositive();
        assertThat(first.path("score").isNull()).as("语义检索命中 → score 应有值").isFalse();
        assertThat(first.path("snippet").asText()).isNotBlank();

        // 引用里的标题 / 片段序号必须能在库里对上（不能是模型编的）
        long refDocId = first.path("documentId").asLong();
        int refChunkIndex = first.path("chunkIndex").asInt();
        String dbTitle = jdbcTemplate.queryForObject(
                "SELECT title FROM knowledge_document WHERE id = ?", String.class, refDocId);
        assertThat(first.path("documentTitle").asText()).as("引用标题必须与库里一致").isEqualTo(dbTitle);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = ? AND chunk_index = ?",
                Integer.class, refDocId, refChunkIndex))
                .as("引用的片段必须真实存在").isPositive();
    }

    @Test
    @DisplayName("验收4：topK=20 → 收敛为 10（不报 40001，与 PageQuery 对 size 的脾气一致）")
    void 验收4_topK被收敛() throws Exception {
        long documentId = createAndIndexVpnDocument();

        mockMvc.perform(post("/api/knowledge/rag-query")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"VPN 连不上\",\"topK\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.references.length()").value(
                        org.hamcrest.Matchers.lessThanOrEqualTo(10)));

        assertThat(documentId).isPositive();
    }

    @Test
    @DisplayName("问知识库里没有的东西 → 明确说「未找到」，不编答案（references 为空）")
    void 召回为空不编答案() throws Exception {
        mockMvc.perform(post("/api/knowledge/rag-query")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"公司食堂的午餐菜单里有没有佛跳墙和鹅肝\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("未在知识库中找到")))
                .andExpect(jsonPath("$.data.references").isEmpty());
    }

    // ==================== 工具 ====================

    /** 造一份 VPN 手册并真索引（要 Milvus 里真有向量才能做语义检索） */
    private long createAndIndexVpnDocument() {
        String content = """
                # VPN 故障排查手册

                VPN 连接失败时的处理步骤：
                第一步，确认本机网络可以正常访问外网；
                第二步，检查 VPN 客户端是否为最新版本，过旧版本经常无法握手；
                第三步，尝试退出账号后重新登录，排除凭据过期；
                第四步，确认账号没有被锁定、也没有同时登录的设备数超限；
                如果以上都无法解决，请提交工单并附上完整的错误截图。
                """;

        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(RAG_DOC_TITLE);
        document.setFileName("vpn-troubleshoot.md");
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
        knowledgeIndexService.index(document.getId()); // 真调 embedding + 写 Milvus
        return document.getId();
    }

    private void deleteVectors(long documentId) {
        List<String> vectorIds = jdbcTemplate.queryForList(
                "SELECT vector_id FROM knowledge_chunk WHERE document_id = ?", String.class, documentId);
        if (vectorIds.isEmpty()) {
            return;
        }
        try {
            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore != null) {
                vectorStore.delete(vectorIds);
            }
        }
        catch (Exception ex) {
            System.out.println("[测试清理] 删向量失败 size=" + vectorIds.size() + " cause=" + ex.getMessage());
        }
    }

    private String tokenOf(long userId) {
        return "Bearer " + jwtHelper.createToken(userId);
    }
}
