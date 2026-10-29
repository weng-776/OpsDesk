package com.opsdesk.ticket.service;

import com.opsdesk.ticket.dto.TicketQuery;

import java.io.OutputStream;

/**
 * 工单导出（补单 · 工单导出，API 文档 §8.6）
 *
 * <p>把「§8.3 的筛选条件 + 当前用户的数据范围」命中的工单导出成 xlsx。
 *
 * <h2>为什么返回 {@link OutputStream} 而不是 {@code byte[]}</h2>
 * 由 Service 直接往响应流里写，避免在内存里再拷一份完整文件（10000 行约几 MB）。
 * Controller 只负责设响应头，不参与 Excel 细节 —— 两层职责清晰，
 * 也让本类可以在单测里传 {@code ByteArrayOutputStream} 直接断言内容。
 *
 * <h2>⚠️ 只读</h2>
 * 不改任何工单数据、不写审计以外的东西（本单干脆什么都不写）。
 */
public interface TicketExportService {

    /**
     * 导出工单为 xlsx 并写入 {@code out}。
     *
     * <p>不含表头行 —— 只统计数据行数，便于调用方打日志 / 单测断言。
     *
     * @param query 复用 §8.3 的筛选参数；{@code page}/{@code size} <b>被忽略</b>（导出不分页）
     * @param out   响应输出流（由 Controller 传入 {@code response.getOutputStream()}）
     * @return 实际写出的数据行数（已按 10000 上限截断）
     * @throws com.opsdesk.common.BizException {@code 40001} 排序参数不在白名单；
     *                                         写入流失败 → {@code 50000}
     */
    int export(TicketQuery query, OutputStream out);
}
