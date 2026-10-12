package com.opsdesk.mq.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.common.enums.OutboxStatus;
import com.opsdesk.mq.entity.EventOutbox;
import com.opsdesk.mq.mapper.EventOutboxMapper;
import com.opsdesk.mq.service.EventOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 本地消息表（保证「工单存了，事件一定最终发出」） Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 *
 * <h2>⚠️ 生成器会覆盖本文件</h2>
 * {@code gen_entities.py} 没有「已存在则跳过」，重新生成数据层会抹掉
 * {@link #claimPendingBatch} —— 需手工补回（与 {@code NotificationServiceImpl.notify} 同一类风险）。
 */
@Slf4j
@Service
public class EventOutboxServiceImpl extends ServiceImpl<EventOutboxMapper, EventOutbox> implements EventOutboxService {

    @Override
    public List<EventOutbox> claimPendingBatch(int limit, int maxRetry) {
        // ⚠️ 顺序不能变：MySQL 要求 FOR UPDATE 出现在 LIMIT **之后**。
        //    last() 是 MP 里唯一能表达「锁子句」的位置 —— 它把内容追加到整条 SQL 末尾。
        //    limit / maxRetry 都是 int，没有注入面（见接口 javadoc 的说明）。
        String lockClause = "LIMIT " + limit + " FOR UPDATE SKIP LOCKED";

        return list(new LambdaQueryWrapper<EventOutbox>()
                // idx_outbox_status(status, created_at) 的最左前缀命中
                .eq(EventOutbox::getStatus, OutboxStatus.PENDING)
                .lt(EventOutbox::getRetryCount, maxRetry)
                // 按 id 升序：先产生的先投递（§13.5 无顺序保证要求，但 FIFO 便于排查）
                .orderByAsc(EventOutbox::getId)
                .last(lockClause));
    }
}
