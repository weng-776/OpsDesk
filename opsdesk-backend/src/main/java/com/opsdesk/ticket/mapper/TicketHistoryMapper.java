package com.opsdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.ticket.entity.TicketHistory;
import com.opsdesk.ticket.vo.TicketHistoryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工单业务过程历史 Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface TicketHistoryMapper extends BaseMapper<TicketHistory> {

    /**
     * 按工单查历史（含操作人姓名），按时间升序（工单 D4-05，API 文档 §8.5 / §16.9）。
     *
     * <p>⚠️ 本查询<b>只负责取数</b>：调用者必须先校验对工单的可见性
     * （{@code TicketDataScopeHelper.assertVisible}）—— 否则「知道 id 就能查历史」。
     *
     * @param ticketId 工单 id
     * @return 按 {@code created_at ASC, id ASC} 排列的历史
     */
    List<TicketHistoryVO> selectByTicketIdWithOperator(@Param("ticketId") Long ticketId);
}
