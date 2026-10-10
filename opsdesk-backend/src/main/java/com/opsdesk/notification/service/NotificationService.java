package com.opsdesk.notification.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.opsdesk.common.enums.NotificationType;
import com.opsdesk.notification.entity.Notification;

/**
 * 站内通知（第一版仅站内信，不做邮件/IM） Service
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；业务方法请直接加在本接口上。
 *
 * <h2>⚠️ 本接口只加「写」的入口，查询/已读在 {@link NotificationBizService}</h2>
 * 生成接口负责「落库」，业务编排（列表 / 未读数 / 标记已读）在
 * {@code NotificationBizService} —— 与 {@code TicketCommentService} +
 * {@code TicketCommentBizService} 的分工一致。
 *
 * <h2>⚠️ 生成器会覆盖本文件</h2>
 * {@code tools/gen_entities.py} 是无条件 {@code open(path, "w")} 写入的（没有「已存在则跳过」）。
 * 重新生成数据层后，{@link #notify} 会被抹掉 —— 需要手工补回，或先给生成器加跳过逻辑。
 * （同样的风险也存在于已被加过业务方法的 {@code TicketMapper} / {@code TicketAttachmentMapper} 等。）
 */
public interface NotificationService extends IService<Notification> {

    /**
     * 发一条站内通知 —— <b>全项目写 {@code notification} 表的唯一入口</b>。
     *
     * <p>所有触发点（工单创建 / 分派 / 状态变更 / 评论 / SLA 预警与超时）都调它，
     * <b>不要各处自己 {@code save} / {@code insert}</b>：统一入口才能保证
     * 字段口径（谁收、bizType 怎么填、title 怎么措辞）不会在每个调用点各写一套。
     *
     * <p>触发点的投递方式见 {@code NotificationEvent}：本工单不接 MQ，
     * 先用 Spring {@code ApplicationEvent}（§24.3 降级方案），Day 9 换成 MQ 消费 ——
     * <b>换的时候只改监听器，触发点不动</b>。
     *
     * <h2>⚠️ 不做「自己给自己发通知」的判断</h2>
     * 「操作人 == 接收人时不发」这类规则属于<b>业务语义</b>，因触发点而异
     * （例如「工单被分派给你」在转派给自己时确实不该发，但「状态已更新」给创建人发就合理）。
     * 放在这里一刀切会把某些合法通知也挡掉 —— 交给调用方在触发点判断。
     *
     * @param userId  接收人（工单相关人，<b>不是</b>操作人）
     * @param type    通知类型（§3.10）
     * @param title   标题；列宽 {@code VARCHAR(255)}，超长会被截断
     * @param content 正文；{@code TEXT}，可为 {@code null}
     * @param bizType 业务类型（如 {@code "TICKET"}），供前端跳转；可为 {@code null}
     * @param bizId   业务 ID，供前端跳转；可为 {@code null}
     */
    void notify(Long userId, NotificationType type, String title, String content,
                String bizType, Long bizId);
}
