package com.opsdesk.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.user.entity.RolePermission;
import com.opsdesk.user.mapper.RolePermissionMapper;
import com.opsdesk.user.service.RolePermissionService;
import org.springframework.stereotype.Service;

/**
 * 角色-权限 Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class RolePermissionServiceImpl extends ServiceImpl<RolePermissionMapper, RolePermission> implements RolePermissionService {
}
