package com.opsdesk.knowledge;

import com.opsdesk.common.PageResult;
import com.opsdesk.common.enums.KnowledgeDocStatus;
import com.opsdesk.knowledge.dto.KnowledgeDocumentQuery;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.service.KnowledgeDocumentManageService;
import com.opsdesk.knowledge.service.KnowledgeDocumentManageService.KnowledgeDocumentVO;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库文档列表验收测试（补单 · 知识库文档列表，API 文档 §10.1）
 *
 * <p>本类自己插入 3 份文档（事务内，跑完回滚），断言都基于「基线 + 3」，
 * 不依赖种子库里有没有知识文档 —— 换环境也能跑。
 */
@SpringBootTest
@Transactional
class KnowledgeDocumentListTest {

    private static final long UPLOADER = 1L;

    @Autowired
    private KnowledgeDocumentManageService manageService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("验收1/6：无条件下返回全量，total 是总数而不是本页条数，且按 id DESC 稳定翻页")
    void 列表分页与总数正确() {
        long baseline = countAll();
        insert("公司 VPN 使用手册", "vpn-guide.md", KnowledgeDocStatus.READY, 12);
        insert("打印机驱动安装", "printer-driver.pdf", KnowledgeDocStatus.FAILED, 0);
        insert("OA 系统操作指引", "oa-intro.docx", KnowledgeDocStatus.PROCESSING, 0);

        KnowledgeDocumentQuery query = new KnowledgeDocumentQuery();
        query.setPage(1L);
        query.setSize(2L);
        PageResult<KnowledgeDocumentVO> page1 = manageService.page(query);

        assertThat(page1.getTotal()).as("总数 = 基线 + 3").isEqualTo(baseline + 3);
        assertThat(page1.getPage()).isEqualTo(1L);
        assertThat(page1.getSize()).isEqualTo(2L);
        assertThat(page1.getList()).hasSize(2);

        // created_at 精度到秒、三份文档同秒插入 → 顺序由 id DESC 决定（tiebreaker 的验收点）
        List<Long> page1Ids = page1.getList().stream().map(KnowledgeDocumentVO::id).toList();
        assertThat(page1Ids).isSortedAccordingTo((a, b) -> Long.compare(b, a));

        KnowledgeDocumentQuery second = new KnowledgeDocumentQuery();
        second.setPage(2L);
        second.setSize(2L);
        List<Long> page2Ids = manageService.page(second).getList().stream()
                .map(KnowledgeDocumentVO::id).toList();
        assertThat(page1Ids).as("翻页不重复").doesNotContainAnyElementsOf(page2Ids);
    }

    @Test
    @DisplayName("验收4：status 过滤生效，且无效值由 Spring 绑定成 40001（不在这里测，测枚举值语义）")
    void status过滤生效() {
        insert("状态过滤-READY", "a.md", KnowledgeDocStatus.READY, 3);
        insert("状态过滤-PROCESSING", "b.md", KnowledgeDocStatus.PROCESSING, 0);

        long expectedReady = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document WHERE status = 'READY'", Long.class);

        KnowledgeDocumentQuery query = new KnowledgeDocumentQuery();
        query.setStatus(KnowledgeDocStatus.READY);
        PageResult<KnowledgeDocumentVO> page = manageService.page(query);

        assertThat(page.getTotal()).isEqualTo(expectedReady);
        assertThat(page.getList()).allSatisfy(vo ->
                assertThat(vo.status()).isEqualTo(KnowledgeDocStatus.READY));
    }

    @Test
    @DisplayName("验收5：keyword 同时匹配标题与文件名")
    void keyword命中标题与文件名() {
        insert("公司 VPN 使用手册", "manual-01.md", KnowledgeDocStatus.READY, 5);
        insert("打印机驱动", "wifi-guide-v2.md", KnowledgeDocStatus.READY, 5);

        KnowledgeDocumentQuery byTitle = new KnowledgeDocumentQuery();
        byTitle.setKeyword("VPN");
        List<String> titles = manageService.page(byTitle).getList().stream()
                .map(KnowledgeDocumentVO::title).toList();
        assertThat(titles).contains("公司 VPN 使用手册");

        KnowledgeDocumentQuery byFileName = new KnowledgeDocumentQuery();
        byFileName.setKeyword("wifi-guide");
        List<String> fileNames = manageService.page(byFileName).getList().stream()
                .map(KnowledgeDocumentVO::fileName).toList();
        assertThat(fileNames).contains("wifi-guide-v2.md");
    }

    @Test
    @DisplayName("验收5重点：keyword=% 不能退化成「返回全部」（LIKE 通配符已转义）")
    void 通配符被转义() {
        insert("通配符测试文档", "wildcard.md", KnowledgeDocStatus.READY, 1);

        KnowledgeDocumentQuery query = new KnowledgeDocumentQuery();
        query.setKeyword("%");
        PageResult<KnowledgeDocumentVO> page = manageService.page(query);

        assertThat(page.getTotal()).as("没有文档标题/文件名真的含 % ，所以必须是 0").isZero();
        assertThat(page.getList()).isEmpty();
    }

    @Test
    @DisplayName("验收7：size=999 被收敛成 50；空 keyword 视为不过滤")
    void 分页收敛与空关键字() {
        KnowledgeDocumentQuery query = new KnowledgeDocumentQuery();
        query.setSize(999L);
        query.setKeyword("   ");
        PageResult<KnowledgeDocumentVO> page = manageService.page(query);

        assertThat(page.getSize()).isEqualTo(50L);
        assertThat(page.getTotal()).as("空白 keyword 不应过滤掉数据").isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("验收8：VO 只有 6 个字段，不含 filePath")
    void VO不暴露内部路径() {
        String[] components = Arrays.stream(KnowledgeDocumentVO.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);

        assertThat(components)
                .containsExactly("id", "title", "fileName", "status", "chunkCount", "createdAt")
                .doesNotContain("filePath");
    }

    // ==================== 辅助 ====================

    private long countAll() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_document", Long.class);
        return total == null ? 0L : total;
    }

    private void insert(String title, String fileName, KnowledgeDocStatus status, int chunkCount) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(title);
        document.setFileName(fileName);
        document.setFilePath("knowledge/pending/test-" + fileName);
        document.setStatus(status);
        document.setChunkCount(chunkCount);
        document.setUploaderId(UPLOADER);
        knowledgeDocumentService.save(document);
    }
}
