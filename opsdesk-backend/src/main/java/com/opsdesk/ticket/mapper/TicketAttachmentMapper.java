package com.opsdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.ticket.entity.TicketAttachment;
import com.opsdesk.ticket.vo.AttachmentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工单附件 Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 *
 * <p>本类的自定义方法（{@link #selectByTicketIdWithUploader}）实现落在
 * {@code src/main/resources/mapper/TicketAttachmentMapper.xml}（工单 D3-05）。
 */
@Mapper
public interface TicketAttachmentMapper extends BaseMapper<TicketAttachment> {

    /**
     * 按工单查附件列表，并 JOIN 出上传人姓名（工单 D3-05，API §8.10）。
     *
     * <p>SQL 见 {@code mapper/TicketAttachmentMapper.xml} —— 按 SOP §5「手写 SQL 拼接」红区的要求，
     * 自定义 SQL 一律写进 XML，不用 {@code wrapper.apply("...")}。
     *
     * <p>⚠️ 数据范围<b>不在这里</b>：本方法只认 {@code ticketId}。
     * 调用方必须<b>先</b>通过 {@code TicketDataScopeHelper.assertVisible} 确认当前用户能看到该工单，
     * 否则「知道 id 就能列出附件」。这是有意的分层 —— SQL 只管取数，安全判定收口在 Service。
     *
     * @param ticketId 工单 id
     * @return 按 created_at 升序（id 做 tiebreaker）的附件列表；无附件返回空列表
     */
    List<AttachmentVO> selectByTicketIdWithUploader(@Param("ticketId") Long ticketId);
}
