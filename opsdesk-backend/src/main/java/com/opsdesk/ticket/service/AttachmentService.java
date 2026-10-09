package com.opsdesk.ticket.service;

import com.opsdesk.ticket.vo.AttachmentUploadVO;
import com.opsdesk.ticket.vo.AttachmentVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 工单附件业务接口（工单 D3-05）
 *
 * <p>规格依据：API 文档 §8.9（上传）/ §8.10（列表）/ §8.11（下载）；
 * 规格基线 §23.1（类型白名单 + 10MB + 文件名 UUID 化）。
 *
 * <h2>⚠️ 为什么不叫 {@code TicketAttachmentService}</h2>
 * 那个名字已经被 {@code tools/gen_entities.py} 生成的骨架占用了
 * （{@code interface TicketAttachmentService extends IService<TicketAttachment>}，
 * 只负责 {@code ticket_attachment} 表的基础 CRUD）。本接口是<b>业务接口</b>，
 * 与 D3-04 的 {@code TicketCommentBizService} 是同一个命名约定：
 * <b>骨架服务（表 CRUD）与业务服务（用例）分开，业务方法不往生成的骨架里塞</b>
 * —— 否则重新执行 gen_entities.py 会把业务方法冲掉。
 */
public interface AttachmentService {

    /**
     * 上传附件（§8.9）。
     *
     * <p>完整校验链（顺序固定，不要调换）：
     * <ol>
     *   <li>调用者对工单有可见性 —— <b>不存在 → 40400，不可见 → 40301</b>（顺序见 Service 实现）</li>
     *   <li>文件非空</li>
     *   <li>大小 ≤ 10MB（业务层再校验一次，不依赖 yml 的 multipart 上限）</li>
     *   <li>扩展名 + MIME 双重白名单校验</li>
     *   <li>存储对象名 = {@code ticket/{ticketId}/{UUID}.{ext}}，原始文件名只落 DB</li>
     * </ol>
     *
     * @param ticketId 工单 id
     * @param file     multipart 文件
     * @return §8.9 的响应体 {@code {id, fileName, fileSize}}
     */
    AttachmentUploadVO upload(Long ticketId, MultipartFile file);

    /**
     * 附件列表（§8.10）。
     *
     * <p>同样<b>先做数据范围校验</b>（不可见 → 40301，不存在 → 40400），
     * 否则「知道工单 id 就能列出它的附件」。
     *
     * @return §16.11 的 {@link AttachmentVO} 列表（按上传时间升序）
     */
    List<AttachmentVO> list(Long ticketId);

    /**
     * 下载附件（§8.11）—— 返回下载所需的「流 + 元信息」，由 Controller 拼响应头。
     *
     * <p>校验链：附件 id 不存在 → 40400；其所属工单不可见 → 40301；
     * MinIO 里对象缺失 → 40400（数据不一致）。
     *
     * <p>⚠️ 返回的是<b>未关闭的流</b>，调用方（Controller）必须负责关闭，
     * 否则连接泄漏。用 try-with-resources 或 {@code StreamingResponseBody} 包住。
     */
    DownloadPayload download(Long attachmentId);

    /**
     * 下载载荷：二进制流 + 响应头需要的元信息。
     *
     * <p>为什么不直接返回 {@code InputStream}：Controller 需要原始文件名来拼
     * {@code Content-Disposition}（且要做 URL 编码），也要 {@code contentType}。
     * 打包成一个 record 比返回 {@code Map} 或让 Controller 再查一次库清楚得多。
     *
     * @param fileName    原始文件名（<b>仅用于响应头展示</b>，注意 URL 编码）
     * @param contentType MIME，可为 null
     * @param size        字节数，可为 null（用于 Content-Length）
     * @param stream      对象流，<b>调用方负责关闭</b>
     */
    record DownloadPayload(String fileName, String contentType, Long size, InputStream stream) {
    }
}
