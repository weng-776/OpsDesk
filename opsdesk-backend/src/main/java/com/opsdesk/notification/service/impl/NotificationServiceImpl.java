package com.opsdesk.notification.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.opsdesk.common.enums.NotificationType;
import com.opsdesk.notification.entity.Notification;
import com.opsdesk.notification.mapper.NotificationMapper;
import com.opsdesk.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 站内通知（第一版仅站内信，不做邮件/IM） Service 实现
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；这里只放真正有逻辑的实现，
 * 纯透传的 CRUD 不必重写。
 *
 * <h2>⚠️ {@code notify} 的两个字段长度按 DDL 截断</h2>
 * {@code title} 是 {@code VARCHAR(255)}、{@code biz_type} 是 {@code VARCHAR(32)} ——
 * 不截断的话一个超长标题就让整条通知（连带它所在的事务）失败。
 * 通知是「尽力而为」的辅助信息，不该因为它把主业务拖下水。
 * （注意这与审计的取舍<b>不同</b>：审计是追责依据，宁可不做也不丢；通知丢一条无所谓。）
 */
@Slf4j
@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification> implements NotificationService {

    /** {@code notification.title} 是 VARCHAR(255) */
    private static final int MAX_TITLE_LENGTH = 255;

    /** {@code notification.biz_type} 是 VARCHAR(32) */
    private static final int MAX_BIZ_TYPE_LENGTH = 32;

    /** 未读 */
    private static final int UNREAD = 0;

    @Override
    public void notify(Long userId, NotificationType type, String title, String content,
                       String bizType, Long bizId) {
        // 接收人缺失说明调用点写错了 —— 记 error 但不抛：通知不该让主业务失败
        if (userId == null) {
            log.error("[通知] 接收人为空，丢弃。type={} title={}", type, title);
            return;
        }
        if (type == null || !StringUtils.hasText(title)) {
            log.error("[通知] type / title 缺失，丢弃。userId={} type={} title={}", userId, type, title);
            return;
        }

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(truncate(title, MAX_TITLE_LENGTH));
        notification.setContent(content);
        notification.setBizType(truncate(bizType, MAX_BIZ_TYPE_LENGTH));
        notification.setBizId(bizId);
        notification.setReadFlag(UNREAD);

        // created_at 是 DB 默认值（实体上是 insertStrategy = NEVER），不需要回填 ——
        // 通知是「发出去就不再改」的记录，没有「插完再查回来」的需求
        save(notification);

        log.info("[通知] 已发送 userId={} type={} bizType={} bizId={} title={}",
                userId, type, bizType, bizId, notification.getTitle());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
