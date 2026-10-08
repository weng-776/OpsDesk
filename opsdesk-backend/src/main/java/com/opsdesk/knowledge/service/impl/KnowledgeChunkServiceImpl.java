package com.opsdesk.knowledge.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.knowledge.entity.KnowledgeChunk;
import com.opsdesk.knowledge.mapper.KnowledgeChunkMapper;
import com.opsdesk.knowledge.service.KnowledgeChunkService;
import org.springframework.stereotype.Service;

/**
 * 知识库片段（向量存 Milvus，此处仅存正文与向量映射） Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class KnowledgeChunkServiceImpl extends ServiceImpl<KnowledgeChunkMapper, KnowledgeChunk> implements KnowledgeChunkService {
}
