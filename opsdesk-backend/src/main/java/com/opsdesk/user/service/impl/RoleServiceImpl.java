package com.opsdesk.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.user.entity.Role;
import com.opsdesk.user.mapper.RoleMapper;
import com.opsdesk.user.service.RoleService;
import org.springframework.stereotype.Service;

/**
 * 角色（第一版固定三个系统角色） Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {
}
