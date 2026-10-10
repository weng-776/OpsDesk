package com.opsdesk.sla.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改 SLA 策略入参（API 文档 §9.3）
 *
 * <p>请求体：{@code { "responseMinutes": 30, "resolutionMinutes": 240 }}
 *
 * <p>⚠️ <b>只有这两个字段</b>：§9.3 的约束表就这两行。
 * 特别是 <b>不能改 {@code priority}</b> —— 要换优先级就另建一条策略，
 * 否则会与「同一 priority 只有一条 ACTIVE」的版本化语义纠缠不清。
 *
 * <p>⚠️ 跨字段约束 {@code resolutionMinutes >= responseMinutes} <b>不在这里</b>：
 * Bean Validation 表达跨字段要靠类级 {@code @AssertTrue}，报错落到 {@code data} 里
 * 是一条没有字段名的错误，前端不好定位。改在 service 里判，抛 {@code 40001} 带明确文案。
 * 这里只做单字段的「必须为正」。
 */
@Data
public class SlaPolicyUpdateDTO {

    /** 响应时限（分钟），必填且 > 0 */
    @NotNull(message = "响应时限不能为空")
    @Min(value = 1, message = "响应时限必须大于 0")
    private Integer responseMinutes;

    /** 解决时限（分钟），必填且 > 0；且必须 ≥ 响应时限（service 层校验） */
    @NotNull(message = "解决时限不能为空")
    @Min(value = 1, message = "解决时限必须大于 0")
    private Integer resolutionMinutes;
}
