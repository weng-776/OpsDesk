package com.opsdesk.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.ai.entity.AiAnalysis;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 工单分析结果（不塞进 ticket 主表） Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface AiAnalysisMapper extends BaseMapper<AiAnalysis> {
}
