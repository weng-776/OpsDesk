package com.opsdesk.ticket;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.utils.FileTypeValidator;
import com.opsdesk.ticket.entity.TicketAttachment;
import com.opsdesk.ticket.service.AttachmentService;
import com.opsdesk.ticket.service.TicketAttachmentService;
import com.opsdesk.ticket.vo.AttachmentUploadVO;
import com.opsdesk.ticket.vo.AttachmentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单附件验收测试（工单 D3-05，SOP §5 红区：文件安全边界 / 防路径穿越）
 *
 * <p>规格依据：API 文档 §8.9 / §8.10 / §8.11 / §16.11、规格基线 §23.1。
 *
 * <h2>本单的三条验收（逐条对应工单）</h2>
 * <ol>
 *   <li>超过 10MB → 拒绝；非白名单类型（如 .exe）→ 拒绝</li>
 *   <li>上传后 MinIO 里的对象名是 UUID；<b>用文件名含 {@code ../} 的恶意上传无法越出前缀</b></li>
 *   <li>下载能拿到正确内容，{@code Content-Disposition} 的原始文件名正确（中文不乱码）</li>
 * </ol>
 *
 * <h2>⚠️ 这些用例会真的写 MinIO 和 MySQL</h2>
 * MySQL 侧由类级 {@link Transactional} 回滚，<b>但 MinIO 不受事务管辖</b> ——
 * 上传的对象会留在 bucket 里。这是刻意的取舍：
 * <ul>
 *   <li>要真实验证「对象名是 UUID」「恶意文件名无法穿越」，就不能 mock 掉存储 ——
 *       mock 的返回值永远符合预期，等于没验。</li>
 *   <li>残留对象的代价可控：测试数据量极小（几 KB），且下载用例<b>需要</b>这些对象存在。
 *       用一个固定前缀（{@code ticket/{真实工单 id}/}）便于事后清理。</li>
 * </ul>
 *
 * <p>种子数据：{@code ticket_attachment} <b>没有任何种子行</b>（已核对 Seed SQL），
 * 所以「工单 X 没有附件」这类断言是可靠的。
 */
@SpringBootTest
@Transactional
class AttachmentServiceTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;
    private static final long EMP_ZHAO = 5L;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private TicketAttachmentService ticketAttachmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 验收 1：大小与类型 ====================

    @Test
    @DisplayName("验收1：非白名单类型（.exe）→ 40001，且不留任何记录")
    void 非白名单类型被拒() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        long before = countAttachments(1L);

        MockMultipartFile exe = new MockMultipartFile(
                "file", "trojan.exe", "application/x-msdownload", "MZ...".getBytes(StandardCharsets.UTF_8));

        assertThat(catchBiz(() -> attachmentService.upload(1L, exe)))
                .as(".exe 不在白名单 → 40001").isEqualTo(ErrorCode.PARAM_INVALID);

        assertThat(countAttachments(1L)).as("被拒的上传不应落库").isEqualTo(before);
    }

    @Test
    @DisplayName("验收1：扩展名伪装（evil.jpg 但 MIME 是 exe）→ 40001（双重判断生效）")
    void 扩展名与MIME不符被拒() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);

        // 名字是 .jpg（在白名单），但 MIME 是 application/x-msdownload（不在 jpg 的允许集合里）
        MockMultipartFile fake = new MockMultipartFile(
                "file", "evil.jpg", "application/x-msdownload", "MZ".getBytes(StandardCharsets.UTF_8));

        assertThat(catchBiz(() -> attachmentService.upload(1L, fake)))
                .as("只看扩展名会让它过，双重判断必须拦住").isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("验收1：超过 10MB → 40001（业务层闸门，不依赖 yml）")
    void 超过10MB被拒() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);

        // 构造 10MB + 1 字节
        byte[] oversize = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile big = new MockMultipartFile(
                "file", "big.png", "image/png", oversize);

        assertThat(catchBiz(() -> attachmentService.upload(1L, big)))
                .as("超 10MB → 40001").isEqualTo(ErrorCode.PARAM_INVALID);

        // 边界对照：正好 10MB 应该通过（用 1KB 真实内容冒充，只验大小闸门不看内容）
        // —— 这里不真传 10MB（慢），改为直接验常量口径：10*1024*1024 才是阈值，
        //    见下面 大小阈值口径与yml一致 用例
    }

    @Test
    @DisplayName("大小阈值口径与 yml 的 10MB 一致（10×1024×1024，不是 10_000_000）")
    void 大小阈值口径与yml一致() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);

        // 10MB - 1 字节：应通过
        byte[] justUnder = new byte[10 * 1024 * 1024 - 1];
        MockMultipartFile ok = new MockMultipartFile("file", "ok.png", "image/png", justUnder);
        assertThat(attachmentService.upload(1L, ok).getId())
                .as("10MB-1 字节应放行（若阈值写成 10_000_000，这里会红）").isNotNull();

        // 10MB + 1 字节：应被拒
        byte[] justOver = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile no = new MockMultipartFile("file", "no.png", "image/png", justOver);
        assertThat(catchBiz(() -> attachmentService.upload(1L, no))).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("验收1：空文件 → 40001")
    void 空文件被拒() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        assertThat(catchBiz(() -> attachmentService.upload(1L, empty)))
                .as("空文件 → 40001").isEqualTo(ErrorCode.PARAM_INVALID);
    }

    // ==================== 验收 2：UUID 化 + 防路径穿越（本单核心） ====================

    @Test
    @DisplayName("🔴 验收2：文件名含 ../ 的恶意上传无法越出 ticket/ 前缀（对象名服务端自造）")
    void 恶意文件名无法路径穿越() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);

        // 三种典型穿越 payload
        List<String> evilNames = List.of(
                "../../../../etc/passwd.png",
                "..\\..\\..\\windows\\system32\\evil.png",
                "....//....//config.png");

        for (String evil : evilNames) {
            AttachmentUploadVO vo = attachmentService.upload(1L, new MockMultipartFile(
                    "file", evil, "image/png", "harmless".getBytes(StandardCharsets.UTF_8)));

            TicketAttachment saved = ticketAttachmentService.getById(vo.getId());

            // 核心断言：存储路径必须是 ticket/{id}/{uuid}.png，与用户文件名毫无关系
            assertThat(saved.getFilePath())
                    .as("恶意名 [%s] 不得影响存储路径", evil)
                    .startsWith("ticket/" + 1L + "/")
                    .doesNotContain("..")
                    .doesNotContain("etc")
                    .doesNotContain("windows")
                    .doesNotContain("config");

            // 第三段必须是 UUID 形态（36 字符、4 个连字符）
            String objectSegment = saved.getFilePath().substring(saved.getFilePath().lastIndexOf('/') + 1);
            String uuidPart = objectSegment.substring(0, objectSegment.lastIndexOf('.'));
            assertThat(uuidPart)
                    .as("对象名必须是 UUID（=36 字符）")
                    .hasSize(36)
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
    }

    @Test
    @DisplayName("验收2：同一文件连传两次 → 两个不同的对象名（UUID 不重复，不会互相覆盖）")
    void 同名文件两次上传对象名不同() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);

        MockMultipartFile f1 = new MockMultipartFile("file", "same.png", "image/png", "a".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile f2 = new MockMultipartFile("file", "same.png", "image/png", "b".getBytes(StandardCharsets.UTF_8));

        String p1 = ticketAttachmentService.getById(attachmentService.upload(1L, f1).getId()).getFilePath();
        String p2 = ticketAttachmentService.getById(attachmentService.upload(1L, f2).getId()).getFilePath();

        assertThat(p1).as("同名文件不得互相覆盖（UUID 化后路径必然不同）").isNotEqualTo(p2);
        assertThat(p1).as("两次都应在同一工单目录下").startsWith("ticket/1/");
        assertThat(p2).startsWith("ticket/1/");
    }

    @Test
    @DisplayName("验收2：原始文件名完整保留在 DB（仅展示用），不与存储路径混用")
    void 原始文件名保留在DB() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        MockMultipartFile f = new MockMultipartFile(
                "file", "报错截图.png", "image/png", "png-bytes".getBytes(StandardCharsets.UTF_8));

        AttachmentUploadVO vo = attachmentService.upload(1L, f);

        assertThat(vo.getFileName()).as("中文原名回显").isEqualTo("报错截图.png");
        assertThat(ticketAttachmentService.getById(vo.getId()).getFileName())
                .as("DB 里存的是原始中文名").isEqualTo("报错截图.png");

        // 存储路径里绝不能出现中文原名
        assertThat(ticketAttachmentService.getById(vo.getId()).getFilePath())
                .as("存储路径不得含中文原名").doesNotContain("报错截图");
    }

    @Test
    @DisplayName("客户端的完整本地路径（C:\\Users\\..\\a.png）→ 只取最后一段展示")
    void 本地完整路径被清洗为纯文件名() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        MockMultipartFile f = new MockMultipartFile(
                "file", "C:\\Users\\x\\Desktop\\a.png", "image/png", "x".getBytes(StandardCharsets.UTF_8));

        AttachmentUploadVO vo = attachmentService.upload(1L, f);

        assertThat(vo.getFileName())
                .as("只保留最后一段，不要把整条本地路径展示给同事")
                .isEqualTo("a.png");
    }

    // ==================== 验收 3：列表与下载 ====================

    @Test
    @DisplayName("验收3：上传后列表能查到，字段与 §16.11 对齐（含 uploaderName）")
    void 列表字段装配正确() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        attachmentService.upload(1L, new MockMultipartFile(
                "file", "log.txt", "text/plain", "log-content".getBytes(StandardCharsets.UTF_8)));

        List<AttachmentVO> list = attachmentService.list(1L);

        assertThat(list).as("刚传的附件在列表里").hasSize(1);
        AttachmentVO vo = list.get(0);
        assertThat(vo.getId()).isNotNull();
        assertThat(vo.getFileName()).isEqualTo("log.txt");
        assertThat(vo.getFileSize()).isEqualTo("log-content".length());
        assertThat(vo.getContentType()).isEqualTo("text/plain");
        assertThat(vo.getUploaderName()).as("上传人姓名由 SQL JOIN 装配（emp_wang = 王五）").isEqualTo("王五");
        assertThat(vo.getCreatedAt()).as("created_at 已查出").isNotNull();

        // 字段恰好 6 个（§16.11），且不暴露 filePath
        Set<String> fields = java.util.Arrays.stream(AttachmentVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(fields).as("§16.11 恰好 6 个字段").containsExactlyInAnyOrder(
                "id", "fileName", "fileSize", "contentType", "uploaderName", "createdAt");
        assertThat(fields).as("绝不对外暴露存储路径").doesNotContain("filePath", "ticketId", "uploaderId");
    }

    @Test
    @DisplayName("验收3：下载能拿到原始内容（字节级一致）")
    void 下载内容一致() throws Exception {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        byte[] content = "这是一段用于校验下载内容的中文文本".getBytes(StandardCharsets.UTF_8);

        AttachmentUploadVO uploaded = attachmentService.upload(1L,
                new MockMultipartFile("file", "note.txt", "text/plain", content));

        AttachmentService.DownloadPayload payload = attachmentService.download(uploaded.getId());
        assertThat(payload.fileName()).as("下载载荷带原始文件名").isEqualTo("note.txt");
        assertThat(payload.size()).isEqualTo((long) content.length);

        byte[] downloaded;
        try (InputStream in = payload.stream()) {
            downloaded = in.readAllBytes();
        }
        assertThat(downloaded).as("下载内容与上传字节级一致").isEqualTo(content);
    }

    @Test
    @DisplayName("验收3：列表按上传时间升序（先传的先出现）")
    void 列表按时间升序() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        attachmentService.upload(1L, new MockMultipartFile("file", "a.txt", "text/plain", "1".getBytes()));
        attachmentService.upload(1L, new MockMultipartFile("file", "b.txt", "text/plain", "2".getBytes()));
        attachmentService.upload(1L, new MockMultipartFile("file", "c.txt", "text/plain", "3".getBytes()));

        List<AttachmentVO> list = attachmentService.list(1L);
        assertThat(list).extracting(AttachmentVO::getFileName).containsExactly("a.txt", "b.txt", "c.txt");
    }

    // ==================== 数据范围（SOP §5 红区） ====================

    @Test
    @DisplayName("越权：对无可见性的工单上传 → 40301；列表同理")
    void 越权上传40301() {
        // emp_wang(4) 看不到工单 2（emp_zhao 创建、技术部）
        assertThat(catchBiz(() -> {
            setCurrentUser(EMP_WANG, Role.EMPLOYEE);
            attachmentService.upload(2L, new MockMultipartFile(
                    "file", "x.png", "image/png", "x".getBytes(StandardCharsets.UTF_8)));
        })).as("无可见性 → 40301").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        assertThat(catchBiz(() -> {
            setCurrentUser(EMP_WANG, Role.EMPLOYEE);
            attachmentService.list(2L);
        })).as("列表也要 40301").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        // 反证：能看的人可以上传
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        assertThat(attachmentService.upload(2L, new MockMultipartFile(
                "file", "ok.png", "image/png", "ok".getBytes(StandardCharsets.UTF_8))).getId()).isNotNull();
    }

    @Test
    @DisplayName("🔴 越权下载 → 40301：不能靠「猜附件 id」绕过工单可见性")
    void 越权下载40301() {
        // emp_zhao(5) 在工单 2 上传一个附件（他对工单 2 可见）
        setCurrentUser(EMP_ZHAO, Role.EMPLOYEE);
        Long attachmentId = attachmentService.upload(2L, new MockMultipartFile(
                "file", "secret.png", "image/png", "secret".getBytes(StandardCharsets.UTF_8))).getId();

        // emp_wang(4) 看不到工单 2，即使知道附件 id 也不能下载
        assertThat(catchBiz(() -> {
            setCurrentUser(EMP_WANG, Role.EMPLOYEE);
            attachmentService.download(attachmentId);
        })).as("附件可见性 = 其工单可见性 → 40301").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        // 反证：能看到工单 2 的人（受理人）能下载
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        assertThat(attachmentService.download(attachmentId).fileName()).isEqualTo("secret.png");
    }

    @Test
    @DisplayName("不存在的工单 → 40400（不是 40301 —— 不泄露存在性）；三种角色一致")
    void 不存在工单40400() {
        for (Role role : new Role[]{Role.EMPLOYEE, Role.AGENT, Role.ADMIN}) {
            assertThat(catchBiz(() -> {
                setCurrentUser(EMP_WANG, role);
                attachmentService.list(999_999L);
            })).as("%s 查不存在工单 → 40400", role).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("不存在的附件 id → 40400（下载入口）")
    void 不存在附件40400() {
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> attachmentService.download(999_999L)))
                .as("附件不存在 → 40400").isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== FileTypeValidator 单测（白名单语义） ====================

    @Test
    @DisplayName("FileTypeValidator：8 种白名单扩展名全部通过，None 与非法名被拒")
    void 白名单语义正确() {
        // 白名单内（MIME 用各自常见值）
        assertThat(FileTypeValidator.validate("a.jpg", "image/jpeg")).isEqualTo("jpg");
        assertThat(FileTypeValidator.validate("a.PNG", "image/png")).as("大小写不敏感").isEqualTo("png");
        assertThat(FileTypeValidator.validate("a.gif", "image/gif")).isEqualTo("gif");
        assertThat(FileTypeValidator.validate("a.pdf", "application/pdf")).isEqualTo("pdf");
        assertThat(FileTypeValidator.validate("a.txt", "text/plain")).isEqualTo("txt");
        assertThat(FileTypeValidator.validate("a.log", "text/plain")).isEqualTo("log");
        assertThat(FileTypeValidator.validate("a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isEqualTo("docx");
        assertThat(FileTypeValidator.validate("a.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isEqualTo("xlsx");

        // MIME 带参数应被归一化（不能因为 "; charset=utf-8" 就拒）
        assertThat(FileTypeValidator.validate("a.txt", "text/plain; charset=UTF-8")).isEqualTo("txt");
        // MIME 缺失放行（有些客户端不发 Content-Type）
        assertThat(FileTypeValidator.validate("a.txt", null)).isEqualTo("txt");

        // 白名单外 / 无扩展名 / 空名
        assertThat(FileTypeValidator.supports("exe")).isFalse();
        assertThat(FileTypeValidator.supports("sh")).isFalse();
        assertThat(FileTypeValidator.supports("svg")).as("svg 可内联执行脚本，必须不在白名单").isFalse();
        assertThat(FileTypeValidator.supports("html")).as("html 同理").isFalse();
        assertThat(catchBiz(() -> FileTypeValidator.validate("noext", "text/plain")))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(catchBiz(() -> FileTypeValidator.validate(null, "text/plain")))
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(catchBiz(() -> FileTypeValidator.validate("a.xyz", "image/png")))
                .isEqualTo(ErrorCode.PARAM_INVALID);

        // 扩展名里混入路径分隔符 → 不认（防 "jpg/" 这种）
        assertThat(FileTypeValidator.extensionOf("a.jpg/../b")).isNull();
        assertThat(FileTypeValidator.extensionOf("a.")).as("尾点 → 无扩展名").isNull();
        assertThat(FileTypeValidator.extensionOf(".gitignore")).as("点开头 → 非扩展名").isNull();
        assertThat(FileTypeValidator.whitelistText()).isEqualTo("jpg/png/gif/pdf/txt/log/docx/xlsx");
    }

    // ==================== 工具 ====================

    private long countAttachments(long ticketId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_attachment WHERE ticket_id = ?", Long.class, ticketId);
        return n == null ? 0 : n;
    }

    private void setCurrentUser(long userId, Role role) {
        UserContext.set(new UserContext.CurrentUser(
                userId, "test-jti", Set.of(role), Set.of(), departmentIdOf(userId)));
    }

    private Long departmentIdOf(long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static ErrorCode catchBiz(Runnable action) {
        try {
            action.run();
        }
        catch (BizException ex) {
            return ex.getErrorCode();
        }
        throw new AssertionError("预期抛出 BizException，但调用正常返回了");
    }
}
