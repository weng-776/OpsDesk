package com.opsdesk.ticket;

import com.opsdesk.ticket.controller.AttachmentController;
import com.opsdesk.ticket.service.AttachmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载响应头 {@code Content-Disposition} 的编码验收（工单 D3-05 验收 3）
 *
 * <h2>为什么单独测这个私有方法</h2>
 * 工单验收 3 原话：「下载能拿到正确内容，{@code Content-Disposition} 的原始文件名正确（<b>中文不乱码</b>）」。
 * 这段逻辑的失败方式是<b>静默的</b>：
 * <ul>
 *   <li>用 {@code URLEncoder} 编完忘了把 {@code +} 换回 {@code %20} → 文件名里的空格变成加号，
 *       不报错，只是名字错了</li>
 *   <li>ASCII 兜底名没过滤引号 → 头结构被破坏，被容器拒绝或截断</li>
 *   <li>只给 {@code filename=} 不给 {@code filename*=} → 老客户端还行，新客户端中文全乱</li>
 * </ul>
 * 这些都不会让接口「失败」，只会让用户拿到一个乱码文件名。所以必须把断言钉在字符串上。
 *
 * <p>它是 {@code private} —— 用反射调用。**不为了可测就把它改成 public**：
 * 它的唯一合理调用方是 Controller 内部，公开出去等于放宽了封装换测试便利，不值。
 * 反射调用在这里是可接受的（测的是纯字符串函数，无副作用）。
 */
class AttachmentContentDispositionTest {

    /** 通过反射调用 Controller 的私有 buildContentDisposition */
    private String contentDispositionFor(String fileName) throws Exception {
        AttachmentController controller = new AttachmentController(new NoopAttachmentService());
        Method m = AttachmentController.class.getDeclaredMethod("buildContentDisposition", String.class);
        m.setAccessible(true);
        return (String) m.invoke(controller, fileName);
    }

    @Test
    @DisplayName("验收3：中文文件名 —— filename* 用 UTF-8 百分号编码，解码后与原名逐字相同")
    void 中文文件名编码正确() throws Exception {
        String header = contentDispositionFor("报错截图.png");

        assertThat(header).as("必须带 attachment（强制另存，防内联渲染）").startsWith("attachment;");

        // 取 filename*=UTF-8''<encoded> 部分并解码，应还原成原中文名
        int idx = header.indexOf("filename*=UTF-8''");
        assertThat(idx).as("必须提供 RFC 5987 的 filename* 形式").isGreaterThan(-1);

        String encoded = header.substring(idx + "filename*=UTF-8''".length());
        // 头里可能还有别的字段，本实现里 filename* 在最后，直接解码
        String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        assertThat(decoded)
                .as("解码后必须与中文原名逐字相同（这就是「不乱码」的定义）")
                .isEqualTo("报错截图.png");
    }

    @Test
    @DisplayName("验收3：ASCII 兜底名真的只含 ASCII（中文替换为 _），不会破坏头结构")
    void ASCII兜底名安全() throws Exception {
        String header = contentDispositionFor("报错截图.png");

        // 抽出 filename="..." 部分
        int start = header.indexOf("filename=\"");
        assertThat(start).as("必须同时提供 ASCII 兜底的 filename=").isGreaterThan(-1);
        String ascii = header.substring(start + "filename=\"".length(), header.indexOf('"', start + 10));

        assertThat(ascii)
                .as("兜底名不得含非 ASCII（否则头会是字节流乱码）")
                .matches("[\\x20-\\x7E]*")
                .as("中文部分替换成 _").isEqualTo("____.png");
        assertThat(ascii).as("不得含引号（会截断头）").doesNotContain("\"");
        assertThat(ascii).as("不得含反斜杠").doesNotContain("\\");
    }

    @Test
    @DisplayName("空格必须编成 %20（不是 +）—— URLEncoder 的经典坑")
    void 空格编码为百分号二十() throws Exception {
        String header = contentDispositionFor("my report.pdf");

        int idx = header.indexOf("filename*=UTF-8''");
        String encoded = header.substring(idx + "filename*=UTF-8''".length());

        assertThat(encoded)
                .as("filename* 里的空格必须是 %20；用 + 会被解成加号字符")
                .isEqualTo("my%20report.pdf")
                .doesNotContain("+");
    }

    @Test
    @DisplayName("文件名含引号 / 分隔符 → 兜底名被清洗，不产生 header injection")
    void 恶意文件名不破坏头结构() throws Exception {
        // 经典 header injection payload
        String header = contentDispositionFor("a\"b\\c\r\nX-Injected: 1.png");

        // 头里不能出现真正的 CR/LF（否则是响应头注入）
        assertThat(header).as("不得含 CR/LF —— 那是响应头注入").doesNotContain("\r").doesNotContain("\n");

        int start = header.indexOf("filename=\"");
        String ascii = header.substring(start + "filename=\"".length(), header.indexOf('"', start + 10));
        assertThat(ascii).as("引号与反斜杠被替换").doesNotContain("\"").doesNotContain("\\");
    }

    @Test
    @DisplayName("文件名缺失 → 回落 attachment，不产生 filename=\"\"")
    void 空文件名回落() throws Exception {
        assertThat(contentDispositionFor(null)).contains("filename=\"attachment\"");
        assertThat(contentDispositionFor("   ")).contains("filename=\"attachment\"");
    }

    /** 本测试只调私有纯函数，不需要真的 Service —— 用最小空实现满足构造器 */
    private static final class NoopAttachmentService implements AttachmentService {
        @Override
        public com.opsdesk.ticket.vo.AttachmentUploadVO upload(Long ticketId, org.springframework.web.multipart.MultipartFile file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<com.opsdesk.ticket.vo.AttachmentVO> list(Long ticketId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DownloadPayload download(Long attachmentId) {
            throw new UnsupportedOperationException();
        }
    }
}
