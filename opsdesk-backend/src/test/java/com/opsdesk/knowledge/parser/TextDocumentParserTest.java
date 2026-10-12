package com.opsdesk.knowledge.parser;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D12-01：md / txt 解析器的单测（纯单测，不启 Spring、不碰 MinIO）
 *
 * <h2>为什么解析器必须有自己的测试</h2>
 * 本单的上传接口<b>不调用解析器</b>（解析属 D12-05 的异步流水线），
 * 也就是说：解析器写错了，集成测试<b>一条都发现不了</b>。
 * 所以它的正确性只能靠这里保证 —— 尤其是编码相关的边界（这正是"静默出错"的高发区）。
 */
class TextDocumentParserTest {

    private final TextDocumentParser parser = new TextDocumentParser();

    @Test
    @DisplayName("解析 md：原样返回文本（保留 Markdown 标记，切分阶段才处理）")
    void 解析md() {
        String markdown = "# VPN 使用手册\n\n## 连接失败\n\n1. 检查客户端版本\n2. 重启网络\n";

        assertThat(parse(markdown, "md")).isEqualTo(markdown);
    }

    @Test
    @DisplayName("解析 txt：中文内容不乱码")
    void 解析txt() {
        String text = "公司 VPN 无法连接时的排查步骤：先看客户端状态。";

        assertThat(parse(text, "txt")).isEqualTo(text);
    }

    @Test
    @DisplayName("🔴 非 UTF-8（GBK）→ 40001，绝不放行成乱码")
    void 非UTF8直接拒绝() {
        // 用 GBK 编码一段中文，再当作 UTF-8 去解 —— 默认的 new String(bytes, UTF_8) 会静默替换成
        // U+FFFD，乱码照样进库、进向量库，而且没有任何地方会报错
        byte[] gbk = "公司 VPN 无法连接".getBytes(Charset.forName("GBK"));

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(gbk), "txt"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UTF-8")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("🔴 UTF-8 BOM 必须剥掉（否则它会成为正文第一个字符、被切进第一个 chunk）")
    void 剥掉BOM() {
        byte[] withBom = ("\uFEFF" + "VPN 手册").getBytes(StandardCharsets.UTF_8);
        assertThat(withBom[0]).as("前提：确实带 BOM 字节").isEqualTo((byte) 0xEF);

        String text = parser.parse(new ByteArrayInputStream(withBom), "md");

        assertThat(text).isEqualTo("VPN 手册");
        assertThat(text.charAt(0)).as("首字符不能是不可见的 BOM").isNotEqualTo('\uFEFF');
    }

    @Test
    @DisplayName("空内容 → 返回空串（「内容为空」由调用方决定怎么处理，解析器不越权判断）")
    void 空内容返回空串() {
        assertThat(parser.parse(new ByteArrayInputStream(new byte[0]), "txt")).isEmpty();
    }

    @Test
    @DisplayName("stream 为 null → 40001（防御性）")
    void 流为空被拒() {
        assertThatThrownBy(() -> parser.parse(null, "txt"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("为空");
    }

    @Test
    @DisplayName("supports：认 md / txt，不认 docx / pdf（本版按 §0.5 决策 2 方案 B）")
    void 支持范围() {
        assertThat(parser.supports("md")).isTrue();
        assertThat(parser.supports("txt")).isTrue();
        assertThat(parser.supports("docx")).isFalse();
        assertThat(parser.supports("pdf")).isFalse();
        assertThat(parser.supports(null)).isFalse();
    }

    private String parse(String text, String ext) {
        InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        return parser.parse(in, ext);
    }
}
