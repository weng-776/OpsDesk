package com.opsdesk.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.JwtHelper;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.config.properties.MinioProperties;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D12-01 验收：知识文档上传（API §10.2 / §10.3，规格基线 §12.3）
 *
 * <h2>⚠️ 关于 21MB 那条用例测的是什么</h2>
 * MockMvc 不走真实的 Servlet 容器 multipart 解析，所以 yml 的
 * {@code spring.servlet.multipart.max-file-size} 在这里<b>不会生效</b> ——
 * 本用例验的是<b>业务层那道闸门</b>（{@code KnowledgeDocumentManageServiceImpl.MAX_SIZE_BYTES}），
 * 而它才是真正的限制（D3-05 的原话：「不信任 yml」）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeDocumentUploadTest {

    private static final long ADMIN_ID = 1L;
    private static final long AGENT_ZHANG_ID = 2L;
    private static final long EMP_WANG_ID = 4L;
    private static final List<Long> TOUCHED = List.of(ADMIN_ID, AGENT_ZHANG_ID, EMP_WANG_ID);

    /** 服务端自造的对象名：knowledge/{documentId}/{UUID}.{ext} —— 不含原始文件名的任何字符 */
    private static final Pattern OBJECT_NAME = Pattern.compile(
            "^knowledge/\\d+/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(md|txt)$");

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

    /** 本测试造出来的文档，跑完要连对象一起清掉（验收：测试自清） */
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void cleanAuthCache() {
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            String objectName = jdbcTemplate.query(
                    "SELECT file_path FROM knowledge_document WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, id);
            // ObjectStorage 接口刻意没有 delete（本单不需要它；§10.4 删文档时才需要），
            // 所以测试直接用 MinioClient 清理，避免"为了测试去扩大生产接口"
            if (objectName != null && !objectName.startsWith("knowledge/pending/")) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .build());
                }
                catch (Exception ex) {
                    // 清理失败不影响测试结论，仅记录
                    System.out.println("[测试清理] 删除对象失败 object=" + objectName + " cause=" + ex.getMessage());
                }
            }
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", id);
        }
        createdIds.clear();
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    // ==================== 验收 1：上传成功 ====================

    @Test
    @DisplayName("验收1：ADMIN 上传 .md → {id, PROCESSING}；落库字段完整，file_path 是 UUID 形式")
    void 上传md成功() throws Exception {
        long id = uploadAndGetId(adminToken(), "vpn-guide.md", "text/markdown",
                "# VPN 使用手册\n\n连接失败时先检查客户端。".getBytes(StandardCharsets.UTF_8), null);

        // 响应：立即返回，状态恒为 PROCESSING（解析是 D12-05 的异步流水线）
        // 库里的字段
        var row = jdbcTemplate.queryForMap(
                "SELECT title, file_name, file_path, status, chunk_count, uploader_id "
                        + "FROM knowledge_document WHERE id = ?", id);

        assertThat(row.get("title")).as("title 可空 → 默认取文件名（去扩展名）").isEqualTo("vpn-guide");
        assertThat(row.get("file_name")).as("原始文件名只用于展示").isEqualTo("vpn-guide.md");
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(((Number) row.get("chunk_count")).intValue()).as("§12.3：chunk_count 初始 0").isZero();
        assertThat(((Number) row.get("uploader_id")).longValue()).isEqualTo(ADMIN_ID);

        String filePath = (String) row.get("file_path");
        assertThat(filePath)
                .as("🔴 对象名必须是服务端自造的 knowledge/{id}/{UUID}.{ext}")
                .matches(OBJECT_NAME)
                .doesNotContain("vpn-guide");

        // 对象真的落到 MinIO 了（不是只写了 DB）
        assertThat(objectStorage.exists(filePath)).as("对象应当真的落盘").isTrue();
    }

    @Test
    @DisplayName("验收1 补充：显式传 title → 用传入的标题；详情接口能查到 PROCESSING / chunkCount=0")
    void 显式标题与详情() throws Exception {
        long id = uploadAndGetId(adminToken(), "guide.md", "text/markdown",
                "内容".getBytes(StandardCharsets.UTF_8), "公司 VPN 手册");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM knowledge_document WHERE id = ?", String.class, id))
                .isEqualTo("公司 VPN 手册");

        mockMvc.perform(get("/api/knowledge/documents/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, empToken()))   // §22：知识库查看所有角色都有
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.chunkCount").value(0))
                .andExpect(jsonPath("$.data.title").value("公司 VPN 手册"))
                .andExpect(jsonPath("$.data.filePath").doesNotExist());   // 不外泄存储细节
    }

    @Test
    @DisplayName("上传 .txt（MIME text/plain）→ 成功")
    void 上传txt成功() throws Exception {
        long id = uploadAndGetId(adminToken(), "notes.txt", "text/plain",
                "排查步骤：先看客户端。".getBytes(StandardCharsets.UTF_8), null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT file_path FROM knowledge_document WHERE id = ?", String.class, id))
                .endsWith(".txt");
    }

    // ==================== 验收 2：类型白名单 ====================

    @Test
    @DisplayName("验收2：上传 .exe → 40001（类型不在白名单）")
    void 上传exe被拒() throws Exception {
        upload(adminToken(), "virus.exe", "application/octet-stream", new byte[]{1, 2, 3}, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("验收2 补充：docx / pdf 不是笼统拒绝，而是明确告诉用户「暂不支持，请转成 md/txt」")
    void 暂不支持的格式有明确提示() throws Exception {
        upload(adminToken(), "doc.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1, 2, 3}, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("暂不支持")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("md")));
    }

    // ==================== 验收 3：大小上限 ====================

    @Test
    @DisplayName("验收3：上传 21MB → 40001（业务层闸门：20 * 1024 * 1024，不是 20_000_000）")
    void 超过20MB被拒() throws Exception {
        byte[] tooBig = new byte[21 * 1024 * 1024];

        upload(adminToken(), "big.md", "text/markdown", tooBig, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("20MB")));
    }

    @Test
    @DisplayName("验收3 补充：刚好 20MiB 是【允许】的（边界值，证明用的是 1024 口径）")
    void 恰好20MiB允许() throws Exception {
        byte[] exactly = new byte[20 * 1024 * 1024];

        long id = uploadAndGetId(adminToken(), "exact.md", "text/markdown", exactly, null);
        assertThat(id).isPositive();
    }

    // ==================== 验收 4：权限 ====================

    @Test
    @DisplayName("验收4：AGENT / EMPLOYEE 上传 → 40300（无 knowledge:manage）")
    void 无权限被拒() throws Exception {
        upload(agentToken(), "a.md", "text/markdown", "x".getBytes(StandardCharsets.UTF_8), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        upload(empToken(), "b.md", "text/markdown", "x".getBytes(StandardCharsets.UTF_8), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("未登录 → 40100")
    void 未登录被拒() throws Exception {
        mockMvc.perform(multipart("/api/knowledge/documents")
                        .file(new MockMultipartFile("file", "a.md", "text/markdown", "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ==================== 验收 5：防路径穿越 ====================

    @Test
    @DisplayName("验收5：原始文件名含 ../ → file_path 里不出现该字符（对象名服务端自造）")
    void 文件名穿越无效() throws Exception {
        long id = uploadAndGetId(adminToken(), "../../etc/passwd.md", "text/markdown",
                "恶意文件名".getBytes(StandardCharsets.UTF_8), null);

        var row = jdbcTemplate.queryForMap(
                "SELECT file_name, file_path FROM knowledge_document WHERE id = ?", id);
        String filePath = (String) row.get("file_path");

        assertThat(filePath)
                .as("🔴 路径穿越在结构上不可能生效：对象名里没有用户输入")
                .matches(OBJECT_NAME)
                .doesNotContain("..")
                .doesNotContain("passwd")
                .doesNotContain("etc");
        // 展示名会把目录部分剥掉，只留最后一段
        assertThat(row.get("file_name")).as("展示名剥掉目录部分").isEqualTo("passwd.md");
        assertThat(objectStorage.exists(filePath)).isTrue();
    }

    // ==================== 详情接口的 40400 ====================

    @Test
    @DisplayName("详情：文档不存在 → 40400")
    void 详情不存在() throws Exception {
        mockMvc.perform(get("/api/knowledge/documents/{id}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    // ==================== 工具 ====================

    private ResultActions upload(String token, String fileName, String contentType, byte[] content, String title)
            throws Exception {
        var request = multipart("/api/knowledge/documents")
                .file(new MockMultipartFile("file", fileName, contentType, content))
                .header(HttpHeaders.AUTHORIZATION, token);
        if (title != null) {
            request = request.param("title", title);
        }
        return mockMvc.perform(request);
    }

    private long uploadAndGetId(String token, String fileName, String contentType, byte[] content, String title)
            throws Exception {
        String body = upload(token, fileName, contentType, content, title)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).path("data").path("id").asLong();
        assertThat(id).as("上传应返回 documentId").isPositive();
        createdIds.add(id);
        return id;
    }

    private String adminToken() {
        return "Bearer " + jwtHelper.createToken(ADMIN_ID);
    }

    private String agentToken() {
        return "Bearer " + jwtHelper.createToken(AGENT_ZHANG_ID);
    }

    private String empToken() {
        return "Bearer " + jwtHelper.createToken(EMP_WANG_ID);
    }
}
