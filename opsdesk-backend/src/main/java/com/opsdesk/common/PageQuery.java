package com.opsdesk.common;

import lombok.Data;

/**
 * 分页请求基类（规格基线 §20.1）
 *
 * <p>约定：{@code ?page=1&size=10}，<b>size 最大 50</b>。
 * 各模块的查询 DTO 继承本类即可，越界值由 {@link #normalize()} 统一收敛，
 * 避免每个 Controller 各写一遍校验。
 *
 * <pre>{@code
 * @Data
 * @EqualsAndHashCode(callSuper = true)
 * public class TicketQuery extends PageQuery {
 *     private TicketStatus status;
 * }
 * }</pre>
 */
@Data
public class PageQuery {

    /** 默认页码 */
    public static final long DEFAULT_PAGE = 1L;

    /** 默认每页条数 */
    public static final long DEFAULT_SIZE = 10L;

    /** 每页条数上限（§20.1 硬约束） */
    public static final long MAX_SIZE = 50L;

    /** 页码，从 1 开始；缺省 1 */
    private Long page;

    /** 每页条数，缺省 10，最大 50 */
    private Long size;

    /** 收敛到合法区间：page ≥ 1，1 ≤ size ≤ 50 */
    public void normalize() {
        if (this.page == null || this.page < DEFAULT_PAGE) {
            this.page = DEFAULT_PAGE;
        }
        if (this.size == null || this.size < 1) {
            this.size = DEFAULT_SIZE;
        }
        if (this.size > MAX_SIZE) {
            this.size = MAX_SIZE;
        }
    }

    /** 归一化后的页码（不改动原对象） */
    public long pageOrDefault() {
        return (page == null || page < DEFAULT_PAGE) ? DEFAULT_PAGE : page;
    }

    /** 归一化后的每页条数（不改动原对象） */
    public long sizeOrDefault() {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
