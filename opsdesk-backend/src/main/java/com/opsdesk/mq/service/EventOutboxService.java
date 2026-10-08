package com.opsdesk.mq.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.opsdesk.mq.entity.EventOutbox;

/**
 * 本地消息表（保证「工单存了，事件一定最终发出」） Service
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；业务方法请直接加在本接口上。
 */
public interface EventOutboxService extends IService<EventOutbox> {
}
