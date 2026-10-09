package com.opsdesk.ticket.vo;

import lombok.Data;

/**
 * 附件上传响应（API 文档 §8.9）
 *
 * <p>§8.9 的响应范例是 {@code data: { "id": 6, "fileName": "报错截图.png", "fileSize": 204800 }}，
 * 只有 3 个字段 —— 上传后前端需要的就是「拿到 id、知道原文件名、知道大小」，
 * 不需要完整 VO（上传人就是自己、时间就是刚刚）。
 * 所以单独建这个 VO，而不是复用 {@link AttachmentVO}。
 *
 * <p>前端拿这个 {@code id} 去挂图片预览 / 拼下载链接。
 */
@Data
public class AttachmentUploadVO {

    /** 附件 ID */
    private Long id;

    /** 原始文件名（回显，含中文原样返回，由 Jackson 负责编码） */
    private String fileName;

    /** 字节数 */
    private Long fileSize;
}
