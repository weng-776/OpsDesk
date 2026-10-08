package com.opsdesk.common;

import lombok.Data;

import java.util.List;

/**
 * 分页响应体（规格基线 §20.1）
 *
 * <pre>
 * { "code": 0, "message": "success",
 *   "data": { "list": [], "total": 0, "page": 1, "size": 10 } }
 * </pre>
 *
 * <p>作为 {@link Result} 的 data 使用：{@code Result<PageResult<TicketVO>>}。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> list;

    /** 总记录数 */
    private long total;

    /** 当前页码，从 1 开始 */
    private long page;

    /** 每页条数 */
    private long size;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, long page, long size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        return new PageResult<>(list, total, page, size);
    }

    /** 空结果，用于「查不到数据但请求合法」的场景 */
    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }
}
