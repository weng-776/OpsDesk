package com.opsdesk.knowledge.parser;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * md / txt 直读解析器（工单 D12-01，规格基线 §12.2「{@code md} / {@code txt} → 直读」）
 *
 * <h2>🔴 最重要的一个决定：非 UTF-8 的文件<b>直接拒绝</b>，而不是"尽力而为"</h2>
 * 默认写法 {@code new String(bytes, UTF_8)} 遇到非法字节会<b>静默替换成 {@code U+FFFD}</b> ——
 * 结果是：GBK 编码的 txt 会变成一屏乱码，然后<b>乱码照样被切分、被 Embedding、进向量库</b>。
 * 这种数据一旦进去，检索会持续返回无意义的结果，而且<b>没有任何地方会报错</b>，
 * 排查时只会觉得"RAG 效果怎么这么差"。
 *
 * <p>所以在源头就卡住：用 {@link CodingErrorAction#REPORT} 让非法字节<b>抛出异常</b>，
 * 转成 40001「文件不是 UTF-8 编码，请转码后再上传」——
 * 用户知道该做什么，库里的数据也永远是干净的。
 *
 * <h2>⚠️ BOM 必须剥掉</h2>
 * 用记事本 / VS Code 存的 UTF-8 文件常带 BOM（{@code EF BB BF} = {@code U+FEFF}）。
 * 不剥的话它就成了正文第一个字符，会被切进第一个 chunk —— 检索时带着一个看不见的字符，
 * 命中率莫名下降，而且肉眼完全看不出来。
 */
@Component
public class TextDocumentParser implements KnowledgeDocumentParser {

    /** 本实现负责的扩展名（与 {@link KnowledgeDocumentTypes} 的白名单一致） */
    private static final Set<String> SUPPORTED = Set.of("md", "txt");

    /** UTF-8 BOM 字符（解码之后的形式） */
    private static final char BOM = '\uFEFF';

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED.contains(extension);
    }

    @Override
    public String parse(InputStream in, String extension) {
        if (in == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文档内容为空");
        }
        return stripBom(decodeStrictUtf8(readAll(in)));
    }

    /**
     * 读取全部字节。
     *
     * <p>不做流式处理：文件上限是 20MB（上传层已校验），一次读进内存完全可控；
     * 而按行读反而会在「一行超长」（某些压缩过的 md）时出问题。
     */
    private byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        }
        catch (IOException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "读取文档内容失败", ex);
        }
    }

    /**
     * 严格 UTF-8 解码：遇到非法字节就抛，不做静默替换（见类注释）。
     */
    private String decodeStrictUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        }
        catch (CharacterCodingException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "文件不是 UTF-8 编码，请转成 UTF-8 后再上传");
        }
    }

    private String stripBom(String text) {
        return text.startsWith(String.valueOf(BOM)) ? text.substring(1) : text;
    }
}
