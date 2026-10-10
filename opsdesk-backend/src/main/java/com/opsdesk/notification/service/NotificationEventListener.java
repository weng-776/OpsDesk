package com.opsdesk.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 通知事件监听器（工单 D6-03，规格基线 §24.3 降级方案）
 *
 * <p>把 {@link NotificationEvent} 落到 {@link NotificationService#notify}。
 * 这一层是**可替换的投递实现** —— Day 9 接 RabbitMQ 时把它换成
 * {@code @RabbitListener} 即可，发布事件的触发点一行都不用改。
 *
 * <h2>⚠️ 现在是同步的（故意的）</h2>
 * 用普通 {@code @EventListener} 而不是 {@code @Async}：
 * <ul>
 *   <li>同步意味着监听器跑在<b>发布方的事务里</b> —— 业务回滚时通知一起回滚，
 *       不会出现「工单没建成，却收到了通知」这种假通知；</li>
 *   <li>{@code @Async} 还需要额外开 {@code @EnableAsync}，而<b>忘了开是静默失效</b>
 *       （与 {@code @Scheduled} 漏 {@code @EnableScheduling}、{@code @Aspect} 漏 AOP 依赖同类）。</li>
 * </ul>
 * 要异步化时：加 {@code @Async} + 在配置类上开 {@code @EnableAsync}，
 * 或改成 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * （注意后者默认 {@code fallbackExecution = false}，**没有事务时事件会被直接丢弃**）。
 *
 * <h2>⚠️ 监听器内部不再抛异常</h2>
 * {@code notify(...)} 已经把「参数缺失」降级为记日志（见其实现），
 * 所以这里不额外 try/catch —— 真出了没预料到的异常（如 DB 连不上），
 * 让它冒出去才是对的：静默吞掉会让「通知为什么没发」无从查起。
 */
@Slf4j
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @EventListener
    public void onNotification(NotificationEvent event) {
        log.debug("[通知] 收到事件 userId={} type={} bizType={} bizId={}",
                event.userId(), event.type(), event.bizType(), event.bizId());

        notificationService.notify(event.userId(), event.type(), event.title(),
                event.content(), event.bizType(), event.bizId());
    }
}
