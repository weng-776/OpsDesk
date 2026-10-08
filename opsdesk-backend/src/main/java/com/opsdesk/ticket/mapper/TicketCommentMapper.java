package com.opsdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.ticket.entity.TicketComment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单评论 Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface TicketCommentMapper extends BaseMapper<TicketComment> {
}
