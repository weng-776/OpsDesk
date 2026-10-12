package com.opsdesk.mq.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.opsdesk.config.RabbitMQConfig;

import java.io.Serializable;

/**
 * 通知请求（工单 D9-03，规格基线 §24.3 降级表的「事件模型不变」）
 *
 * <h2>它为什么存在：让通知过一次 MQ，而触发点一行不改</h2>
 * <pre>
 * 触发点（分派/状态变更/评论/SLA 提醒，Day 6 的 4 处 publishEvent）
 *     │  publishEvent(new NotificationEvent(...))      ← D9-03 一行不改
 *     ▼
 * NotificationEventListener（同步，跑在业务事务里）
 *     │  序列化成 NotificationRequestedEvent 写 event_outbox(status = PENDING)
 *     ▼
 * OutboxPublisher（D9-01）→ opsdesk.notification.queue
 *     ▼
 * NotificationRequestedListener（D9-02 的幂等骨架）
 *     ▼
 * NotificationService.notify(...)   ← 唯一写库入口
 * </pre>
 * {@link com.opsdesk.notification.service.NotificationEvent} 是<b>进程内</b>的 Spring 事件，
 * 过 MQ 必须是可序列化的 DTO，所以有本类。两者字段一一对应。
 *
 * <h2>⚠️ 与 {@code NotificationEvent} 的字段对应（不要漏）</h2>
 * <table border="1">
 *   <tr><th>本类</th><th>来源</th><th>说明</th></tr>
 *   <tr><td>{@code userId}</td><td>同名字段</td><td>收件人</td></tr>
 *   <tr><td>{@code type}</td><td>同名字段</td><td>⚠️ 见下方「为什么 type 是 String」</td></tr>
 *   <tr><td>{@code title}</td><td>同名字段</td><td>已按 {@code notification.title}(255) 截断</td></tr>
 *   <tr><td>{@code content}</td><td>同名字段</td><td>已按 {@code MAX_CONTENT_LENGTH} 截断</td></tr>
 *   <tr><td>{@code bizType}</td><td>同名字段</td><td>已按 {@code notification.biz_type}(32) 截断</td></tr>
 *   <tr><td>{@code bizId}</td><td>同名字段</td><td>⚠️ 漏了前端就跳不了，别丢</td></tr>
 *   <tr><td>{@code traceId}</td><td>发布时的 MDC</td><td>见下方「为什么多一个 traceId」</td></tr>
 * </table>
 *
 * <h2>⚠️ 为什么 {@code type} 声明成 String 而不是 {@code NotificationType}</h2>
 * 消息体里它本来就是字符串。若声明成枚举，遇到非法值 Jackson 会在<b>进入监听器之前</b>
 * 反序列化失败 → 框架重试 3 次 → 进 DLQ —— 一条永远不可能成功的坏消息被重试 3 次、
 * 还占着 DLQ。声明成 String 后，消费端自己做 {@code valueOf}，
 * 非法值可以<b>记 error 日志后跳过</b>（毒消息策略），这正是工单要求的处理方式。
 * 代价是类型安全从字段挪到了消费端 —— 而消费端本来就是「毒消息策略」该待的地方。
 *
 * <h2>⚠️ 为什么多一个 {@code traceId}（工单字段表里没有）</h2>
 * 验收 5 要求「整条链路 traceId 一致（HTTP → Outbox → MQ → 消费者 → 写通知）」。
 * MQ 消息头里的 traceId 是<b>投递器</b>生成的（D9-01：定时任务线程没有请求上下文），
 * 而 {@code event_outbox} 表<b>没有 traceId 列</b>（加列属于 DDL 变更，需人工批准），
 * 所以「HTTP 请求的 traceId」只能随 payload 走。
 * 消费端拿到它之后覆盖 MDC，写通知的日志就与 HTTP 请求串上了。
 * <p>⚠️ 代价：消费端「已消费，跳过」那行日志用的仍是消息头里的 traceId（守卫先于业务执行）。
 * 若要让消息头也带上它，需要让 {@code OutboxPublisher} 读 payload 里的 traceId ——
 * 该文件不在 D9-03 的允许改动内，见交接工单的遗留项。
 *
 * @param userId    收件人
 * @param type      {@link com.opsdesk.common.enums.NotificationType} 的名字（§3.10 白名单）
 * @param title     标题（已截断）
 * @param content   正文（已截断）
 * @param bizType   业务类型（{@code TICKET} 等），前端跳转用
 * @param bizId     业务 ID，前端跳转用
 * @param traceId   发布时 HTTP 请求的 traceId（可为空）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationRequestedEvent(Long userId,
                                         String type,
                                         String title,
                                         String content,
                                         String bizType,
                                         Long bizId,
                                         String traceId) implements Serializable {

    /** 事件类型名，写 {@code event_outbox.event_type} */
    public static final String EVENT_TYPE = "NotificationRequestedEvent";

    /** MQ 路由键（复用拓扑常量，不写裸字符串） */
    public static final String ROUTING_KEY = RabbitMQConfig.RK_NOTIFICATION_REQUESTED;

    /** {@code notification.title} 是 VARCHAR(255) */
    public static final int MAX_TITLE_LENGTH = 255;

    /** {@code notification.biz_type} 是 VARCHAR(32) */
    public static final int MAX_BIZ_TYPE_LENGTH = 32;

    /**
     * 正文上限。
     *
     * <p>{@code notification.content} 是 TEXT（65535 <b>字节</b>），本身够大；
     * 这里额外设一个更紧的上限，是为了让 {@code event_outbox.payload}（也是 TEXT）不被撑大。
     * 取 4000 字符：本项目所有通知模板的正文都在 100 字以内，
     * 这个值实际不可能触到；真触到了会<b>记 warn</b>（不是静默截断）。
     */
    public static final int MAX_CONTENT_LENGTH = 4000;
}
