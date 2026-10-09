package com.opsdesk.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 添加评论入参（API 文档 §8.8）。
 */
@Data
public class CommentCreateDTO {

    /** 内容，≤2000（§8.8） */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 2000, message = "评论内容不能超过 2000 字")
    private String content;

    /**
     * 是否仅 IT 内部可见（§8.8：默认 {@code false}，<b>仅 AGENT / ADMIN 可设为 true</b>）。
     *
     * <p>⚠️ 用包装类型 {@code Boolean} 而不是 {@code boolean}：要区分「没传」与「传了 false」。
     * 缺省时归一成 {@code false}（§8.8 的默认值）。
     * <p>EMPLOYEE 传 {@code true} 会被<b>静默降级</b>为 false（已与用户确认）——
     * 见 service 里的说明。
     */
    private Boolean internal;
}
