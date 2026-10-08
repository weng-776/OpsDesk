package com.opsdesk.knowledge.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.knowledge.entity.KnowledgeDocument;
import com.opsdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.opsdesk.knowledge.service.KnowledgeDocumentService;
import org.springframework.stereotype.Service;

/**
 * 知识库文档 Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> implements KnowledgeDocumentService {
}
