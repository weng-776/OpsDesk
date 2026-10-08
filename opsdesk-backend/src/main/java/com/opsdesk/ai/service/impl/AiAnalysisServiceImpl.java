package com.opsdesk.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.ai.entity.AiAnalysis;
import com.opsdesk.ai.mapper.AiAnalysisMapper;
import com.opsdesk.ai.service.AiAnalysisService;
import org.springframework.stereotype.Service;

/**
 * AI 工单分析结果（不塞进 ticket 主表） Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class AiAnalysisServiceImpl extends ServiceImpl<AiAnalysisMapper, AiAnalysis> implements AiAnalysisService {
}
