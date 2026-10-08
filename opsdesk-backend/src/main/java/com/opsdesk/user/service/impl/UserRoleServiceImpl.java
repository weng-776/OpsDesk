package com.opsdesk.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.mapper.UserRoleMapper;
import com.opsdesk.user.service.UserRoleService;
import org.springframework.stereotype.Service;

/**
 * 用户-角色 Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements UserRoleService {
}
