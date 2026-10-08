package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * AI 分析结果状态（规格基线 §3.12）
 *
 * <p>写 {@code ai_analysis.status}。AI 分析失败时该记录仍要落库（§24.3 降级方案），
 * 不能因为调用失败就不写。
 */
@Getter
public enum AiAnalysisStatus implements CodeEnum {

    SUCCESS("成功"),
    FAILED("失败");

    private final String label;

    AiAnalysisStatus(String label) {
        this.label = label;
    }
}
