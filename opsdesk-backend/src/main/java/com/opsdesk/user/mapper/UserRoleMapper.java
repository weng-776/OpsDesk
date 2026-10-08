package com.opsdesk.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.user.entity.UserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-角色 Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {
}
