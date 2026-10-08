package com.opsdesk.organization.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.organization.entity.Department;
import com.opsdesk.organization.mapper.DepartmentMapper;
import com.opsdesk.organization.service.DepartmentService;
import org.springframework.stereotype.Service;

/**
 * 部门 Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class DepartmentServiceImpl extends ServiceImpl<DepartmentMapper, Department> implements DepartmentService {
}
