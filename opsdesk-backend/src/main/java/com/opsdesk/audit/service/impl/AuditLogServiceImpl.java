package com.opsdesk.audit.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.audit.entity.AuditLog;
import com.opsdesk.audit.mapper.AuditLogMapper;
import com.opsdesk.audit.service.AuditLogService;
import org.springframework.stereotype.Service;

/**
 * 操作审计（不提供修改/删除接口） Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class AuditLogServiceImpl extends ServiceImpl<AuditLogMapper, AuditLog> implements AuditLogService {
}
