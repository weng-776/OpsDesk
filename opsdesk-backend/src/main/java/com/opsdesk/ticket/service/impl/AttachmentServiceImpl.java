package com.opsdesk.ticket.service.impl;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.common.utils.FileTypeValidator;
import com.opsdesk.common.storage.ObjectStorage;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.entity.TicketAttachment;
import com.opsdesk.ticket.mapper.TicketAttachmentMapper;
import com.opsdesk.ticket.service.AttachmentService;
import com.opsdesk.ticket.service.TicketAttachmentService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.vo.AttachmentUploadVO;
import com.opsdesk.ticket.vo.AttachmentVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * 工单附件业务实现（工单 D3-05，SOP §5 红区：涉及文件安全边界）
 *
 * <p>规格依据：API 文档 §8.9 / §8.10 / §8.11 / §16.11；规格基线 §23.1。
 *
 * <h2>🔴 防路径穿越：本模块最核心的一条规则</h2>
 * <pre>
 * 存储对象名 = "ticket/" + ticketId + "/" + UUID.randomUUID() + "." + 规范化扩展名
 * </pre>
 * <b>对象名完全由服务端拼装，原始文件名不参与任何一个字符</b>。
 * 因此 {@code ../../etc/passwd}、{@code ..\\..\\windows\\system32}、{@code C:\\evil.jpg}
 * 这类输入<b>在结构上无法生效</b> —— 它们只会落到 DB 的 {@code file_name} 字段里当一个展示字符串。
 *
 * <p>这与「把用户文件名 sanitize 之后再用」有本质区别：sanitize 是黑名单（总有绕过方式：
 * Unicode 归一化、Windows 保留名 {@code CON}/{@code NUL}、超长名、空字节……），
 * 而「服务端自造名字」是白名单 —— <b>根本没有用户输入进去</b>。
 * 附件表 DDL 的注释也明说了这一点：「原始文件名（仅用于展示，不参与路径拼接）」。
 *
 * <h2>校验链顺序（固定，不要调换）</h2>
 * <ol>
 *   <li><b>工单存在性 + 数据范围</b> —— 不存在 40400，不可见 40301。<b>先于文件校验</b>：
 *       对一个无权访问的工单，不应该从其上传报错信息里推断出「这单存在」。
 *       而如果先校验文件、后校验权限，非法文件会先报 40001 —— 攻击者据此能区分
 *       「工单不存在」和「工单存在但我无权」。</li>
 *   <li>文件非空</li>
 *   <li>大小 ≤ 10MB（<b>业务层自己判，不信任 yml</b>，见下）</li>
 *   <li>扩展名 + MIME 双白名单（{@link FileTypeValidator}）</li>
 *   <li>落 MinIO（对象名服务端拼）→ 落 DB</li>
 * </ol>
 *
 * <h2>⚠️ 为什么大小要在业务层再判一次</h2>
 * 工单要求原话：「大小上限 10MB 由 {@code application.yml} 的
 * {@code spring.servlet.multipart.max-file-size} 兜底，<b>业务层仍要再校验一次</b>（配置可能被改）」。
 * 另外两者报错形态也不同：yml 超限是 Spring 抛 {@code MaxUploadSizeExceededException}
 * （HTTP 层，发生在进 Controller 之前），业务层判的是「能进来的文件是否合规」——
 * 如果哪天有人把 yml 调成 50MB，业务层的 10MB 才是真正的闸门。
 */
@Service
public class AttachmentServiceImpl implements AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentServiceImpl.class);

    /**
     * 附件大小上限 10MB（§23.1）。
     *
     * <p>⚠️ 用 {@code 10 * 1024 * 1024} 而不是 {@code 10_000_000}：
     * yml 里的 {@code 10MB} 是 Spring 的 DataSize 语法 = 10 × 1024 × 1024。
     * 两处如果口径不同（一边 10MiB、一边 10MB），会出现
     * 「yml 放行了、业务层拒了」的诡异边界 —— 必须用同一口径。
     */
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    /** 对象名前缀：所有附件都在这个目录下，便于 bucket 内做生命周期策略 */
    private static final String OBJECT_PREFIX = "ticket/";

    private final TicketService ticketService;
    private final TicketAttachmentService ticketAttachmentService;
    private final TicketAttachmentMapper attachmentMapper;
    private final TicketDataScopeHelper dataScopeHelper;
    private final ObjectStorage objectStorage;

    public AttachmentServiceImpl(TicketService ticketService,
                                 TicketAttachmentService ticketAttachmentService,
                                 TicketAttachmentMapper attachmentMapper,
                                 TicketDataScopeHelper dataScopeHelper,
                                 ObjectStorage objectStorage) {
        this.ticketService = ticketService;
        this.ticketAttachmentService = ticketAttachmentService;
        this.attachmentMapper = attachmentMapper;
        this.dataScopeHelper = dataScopeHelper;
        this.objectStorage = objectStorage;
    }

    // ==================== 上传（§8.9） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttachmentUploadVO upload(Long ticketId, MultipartFile file) {
        // ① 先判工单可见性（不存在 40400 / 不可见 40301）—— 先于文件校验，见类注释
        Ticket ticket = loadVisibleTicket(ticketId);

        // ② 文件非空
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "上传文件不能为空");
        }

        // ③ 大小（业务层闸门，与 yml 口径一致：10 × 1024 × 1024）
        long size = file.getSize();
        if (size > MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "附件大小不能超过 10MB（当前 " + humanSize(size) + "）");
        }

        // ④ 扩展名 + MIME 双白名单；返回归一化扩展名
        String originalName = file.getOriginalFilename();
        String ext = FileTypeValidator.validate(originalName, file.getContentType());

        // ⑤ 对象名 = 服务端自造（UUID），原始文件名不参与 —— 防路径穿越的关键
        String objectName = buildObjectName(ticket.getId(), ext);
        assertSafeObjectName(objectName);   // 防御性双保险，见方法注释

        try (InputStream in = file.getInputStream()) {
            objectStorage.put(objectName, in, size, file.getContentType());
        }
        catch (IOException ex) {
            log.error("[附件上传] 读取上传流失败 ticketId={} fileName={}", ticketId, originalName, ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "读取上传文件失败");
        }

        TicketAttachment attachment = new TicketAttachment();
        attachment.setTicketId(ticket.getId());
        attachment.setUploaderId(UserContext.get().userId());
        // 原始文件名只落这一列，仅用于展示（DDL 注释原话：不参与路径拼接）
        attachment.setFileName(sanitizeDisplayName(originalName));
        attachment.setFilePath(objectName);
        attachment.setFileSize(size);
        attachment.setContentType(file.getContentType());
        // created_at 由 DB 填（实体上 insertStrategy = NEVER）
        ticketAttachmentService.save(attachment);

        log.info("[附件上传] 成功。ticketId={} attachmentId={} uploaderId={} size={} object={}",
                ticket.getId(), attachment.getId(), attachment.getUploaderId(), size, objectName);

        AttachmentUploadVO vo = new AttachmentUploadVO();
        vo.setId(attachment.getId());
        vo.setFileName(attachment.getFileName());
        vo.setFileSize(size);
        return vo;
    }

    // ==================== 列表（§8.10） ====================

    @Override
    public List<AttachmentVO> list(Long ticketId) {
        // 数据范围校验（不存在 40400 / 不可见 40301）—— 与上传同一道门
        Ticket ticket = loadVisibleTicket(ticketId);

        // 自定义 SQL 走 mapper XML（含 JOIN user 取 uploaderName）—— 见 TicketAttachmentMapper.xml
        List<AttachmentVO> list = attachmentMapper.selectByTicketIdWithUploader(ticket.getId());

        // createdAt 也一并查出来（本单用自定义 SQL，不受 known-traps #5「insert 不回填默认值」影响，
        // 因为这里读的是已有行）
        log.debug("[附件列表] ticketId={} count={}", ticket.getId(), list.size());
        return list;
    }

    // ==================== 下载（§8.11） ====================

    @Override
    public DownloadPayload download(Long attachmentId) {
        // ① 附件本身存在吗？—— 不存在 40400（先判存在，再判权限，见下）
        TicketAttachment attachment = ticketAttachmentService.getById(attachmentId);
        if (attachment == null) {
            throw BizException.notFound("附件不存在");
        }

        // ② 它所属的工单在不在当前用户的数据范围内？—— 不可见 40301
        //    ⚠️ 这里查工单也要先判 null：理论上 attachment.ticket_id 有外键约束，
        //       但工单若是物理删除/数据异常，ticket 会是 null —— 此时按 40400 处理，
        //       而不是让 assertVisible(null) 抛 NPE（那是 50000，把内部结构泄露到日志/响应）
        Ticket ticket = ticketService.getById(attachment.getTicketId());
        if (ticket == null) {
            throw BizException.notFound("附件所属工单不存在");
        }
        dataScopeHelper.assertVisible(ticket, UserContext.get());

        // ③ 对象是否真的在 MinIO 里（DB 有记录、对象被误删 → 40400「文件已丢失」）
        if (!objectStorage.exists(attachment.getFilePath())) {
            log.error("[附件下载] DB 有记录但对象缺失 attachmentId={} object={}",
                    attachmentId, attachment.getFilePath());
            throw BizException.notFound("附件文件已丢失，请联系管理员");
        }

        InputStream stream = objectStorage.get(attachment.getFilePath());
        return new DownloadPayload(
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getFileSize(),
                stream);
    }

    // ==================== 私有 ====================

    /**
     * 按 id 取工单并做数据范围校验。
     *
     * <p>⚠️ 顺序与 D3-03 详情 / D3-04 评论<b>完全一致，不能反</b>：
     * 先判「不存在」→ 40400，再判「不可见」→ 40301。
     * 若先判可见性，不存在的 id 也会得到 40301，攻击者能从错误码推断出该 id 是否存在（§2.7）。
     */
    private Ticket loadVisibleTicket(Long ticketId) {
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            throw BizException.notFound("工单不存在");
        }
        dataScopeHelper.assertVisible(ticket, UserContext.get());
        return ticket;
    }

    /**
     * 拼存储对象名：{@code ticket/{ticketId}/{UUID}.{ext}}。
     *
     * <p>三个组成部分的来源：
     * <ul>
     *   <li>{@code ticket/} —— 常量前缀（本类的 {@code OBJECT_PREFIX}）</li>
     *   <li>{@code ticketId} —— {@code Long}，已经过 {@code getById} 确认存在，<b>不可能含路径字符</b></li>
     *   <li>{@code UUID} —— 纯生成，无外部输入</li>
     *   <li>{@code ext} —— 已经过 {@link FileTypeValidator#validate}，
     *       且 {@code extensionOf} 保证只含字母数字（含 {@code /}、{@code .}、空白的都返回 null 被拒）</li>
     * </ul>
     * 四个部分全部可信 → 拼出来的名字不可能逃出 {@code ticket/} 前缀。
     */
    private String buildObjectName(Long ticketId, String ext) {
        return OBJECT_PREFIX + ticketId + "/" + UUID.randomUUID() + "." + ext;
    }

    /**
     * 防御性断言：对象名必须以 {@code ticket/} 开头、且不含 {@code ..}。
     *
     * <p>本方法在正常路径上<b>永远不会触发</b>（{@link #buildObjectName} 的四个片段都可信）。
     * 那为什么还要写？—— 因为这是安全边界，一旦将来有人「优化」了 {@code buildObjectName}
     * 或换了调用方，这行断言会立刻炸掉，而不是静默地产生一个可穿越的对象名。
     * 属于「让错误在最近的地方暴露」的守卫，测试里会有一条单测专门钉住它。
     */
    private void assertSafeObjectName(String objectName) {
        if (!objectName.startsWith(OBJECT_PREFIX) || objectName.contains("..")) {
            // 这是代码 bug，不是用户输入问题 → 50000
            throw new BizException(ErrorCode.SYSTEM_ERROR, "附件存储路径非法");
        }
    }

    /**
     * 清洗「仅用于展示」的原始文件名。
     *
     * <p>注意：<b>这不是防路径穿越的手段</b>（穿越已经由「对象名自造」在结构上杜绝）。
     * 这里只做两件展示层的事：
     * <ol>
     *   <li>剥掉客户端可能带上来的<b>目录部分</b> —— 有些浏览器/旧客户端会把
     *       {@code C:\\Users\\x\\Desktop\\a.png} 整个当文件名传上来，
     *       直接存进 DB 再显示，用户会看到一串乱路径。只取最后一段。</li>
     *   <li>长度截断到 255（DDL 是 {@code VARCHAR(255)}）—— 否则插入会抛数据截断异常，
     *       用户看到 50000 而不是「文件名太长」。按字符数（不是字节数）截，
     *       因为 utf8mb4 下 255 字符才是该列的真实容量。</li>
     * </ol>
     *
     * <p>清洗后若为空（例如原本只有目录部分），回落成 {@code "attachment"}。
     */
    private String sanitizeDisplayName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "attachment";
        }
        // 同时按 / 和 \ 切，取最后一段（\ 在 Unix 文件名里合法，但客户端几乎不会是那个意思）
        String name = originalName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            return "attachment";
        }
        return name.length() <= 255 ? name : name.substring(0, 255);
    }

    /** 人读的字节数（异常文案里用，避免「10485761 字节」这种数字） */
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
