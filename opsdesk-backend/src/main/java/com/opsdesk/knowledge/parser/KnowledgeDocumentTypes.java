package com.opsdesk.knowledge.parser;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识文档的类型白名单与校验（工单 D12-01，规格基线 §12.2 + §0.5 决策 2）
 *
 * <h2>🔴 为什么不复用 {@code common/utils/FileTypeValidator}</h2>
 * 已核实：那个类是<b>工单附件专用</b>的 —— 它的白名单是
 * {@code jpg / png / gif / pdf / txt / log / docx / xlsx}（允许图片、<b>没有 md</b>）。
 * 知识库需要的是完全不同的集合（{@code md / txt}），硬塞进那个类会变成
 * 「两个场景互相牵制」：给知识库加格式会顺带放宽附件的白名单。
 *
 * <p>所以这里独立一份。两者共享的思想只有一条：<b>扩展名 + MIME 双白名单</b>，
 * 且真正的闸门是<b>扩展名</b>（MIME 由客户端提供，本来就不可信，只当第二道粗筛）。
 */
public final class KnowledgeDocumentTypes {

    /** 本版支持（§0.5 决策 2 方案 B） */
    private static final Set<String> SUPPORTED = Set.of("md", "txt");

    /**
     * 扩展名 → 允许的 MIME。
     *
     * <p>⚠️ {@code md} 的 MIME <b>各家报得极不一致</b>：{@code text/markdown}、
     * {@code text/x-markdown}、{@code text/plain}（浏览器按后缀猜）、
     * {@code application/octet-stream}（curl 之类的默认值）—— 都得放行，
     * 否则「用 curl 上传 md」这种完全正常的用法会被自己的白名单挡住。
     */
    private static final Map<String, Set<String>> WHITELIST = buildWhitelist();

    /**
     * §12.2 表格里列了、但按 §0.5 决策 2（B）本版<b>未开放</b>的格式。
     *
     * <p>单独列出来只为一句有用的报错：「当前版本暂不支持 docx，请先转成 md 或 txt」——
     * 比笼统的「文件类型不支持」对用户有用得多（他知道下一步该干什么）。
     */
    private static final Set<String> KNOWN_UNSUPPORTED = Set.of("docx", "doc", "pdf", "xlsx", "xls");

    private KnowledgeDocumentTypes() {
    }

    private static Map<String, Set<String>> buildWhitelist() {
        // LinkedHashMap：异常文案里列白名单时顺序稳定（与附件那边同一手法）
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put("md", Set.of("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream"));
        map.put("txt", Set.of("text/plain", "application/octet-stream"));
        // 不用 Map.copyOf —— 它返回的 MapN 迭代顺序按哈希桶散列，会丢掉插入顺序
        return Collections.unmodifiableMap(map);
    }

    /** 校验扩展名 + MIME，返回<b>归一化扩展名</b>（小写、不含点） */
    public static String validate(String fileName, String contentType) {
        if (fileName == null || fileName.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件名不能为空");
        }

        String ext = extensionOf(fileName);
        if (ext == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "文件缺少扩展名，仅支持 " + supportedText());
        }

        if (KNOWN_UNSUPPORTED.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "当前版本暂不支持 " + ext + " 格式，请先转成 " + supportedText() + " 后再上传");
        }

        Set<String> allowedMimes = WHITELIST.get(ext);
        if (allowedMimes == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "不支持的文件类型：" + ext + "，仅支持 " + supportedText());
        }

        // MIME 由客户端提供，不可信 —— 但作为第二道粗筛仍然有价值（能挡住"改后缀"的低级绕过）
        if (contentType == null || contentType.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "无法识别文件类型（缺少 Content-Type），仅支持 " + supportedText());
        }
        String mime = contentType.trim().toLowerCase(Locale.ROOT);
        // 有些客户端会带 charset，例如 "text/plain; charset=UTF-8"
        int semicolon = mime.indexOf(';');
        if (semicolon > 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        if (!allowedMimes.contains(mime)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "文件类型不匹配：" + ext + " 不应是 " + mime + "，仅支持 " + supportedText());
        }

        return ext;
    }

    public static boolean isSupported(String extension) {
        return extension != null && SUPPORTED.contains(extension.toLowerCase(Locale.ROOT));
    }

    /** 白名单文案，用于异常提示，如 {@code md / txt} */
    public static String supportedText() {
        return String.join(" / ", WHITELIST.keySet());
    }

    /**
     * 从文件名取扩展名：小写、<b>只保留字母数字</b>。
     *
     * <p>归一化规则本身就是一道防线：任何含 {@code /}、{@code \}、{@code .}、空格、
     * 控制字符的"扩展名"都返回 {@code null}（被拒）。
     * 这样 {@code a.md/../x}、{@code a.md%00.png} 这类花招连扩展名这一关都过不去 ——
     * 与 D3-05 的 {@code FileTypeValidator#extensionOf} 同一手法。
     */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.isEmpty() || ext.length() > 10) {
            return null;
        }
        for (int i = 0; i < ext.length(); i++) {
            if (!Character.isLetterOrDigit(ext.charAt(i))) {
                return null;
            }
        }
        return ext;
    }

    /** 已知但未开放的格式（供测试与文档引用） */
    public static Set<String> knownUnsupported() {
        return KNOWN_UNSUPPORTED.stream().sorted().collect(Collectors.toUnmodifiableSet());
    }
}
