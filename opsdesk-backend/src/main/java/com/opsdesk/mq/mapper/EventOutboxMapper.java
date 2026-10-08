package com.opsdesk.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.mq.entity.EventOutbox;
import org.apache.ibatis.annotations.Mapper;

/**
 * 本地消息表（保证「工单存了，事件一定最终发出」） Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface EventOutboxMapper extends BaseMapper<EventOutbox> {
}
