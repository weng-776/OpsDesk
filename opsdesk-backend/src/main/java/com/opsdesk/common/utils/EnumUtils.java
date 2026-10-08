package com.opsdesk.common.utils;

import com.opsdesk.common.enums.CodeEnum;

import java.util.Optional;

/**
 * 枚举工具（规格基线 §3 白名单校验）
 *
 * <p>主要用途是**校验 AI 结构化输出的枚举字段**（§10.3 / §11.3）：
 * AI 返回的任何枚举值都必须落在 §3 白名单内，否则走兜底，不能直接落库。
 *
 * <pre>{@code
 * TicketCategory category = EnumUtils.fromCode(TicketCategory.class, aiOutput.getCategory())
 *         .orElse(TicketCategory.OTHER);       // §10.3 兜底
 * }</pre>
 */
public final class EnumUtils {

    private EnumUtils() {
    }

    /**
     * 按 code（枚举常量名）查找，大小写不敏感；找不到返回 {@link Optional#empty()}。
     */
    public static <E extends Enum<E> & CodeEnum> Optional<E> fromCode(Class<E> type, String code) {
        if (type == null || code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim();
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(normalized)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    /** code 是否落在白名单内 */
    public static <E extends Enum<E> & CodeEnum> boolean isValid(Class<E> type, String code) {
        return fromCode(type, code).isPresent();
    }

    /**
     * 按 code 查找，找不到时返回 {@code fallback}（AI 输出兜底场景，§10.3）。
     */
    public static <E extends Enum<E> & CodeEnum> E fromCodeOrDefault(Class<E> type, String code, E fallback) {
        return fromCode(type, code).orElse(fallback);
    }
}
