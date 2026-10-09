package com.opsdesk.ticket.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.ticket.service.AttachmentService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 附件下载接口（API 文档 §8.11）
 *
 * <h2>⚠️ 为什么单独一个 Controller，而不是加进 {@link TicketController}</h2>
 * §8.11 的路径是 {@code GET /api/attachments/{id}/download}，
 * 而 {@code TicketController} 的类级 {@code @RequestMapping} 是 {@code /api/tickets}。
 * 路径前缀不同 → 必须分开。硬塞进 TicketController 只能给方法上写全路径
 * （{@code @GetMapping("/api/attachments/{id}/download")}），那会与类级前缀拼成
 * {@code /api/tickets/api/attachments/...} —— 错得很隐蔽。
 *
 * <h2>为什么返回 {@code void} + 直接写 {@link HttpServletResponse}，而不是返回 {@code ResponseEntity}</h2>
 * 返回 {@code ResponseEntity<Resource>} 也可以，但：
 * <ul>
 *   <li>它要求先把内容读进内存或包成 {@code ByteArrayResource}（10MB × 并发 = 危险）</li>
 *   <li>或者在 Controller 里拼 {@code Content-Disposition} 字符串 —— 中文编码正是最容易错的地方，
 *       用一个明确的 {@link #buildContentDisposition} 方法把它钉死更好审（本文件是红区）</li>
 * </ul>
 * 直接写响应流：MinIO 的流 → 响应流，<b>全程流式，不整块进内存</b>。
 *
 * <h2>⚠️ 权限与数据范围</h2>
 * 权限码 {@code ticket:detail}（能看详情才能下它的附件）。
 * 数据范围校验在 Service 层（{@code AttachmentServiceImpl#download}）—— Controller 不参与，
 * 与 D3-02/03/04 的分层一致。
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 下载附件（§8.11）—— 返回二进制流。
     *
     * <p>响应头要点：
     * <ul>
     *   <li>{@code Content-Disposition: attachment} —— 强制「另存为」，
     *       不让浏览器按 contentType 内联渲染（上传 SVG / HTML 时这是 XSS 的常见入口；
     *       本模块白名单虽已排除 svg/html，但「一律 attachment」是更稳的姿势）</li>
     *   <li>文件名用 <b>RFC 5987 {@code filename*}</b> 携带 UTF-8 原始名，
     *       并<b>同时</b>给一个 ASCII 回落的 {@code filename=} —— 见 {@link #buildContentDisposition}</li>
     * </ul>
     *
     * <p>⚠️ 这里<b>不 try/catch</b>：异常交给 {@code GlobalExceptionHandler}。
     * 但要注意流已经开始写之后抛异常，响应头已发出、无法再改成 JSON 错误体 ——
     * 所以「所有校验都必须在写第一字节之前完成」，这一点由 Service 保证
     * （{@code download()} 返回时校验已全部通过）。
     */
    @RequirePermission(PermissionCodes.TICKET_DETAIL)
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) throws Exception {
        AttachmentService.DownloadPayload payload = attachmentService.download(id);

        // —— 以下所有写操作都发生在校验之后（Service 已保证），顺序不能挪 ——
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                buildContentDisposition(payload.fileName()));

        if (payload.contentType() != null && !payload.contentType().isBlank()) {
            response.setContentType(payload.contentType());
        }
        else {
            // 没有 MIME 时用二进制流，让浏览器直接下载而不是猜类型渲染
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }
        if (payload.size() != null && payload.size() >= 0) {
            response.setContentLengthLong(payload.size());
        }

        try (InputStream in = payload.stream();
             OutputStream out = response.getOutputStream()) {
            StreamUtils.copy(in, out);
            out.flush();
        }
        catch (Exception ex) {
            // 客户端中途断开（下载到一半关页面）会走到这里 —— 不算业务错误，记 warn 即可。
            // 不重新抛出：响应头已发出，抛出去 GlobalExceptionHandler 也写不了 JSON 了，
            // 只会多打一条误导性的 error 堆栈。
            log.warn("[附件下载] 传输中断 attachmentId={} cause={}", id, ex.getMessage());
        }
    }

    /**
     * 构造 {@code Content-Disposition}，兼顾中文文件名与老客户端兼容。
     *
     * <h2>为什么是两个 filename（这是唯一正确的姿势）</h2>
     * HTTP 头是<b>字节流</b>，规范上只允许 ISO-8859-1 / ASCII。中文文件名直接写进去会乱码。
     * RFC 5987 给出了 {@code filename*=UTF-8''<percent-encoded>} 这个扩展语法，
     * 但<b>老客户端不认它</b>。所以标准做法是两个都给：
     * <pre>
     * Content-Disposition: attachment; filename="report.png"; filename*=UTF-8''%E6%8A%A5%E9%94%99%E6%88%AA%E5%9B%BE.png
     * </pre>
     * 新客户端优先取 {@code filename*}（拿到正确中文），老客户端退化取 {@code filename}
     * （拿到 ASCII 兜底名）。两个都写，谁也不出错。
     *
     * <h2>⚠️ 两个容易踩的坑</h2>
     * <ol>
     *   <li><b>ASCII 兜底名必须真的只含 ASCII</b>，且不能含 {@code "} / {@code \} / 控制字符
     *       —— 否则会破坏头结构（header injection）。中文一律替换成 {@code _}。</li>
     *   <li><b>百分号编码要用 UTF-8 字节</b>，且 {@code URLEncoder} 把空格编成 {@code +}
     *       —— 在 {@code filename*} 里空格必须是 {@code %20}，所以编完要把 {@code +} 换回 {@code %20}。
     *       这是本方法最容易漏的一步。</li>
     * </ol>
     */
    private String buildContentDisposition(String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? "attachment" : fileName;

        // ① RFC 5987：UTF-8 + 百分号编码
        String encoded = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)
                .replace("+", "%20");   // ⚠️ URLEncoder 把空格编成 +，这里必须换回 %20

        // ② ASCII 兜底：非 ASCII 与控制字符一律换成 _
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_")   // 非可打印 ASCII
                .replace("\\", "_")
                .replace("\"", "_");

        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
