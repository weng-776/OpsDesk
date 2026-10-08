package com.opsdesk.sla.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.sla.entity.SlaPolicy;
import com.opsdesk.sla.mapper.SlaPolicyMapper;
import com.opsdesk.sla.service.SlaPolicyService;
import org.springframework.stereotype.Service;

/**
 * SLA 策略（版本化：修改=停用旧版本+新增版本） Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class SlaPolicyServiceImpl extends ServiceImpl<SlaPolicyMapper, SlaPolicy> implements SlaPolicyService {
}
