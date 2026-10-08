package com.opsdesk.knowledge.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.opsdesk.knowledge.entity.KnowledgeChunk;

/**
 * 知识库片段（向量存 Milvus，此处仅存正文与向量映射） Service
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；业务方法请直接加在本接口上。
 */
public interface KnowledgeChunkService extends IService<KnowledgeChunk> {
}
