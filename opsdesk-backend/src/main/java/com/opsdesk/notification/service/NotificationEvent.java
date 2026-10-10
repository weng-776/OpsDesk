package com.opsdesk.notification.service;

import com.opsdesk.common.enums.NotificationType;

/**
 * 通知事件（工单 D6-03，规格基线 §24.3 降级方案）
 *
 * <h2>为什么用 Spring {@code ApplicationEvent} 而不是直接调 {@code notify(...)}</h2>
 * §24.3 的降级表写着：RabbitMQ 未就绪时「用 Spring {@code ApplicationEvent} 替代，
 * <b>事件模型不变</b>」。也就是说触发点只负责<b>发布事件</b>，
 * 至于「谁来落库」（本地监听器 or MQ 消费者）是可替换的细节。
 *
 * <pre>
 * 触发点（工单创建/分派/状态变更/评论/SLA 预警…）
 *     │  publishEvent(new NotificationEvent(...))     ← 现在与将来都不变
 *     ▼
 * NotificationEventListener（本地，本工单）    →  Day 9 换成  MQ 消费者
 *     │
 *     ▼
 * NotificationService.notify(...)   ← 唯一写库入口
 * </pre>
 *
 * <p>于是 Day 9 接 MQ 时，<b>触发点一行都不用改</b>：把
 * {@code NotificationEventListener} 换成 {@code @RabbitListener} 即可。
 *
 * <h2>⚠️ 本工单只提供落点，不接触发点</h2>
 * 触发点在 {@code ticket/} / {@code sla/} 里，而 D6-03 的「允许改动」只有
 * {@code notification/} 三个包 —— 所以这里只把「事件 → 落库」这一段做通，
 * 业务侧 publish 留给后续工单。
 *
 * <p>⚠️ 监听器目前是<b>同步</b>的（跑在发布方的事务里）：通知会跟着业务一起提交/回滚，
 * 不会出现「工单没建成却收到了通知」。若要异步或 AFTER_COMMIT 语义，
 * 改监听器上的注解即可，<b>事件与触发点都不动</b>。
 */
public record NotificationEvent(Long userId,
                                NotificationType type,
                                String title,
                                String content,
                                String bizType,
                                Long bizId) {
}
