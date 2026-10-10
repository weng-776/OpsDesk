package com.opsdesk.common.utils;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 附件类型白名单校验（工单 D3-05，规格基线 §23.1）
 *
 * <p>§23.1 逐字要求：<b>「附件：类型白名单 + 大小上限 10MB + 文件名 UUID 化（防路径穿越）」</b>；
 * API 文档 §8.9 给出的白名单是 {@code jpg/png/gif/pdf/txt/log/docx/xlsx}。
 *
 * <h2>为什么扩展名和 MIME 都要判（双重判断）</h2>
 * 单看任何一个都能被绕过，两者<b>互补</b>：
 * <ul>
 *   <li><b>只看扩展名</b>：攻击者把 {@code evil.exe} 改名成 {@code evil.jpg} 就过了。
 *       虽然本模块把文件当二进制流存取、不落地执行，但「用户上传的可执行文件挂在工单里被同事下载」
 *       本身就是钓鱼载体 —— 所以不能只看名字。</li>
 *   <li><b>只看 MIME</b>：MIME 是客户端 {@code Content-Type} 自报的，随手可伪造。
 *       curl 可以把 {@code Content-Type} 写成 {@code image/png} 而 body 是 ELF。</li>
 * </ul>
 * 所以要求<b>两者同时命中同一个白名单条目</b>：扩展名对、且 MIME 属于该扩展名允许的 MIME 集合。
 *
 * <h2>⚠️ 本类的边界（不要误以为它做了内容嗅探）</h2>
 * 它<b>不做魔数（magic number）嗅探</b>，也不解析文件真实内容 —— 那需要引入 Tika 之类的依赖
 * （SOP §6 禁止自主新增依赖）。本类做的是「声明一致性校验」：<b>扩展名与 MIME 必须自洽且都在白名单内</b>。
 * 真正的隔离靠「存 MinIO 对象、UUID 命名、下载一律 {@code Content-Disposition: attachment} 强制另存」
 * —— 即<b>不让浏览器按声明类型渲染</b>。这一点在 {@code AttachmentServiceImpl} 里落实。
 */
public final class FileTypeValidator {

    /** §23.1 / §8.9 的类型白名单：扩展名 → 允许的 MIME 集合 */
    private static final Map<String, Set<String>> WHITELIST = buildWhitelist();

    /** 无扩展名 / 扩展名为空时的兜底 MIME（此时会直接判不合法，仅为异常文案服务） */
    private static final String UNKNOWN = "application/octet-stream";

    private FileTypeValidator() {
    }

    private static Map<String, Set<String>> buildWhitelist() {
        // LinkedHashMap：异常文案里列白名单时顺序稳定，便于人读（"jpg/png/gif/..." 与 §8.9 一致）
        Map<String, Set<String>> map = new LinkedHashMap<>();

        // 图片：客户端可能报多种 MIME（尤其 jpg ↔ jpeg、以及某些库只给 image/jpg）
        map.put("jpg", Set.of("image/jpeg", "image/jpg"));
        map.put("png", Set.of("image/png"));
        map.put("gif", Set.of("image/gif"));

        // 文档：pdf 还有个小众别名 x-pdf，某些老客户端会报
        map.put("pdf", Set.of("application/pdf", "application/x-pdf"));

        // 纯文本：运维贴错误日志的场景（§PRD 里「上传日志排查」），MIME 各家报得不一致
        map.put("txt", Set.of("text/plain", "application/octet-stream"));
        map.put("log", Set.of("text/plain", "application/octet-stream", "text/x-log"));

        // Office（OOXML：本质是 zip，所以部分客户端会报 x-zip / zip）
        map.put("docx", Set.of(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/zip", "application/x-zip-compressed", "application/octet-stream"));
        map.put("xlsx", Set.of(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/zip", "application/x-zip-compressed", "application/octet-stream"));

        // 注意：不能用 Map.copyOf(map) —— 它返回 ImmutableCollections.MapN，
        // 迭代顺序按哈希桶散列，会丢掉 LinkedHashMap 的插入顺序（whitelistText() 就乱了）。
        // Collections.unmodifiableMap 保留原 Map 的迭代顺序。
        return Collections.unmodifiableMap(map);
    }

    /**
     * 校验扩展名 + MIME 是否都在白名单内且自洽。
     *
     * @param fileName    原始文件名（只用来取扩展名；<b>不参与任何路径拼接</b>）
     * @param contentType 客户端声明的 MIME，可为空（为空时按具体规则处理，见下）
     * @return 归一化后的扩展名（小写、不含点），供拼存储路径用
     * @throws BizException {@code 40001} —— 缺文件名 / 无扩展名 / 扩展名不在白名单 / MIME 与扩展名不匹配
     */
    public static String validate(String fileName, String contentType) {
        if (fileName == null || fileName.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件名不能为空");
        }

        String ext = extensionOf(fileName);
        if (ext == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "文件缺少扩展名，不支持的附件类型（白名单：" + whitelistText() + "）");
        }
        if (!WHITELIST.containsKey(ext)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "不支持的附件类型：." + ext + "（白名单：" + whitelistText() + "）");
        }

        // MIME 为空 → 放行（有些客户端确实不发 Content-Type）。
        // 此时扩展名已经是白名单内的，且文件不会被执行（见类注释「本类的边界」），
        // 所以这里不必再拒 —— 拒了反而伤正常用户。
        if (contentType == null || contentType.isBlank()) {
            return ext;
        }

        String mime = normalizeMime(contentType);
        if (!WHITELIST.get(ext).contains(mime)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "文件类型与内容不符：扩展名 ." + ext + " 不允许 MIME " + mime);
        }
        return ext;
    }

    /**
     * 取小写扩展名（不含点）。
     *
     * <p>⚠️ 这里刻意<b>不用</b> {@code new File(name).getName()} —— 本方法在 Windows 上
     * 会把 {@code ..\\evil.jpg} 里的反斜杠当路径分隔符处理，行为随 OS 变化。
     * 我们要的是「最后一个点之后的部分」，纯字符串操作，与 OS 无关、可预测。
     *
     * @return 小写扩展名；无点、点开头、点结尾都返回 {@code null}
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        // dot < 0        → 无扩展名
        // dot == len-1   → "xxx." 尾点，空扩展名
        // dot == 0       → ".gitignore" 这种点开头，视为无扩展名（不是 .gitignore 扩展）
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 扩展名里混入空白 / 路径分隔符 → 视为非法，不认（防 "jpg/" 这种)
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                return null;
            }
        }
        return ext;
    }

    /** 白名单里是否含该扩展名（不抛异常，给测试与调用方探测用） */
    public static boolean supports(String extension) {
        return extension != null && WHITELIST.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    /** 供异常文案使用：{@code jpg/png/gif/pdf/txt/log/docx/xlsx}（与 §8.9 顺序一致） */
    public static String whitelistText() {
        return String.join("/", WHITELIST.keySet());
    }

    /**
     * 归一化 MIME：去掉参数（{@code image/jpeg; charset=x} → {@code image/jpeg}）、转小写、去空白。
     *
     * <p>不做这一步的话，客户端多发一个 {@code ;charset=` 就会被判成不匹配 —— 那是误伤。
     */
    private static String normalizeMime(String contentType) {
        String mime = contentType;
        int semi = mime.indexOf(';');
        if (semi >= 0) {
            mime = mime.substring(0, semi);
        }
        mime = mime.trim().toLowerCase(Locale.ROOT);
        return mime.isEmpty() ? UNKNOWN : mime;
    }
}
