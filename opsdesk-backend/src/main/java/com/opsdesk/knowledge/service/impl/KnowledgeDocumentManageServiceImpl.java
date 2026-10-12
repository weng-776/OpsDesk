package com.opsdesk.knowledge.service.impl;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.KnowledgeDocStatus;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.parser.KnowledgeDocumentTypes;
import com.opsdesk.knowledge.service.KnowledgeDocumentManageService;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 知识文档上传实现（工单 D12-01，SOP §5 红区：文件安全边界）
 *
 * <h2>校验顺序</h2>
 * <pre>
 * ① 文件非空            → 40001
 * ② 大小 ≤ 20MiB        → 40001
 * ③ 扩展名 + MIME 白名单 → 40001
 * ④ 落库（PROCESSING）→ 拿 documentId
 * ⑤ 落 MinIO（对象名含 documentId）
 * ⑥ 回写 file_path
 * </pre>
 * ⚠️ 与 {@code AttachmentServiceImpl} 的顺序<b>不同</b>，这不是疏漏：
 * 附件要先判「工单是否存在 + 有没有数据范围」（否则能从报错形态推断出工单存不存在），
 * 而知识文档<b>没有"属于谁"这个概念</b>（§22：所有登录角色都能查看知识库），
 * 所以这里直接从文件校验开始。
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
    private final ObjectStorage objectStorage;

    public KnowledgeDocumentManageServiceImpl(KnowledgeDocumentService knowledgeDocumentService,
                                              ObjectStorage objectStorage) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.objectStorage = objectStorage;
    }

    // ==================== 上传（§10.2） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
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

        log.info("[知识文档上传] 成功。documentId={} uploaderId={} size={} object={} ext={}",
                document.getId(), document.getUploaderId(), size, objectName, ext);

        // ⑦ 立即返回 —— 解析/切分/向量化是 D12-05 的异步流水线，与本次请求无关
        return new KnowledgeDocumentUploadResult(document.getId(), KnowledgeDocStatus.PROCESSING);
    }

    // ==================== 详情（§10.3） ====================

    @Override
    public KnowledgeDocumentVO detail(Long id) {
        KnowledgeDocument document = id == null ? null : knowledgeDocumentService.getById(id);
        if (document == null) {
            throw BizException.notFound("文档不存在");
        }
        return new KnowledgeDocumentVO(
                document.getId(),
                document.getTitle(),
                document.getFileName(),
                document.getStatus(),
                document.getChunkCount(),
                document.getCreatedAt());
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
}
