package com.opsdesk.sla.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.sla.entity.SlaPolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * SLA 策略（版本化：修改=停用旧版本+新增版本） Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface SlaPolicyMapper extends BaseMapper<SlaPolicy> {
}
