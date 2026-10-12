package com.opsdesk.mq.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.opsdesk.mq.entity.EventOutbox;

import java.util.List;

/**
 * 本地消息表（保证「工单存了，事件一定最终发出」） Service
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；业务方法请直接加在本接口上。
 *
 * <h2>⚠️ 生成器会覆盖本文件</h2>
 * {@code tools/gen_entities.py} 是无条件 {@code open(path, "w")} 写入的（没有「已存在则跳过」），
 * 重新生成数据层后 {@link #claimPendingBatch} 会被抹掉 —— 需手工补回。
 * 同样的风险也存在于 {@code NotificationService.notify} 等已加过业务方法的生成文件上。
 */
public interface EventOutboxService extends IService<EventOutbox> {

    /**
     * <b>认领</b>一批待投递的 Outbox 记录（工单 D9-01，规格基线 §13.5）。
     *
     * <pre>
     * SELECT ... FROM event_outbox
     *  WHERE status = 'PENDING' AND retry_count &lt; #{maxRetry}
     *  ORDER BY id ASC
     *  LIMIT #{limit}
     *  FOR UPDATE SKIP LOCKED          ← 并发安全的关键
     * </pre>
     *
     * <h2>为什么是 {@code SKIP LOCKED} 而不是 Redis 锁</h2>
     * Outbox 的语义是「<b>认领一行、处理一行</b>」：多实例同时扫描时，各自拿各自的行、
     * 互不阻塞。{@code SKIP LOCKED}（MySQL 8.0+）天生就是这个语义 ——
     * 已被别的事务锁住的行<b>直接跳过</b>，而不是排队等锁。
     *
     * <p>Redis 分布式锁（如 {@code lock:sla:scan}）在这里反而更差：它锁的是「整轮」，
     * 粒度粗；一旦某轮耗时超过锁 TTL，第二个实例会拿到锁并<b>重扫同一批</b> → 重复投递。
     * 行锁没有 TTL 可以误判，锁的生命周期就是事务的生命周期。
     *
     * <h2>⚠️ 调用方必须处在事务里</h2>
     * {@code FOR UPDATE} 的行锁<b>持有到事务提交</b>才释放 —— 不在事务里执行，
     * 语句一结束锁就没了，「认领」也就失去意义。所以本方法只允许被
     * {@code OutboxPublisher#publishPending()}（带 {@code @Transactional}）调用。
     *
     * <h2>⚠️ 这里是本项目唯一的「拼 SQL」处，为什么可以接受</h2>
     * SOP §5 把「手写 SQL 拼接」列为红区，防的是<b>注入</b>。这里的
     * {@code limit} / {@code maxRetry} 都是 {@code int}（调用方传的是类常量），
     * 走的是 {@code String.valueOf(int)}，<b>没有任何字符串输入能进来</b>；
     * 条件列与排序都是 MP 的 {@code LambdaQueryWrapper}（列名由实体方法引用解析）。
     * 没有把这段挪去 XML，是因为 {@code EventOutboxMapper} 是生成物，
     * 而「自定义 SQL 走 XML」在本单的允许改动里没有对应的 mapper 目录。
     *
     * @param limit    最多认领多少条（批大小，调用方传类常量）
     * @param maxRetry 重试次数上限：{@code retry_count >= maxRetry} 的记录不再认领
     *                 （它们应由投递器置为 {@code FAILED}）
     * @return 已锁定的记录，按 id 升序；可能为空
     */
    List<EventOutbox> claimPendingBatch(int limit, int maxRetry);
}
