package com.opsdesk.ticket.dto;

import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建工单入参（API 文档 §8.1）
 *
 * <p>字段与 §8.1 的表格逐项对齐。{@code priority} 可空 → 缺省 {@code P3}（§6.1）。
 *
 * <p>⚠️ 三个枚举字段直接用 {@code com.opsdesk.common.enums} 里的枚举类型（§3.2 / §3.3 / §3.4），
 * 不写 {@code String} 再手工转换 —— 这样「枚举白名单校验」由 Jackson 反序列化天然完成：
 * 传 {@code type=FOO} 会在解析请求体时就失败（→ 40001），根本进不了业务代码。
 */
@Data
public class TicketCreateDTO {

    /** 标题，≤128 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题长度不能超过 128")
    private String title;

    /** 描述，≤4000 */
    @NotBlank(message = "描述不能为空")
    @Size(max = 4000, message = "描述长度不能超过 4000")
    private String description;

    /** 类型：INCIDENT / SERVICE_REQUEST（§3.2） */
    @NotNull(message = "工单类型不能为空")
    private TicketType type;

    /** 分类：见 §3.3 */
    @NotNull(message = "工单分类不能为空")
    private TicketCategory category;

    /** 优先级：P1 / P2 / P3；不传则默认 P3（§6.1） */
    private TicketPriority priority;
}
