package com.opsdesk.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库片段（向量存 Milvus，此处仅存正文与向量映射） Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
}
