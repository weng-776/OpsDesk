package com.opsdesk.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库片段（向量存 Milvus，此处仅存正文与向量映射）
 *
 * <p>对应表 {@code knowledge_chunk}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 */
    private Long documentId;

    /** 片段正文 */
    private String content;

    /** 片段序号（从 0 开始） */
    private Integer chunkIndex;

    /** 向量库中的映射 ID（Milvus 主键） */
    private String vectorId;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
