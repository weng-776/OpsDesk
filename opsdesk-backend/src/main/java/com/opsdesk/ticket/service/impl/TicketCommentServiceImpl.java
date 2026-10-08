package com.opsdesk.ticket.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.ticket.entity.TicketComment;
import com.opsdesk.ticket.mapper.TicketCommentMapper;
import com.opsdesk.ticket.service.TicketCommentService;
import org.springframework.stereotype.Service;

/**
 * 工单评论 Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class TicketCommentServiceImpl extends ServiceImpl<TicketCommentMapper, TicketComment> implements TicketCommentService {
}
