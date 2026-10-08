package com.opsdesk.mq.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.mq.entity.EventOutbox;
import com.opsdesk.mq.mapper.EventOutboxMapper;
import com.opsdesk.mq.service.EventOutboxService;
import org.springframework.stereotype.Service;

/**
 * 本地消息表（保证「工单存了，事件一定最终发出」） Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 */
@Service
public class EventOutboxServiceImpl extends ServiceImpl<EventOutboxMapper, EventOutbox> implements EventOutboxService {
}
