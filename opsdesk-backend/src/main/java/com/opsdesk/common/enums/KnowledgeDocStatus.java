package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 知识文档状态（规格基线 §3.12）
 *
 * <p>写 {@code knowledge_document.status}。上传后异步向量化（§12.3），
 * 处理过程为 {@code PROCESSING} → {@code READY} / {@code FAILED}，前端轮询该状态。
 */
@Getter
public enum KnowledgeDocStatus implements CodeEnum {

    PROCESSING("处理中"),
    READY("就绪"),
    FAILED("失败");

    private final String label;

    KnowledgeDocStatus(String label) {
        this.label = label;
    }
}
