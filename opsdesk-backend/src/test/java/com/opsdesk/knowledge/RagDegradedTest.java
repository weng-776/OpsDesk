package com.opsdesk.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D12-04 验收 5：<b>Milvus / Embedding 不可用时的降级行为</b>
 *
 * <h2>规格在这里是矛盾的，按 §24.3 实现</h2>
 * <ul>
 *   <li>API §10.6 的错误表：{@code 50300} 向量库或 Embedding 服务不可用；</li>
 *   <li>规格 §24.3 的降级表：<b>「内部降级为 MySQL 关键词检索，接口不变」</b>。</li>
 * </ul>
 * 拍板结论：**按 §24.3 内部降级** —— 前端不用改调用方式，也不用为两种完全不同的响应结构
 * 写两套渲染。只有连 LLM 也不可用时才 50300（没有 LLM 就产不出 {@code answer}，而契约必须有它）。
 *
 * <h2>怎么模拟</h2>
 * {@code @SpringBootTest(properties = ...)} 把 Milvus 指向无人监听的端口
 * （比 mock 更真实：走的是 {@code MilvusServiceClient} 真正的连接检查）。
 *
 * <h2>⚠️ 造数据不经过索引流水线</h2>
 * 因为 Milvus 不可用时根本索引不了。这里直接写「文档 + 片段」两张表，
 * 模拟"这篇文档此前已经索引成功"的状态 —— 降级路径本来就只依赖 MySQL。
 */
@SpringBootTest(properties = {
        "spring.ai.vectorstore.milvus.client.host=127.0.0.1",
        "spring.ai.vectorstore.milvus.client.port=19531" // 无人监听 → 连接必然失败
})
@AutoConfigureMockMvc
class RagDegradedTest {

    private static final long ADMIN_ID = 1L;
    private static final String DOC_TITLE = "D12-04 降级测试手册";

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

    @Autowired
    private ObjectMapper objectMapper;

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
        }
        createdIds.clear();
        redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + ADMIN_ID);
    }

    @Test
    @DisplayName("🔴 验收5：Milvus 不可用 → rag-query 仍返回 200（内部降级为关键词检索，不是 50300）")
    void 验收5_Milvus不可用时降级为关键词检索() throws Exception {
        createReadyDocumentWithChunk();

        String body = mockMvc.perform(post("/api/knowledge/rag-query")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"VPN 连不上怎么办\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(body).path("data");

        assertThat(data.path("answer").asText())
                .as("降级要让调用方看得见 —— 不新增字段，写在答案开头")
                .contains("语义检索暂不可用");

        var references = data.path("references");
        assertThat(references.size()).as("关键词召回命中了片段，引用不能是空的").isPositive();

        // ⚠️ 不能断言"第一条就是我们的文档"：降级的关键词召回是**按 documentId 排序**的
        //    （没有相关性排序可言），种子里那份「公司 VPN 使用手册」同样含「VPN」、id 更小，
        //    所以它会排在前面。这里只要求"我们的文档在引用里"，这才是真正该验的行为。
        List<com.fasterxml.jackson.databind.JsonNode> list = new ArrayList<>();
        references.forEach(list::add);

        assertThat(list).anySatisfy(ref -> {
            assertThat(ref.path("documentTitle").asText()).isEqualTo(DOC_TITLE);
            assertThat(ref.path("chunkIndex").asInt()).isZero();
            assertThat(ref.path("score").isNull())
                    .as("★降级时 score 必须是 null —— 关键词检索没有相似度，编一个数会误导用户")
                    .isTrue();
        });
        assertThat(list).allSatisfy(ref -> assertThat(ref.path("score").isNull())
                .as("降级路径下每一条引用的 score 都该是 null")
                .isTrue());
    }

    @Test
    @DisplayName("验收5 补充：降级时关键词召回为空 → 说明「未找到」，仍然 200")
    void 降级后召不到也返回200() throws Exception {
        createReadyDocumentWithChunk(); // 库里有内容，但都是 VPN 相关

        mockMvc.perform(post("/api/knowledge/rag-query")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"银河系漫游指南在哪里下载\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer")
                        .value(org.hamcrest.Matchers.containsString("未在知识库中找到")))
                .andExpect(jsonPath("$.data.references").isEmpty());
    }

    // ==================== 工具 ====================

    private void createReadyDocumentWithChunk() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(DOC_TITLE);
        document.setFileName("degrade.md");
        document.setFilePath("knowledge/test/" + UUID.randomUUID() + ".md");
        document.setStatus(KnowledgeDocStatus.READY);
        document.setChunkCount(1);
        document.setUploaderId(ADMIN_ID);
        knowledgeDocumentService.save(document);
        createdIds.add(document.getId());

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(document.getId());
        chunk.setContent("VPN 无法连接时的排查步骤：先检查客户端版本是否为最新，"
                + "再确认本机网络可以正常访问外网，然后退出账号重新登录排除凭据过期。");
        chunk.setChunkIndex(0);
        chunk.setVectorId("test-degrade-vec");
        knowledgeChunkService.save(chunk);
    }

    private String tokenOf() {
        return "Bearer " + jwtHelper.createToken(ADMIN_ID);
    }
}
