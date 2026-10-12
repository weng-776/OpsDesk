package com.opsdesk.mq.vo;

import lombok.Data;

/**
 * 死信消息（API 文档 §16.17）
 *
 * <table border="1">
 *   <tr><th>字段</th><th>来源</th></tr>
 *   <tr><td>{@code id}</td><td>⚠️ 当前死信列表里的 1-based 序号（见下方「id 的语义」，<b>不是</b>持久标识）</td></tr>
 *   <tr><td>{@code messageId}</td><td>消息头 {@code message_id}（§13.6 的消费幂等键）</td></tr>
 *   <tr><td>{@code queue}</td><td>消息所在的死信队列名</td></tr>
 *   <tr><td>{@code eventType}</td><td>消息头 {@code eventType}（D9-05 起由投递器写入）；
 *       退化取 {@code __TypeId__} 的类名简写，再退化为路由键</td></tr>
 *   <tr><td>{@code payload}</td><td>消息体（**已截断**，见 {@code DeadLetterServiceImpl.MAX_PAYLOAD_LENGTH}）</td></tr>
 *   <tr><td>{@code failedAt}</td><td>recoverer 路径读 {@code x-failed-at}；
 *       broker 原生路径读 {@code x-death[0].time}；都取不到时为 {@code null}</td></tr>
 *   <tr><td>{@code retryCount}</td><td>见下方「两条路径」</td></tr>
 * </table>
 *
 * <h2>⚠️ {@code id} 的语义（D9-05 设计片段里明确的那一条）</h2>
 * 死信在 <b>MQ 里</b>，不在数据库 —— RabbitMQ 管理 API 返回的消息<b>没有稳定的业务 id</b>。
 * 这里把 {@code id} 定义成「<b>当前死信列表里的 1-based 序号</b>」
 * （队列按 {@code RabbitMQConfig} 里的声明顺序聚合，与 {@code GET} 返回的顺序一致）。
 *
 * <p>含义与代价：
 * <ul>
 *   <li>它是**快照定位**，不是持久标识 —— 列表被 purge / 消费后序号会变。</li>
 *   <li>所以 {@code requeue} 前必须先 {@code GET} 一次拿到最新序号；
 *       管理端是人工低频操作，这个约定足够用。</li>
 *   <li>不做成 {@code messageId} 是因为 §16.17 把 {@code id} 定成 {@code long}
 *       （{@code messageId} 是 UUID 字符串，类型对不上）。</li>
 * </ul>
 *
 * <h2>⚠️ {@code retryCount} 两条路径取值不同（§13.4 约束 4）</h2>
 * <table border="1">
 *   <tr><th>路径</th><th>取值</th></tr>
 *   <tr><td>走 recoverer（重试耗尽）</td><td>消息头 {@code x-retry-count}（D9-04 补的，恒 = 3）</td></tr>
 *   <tr><td>broker 原生死信</td><td>{@code x-death[0].count}</td></tr>
 * </table>
 * 两条都取不到时返回 {@code null}（不是 0）—— 「不知道重试了几次」与「一次都没重试」是两回事。
 */
@Data
public class DeadLetterVO {

    /** 当前死信列表里的 1-based 序号（见类注释「id 的语义」） */
    private Long id;

    /** 消息 ID（§13.6 消费端幂等键，也是重投时幂等校验的依据） */
    private String messageId;

    /** 所在死信队列名 */
    private String queue;

    /** 事件类型（取自消息头 {@code __TypeId__}） */
    private String eventType;

    /** 消息体（已截断） */
    private String payload;

    /** 进入死信的时间；broker 没给时为 null */
    private String failedAt;

    /** 重试次数（两条路径取值不同，取不到为 null） */
    private Integer retryCount;
}
