package com.opsdesk.ticket.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件展示对象（API 文档 §16.11）
 *
 * <h2>字段恰好 6 个，逐项对齐 §16.11</h2>
 * <pre>
 * id           附件 ID
 * fileName     原始文件名
 * fileSize     字节数
 * contentType  MIME
 * uploaderName 上传人姓名
 * createdAt    上传时间
 * </pre>
 *
 * <p>⚠️ <b>刻意不返回 {@code filePath}</b> —— 那是对象存储的内部路径（含 bucket 结构）。
 * 暴露它等于告诉前端「文件放在哪」，虽然本模块已 UUID 化使路径不可猜，
 * 但对外只给 id、下载走 {@code GET /api/attachments/{id}/download} 是更干净的边界
 * （§16.11 也确实没有这个字段）。
 */
@Data
public class AttachmentVO {

    /** 附件 ID（下载接口的入参） */
    private Long id;

    /** 原始文件名（仅展示；存储路径里是 UUID，两者不共用） */
    private String fileName;

    /** 字节数 */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    /** 上传人姓名（昵称优先，无昵称回落用户名 —— 与 D3-02 / D3-04 口径一致） */
    private String uploaderName;

    /** 上传时间 */
    private LocalDateTime createdAt;
}
