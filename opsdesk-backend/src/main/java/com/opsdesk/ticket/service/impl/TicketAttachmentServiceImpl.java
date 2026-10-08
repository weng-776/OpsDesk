package com.opsdesk.ticket.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.ticket.entity.TicketAttachment;
import com.opsdesk.ticket.mapper.TicketAttachmentMapper;
import com.opsdesk.ticket.service.TicketAttachmentService;
import org.springframework.stereotype.Service;

/**
 * 工单附件 Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class TicketAttachmentServiceImpl extends ServiceImpl<TicketAttachmentMapper, TicketAttachment> implements TicketAttachmentService {
}
