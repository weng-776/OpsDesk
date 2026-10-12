package com.opsdesk.mq.service;

import com.opsdesk.common.PageResult;
import com.opsdesk.mq.dto.DeadLetterQuery;
import com.opsdesk.mq.vo.DeadLetterVO;

/**
 * 死信管理业务（工单 D9-05，API 文档 §15.1 / §15.2，规格基线 §21.12）
 *
 * <h2>数据源：RabbitMQ 管理 HTTP API（非破坏性读）</h2>
 * 死信在 <b>MQ 里</b>，不在数据库。三种可选做法（D9-05 设计片段里定的是 A）：
 * <table border="1">
 *   <tr><th>方案</th><th>取舍</th></tr>
 *   <tr><td><b>A（采用）</b> 管理 API {@code POST /api/queues/{vhost}/{queue}/get}
 *       + {@code ackmode=ack_requeue_true}</td>
 *       <td>✅ <b>非破坏性</b>：看一眼不会把消息取走。缺点：依赖管理端口 15672 与插件</td></tr>
 *   <tr><td>B {@code RabbitTemplate.receiveAndConvert}</td>
 *       <td>❌ <b>破坏性读</b>：消息被移出队列，必须马上回写，否则「看一眼就丢消息」</td></tr>
 *   <tr><td>C 加一张死信记录表</td>
 *       <td>❌ 属改表结构（SOP §6 黑名单，需人工决策）</td></tr>
 * </table>
 * A 与规格最贴合（§13.4 原文就写了「管理员页面查看 DLQ 消息」），也不引入新的表。
 */
public interface DeadLetterService {

    /**
     * 分页查看死信消息（§15.1）。
     *
     * @param query 分页 + 可选队列名（留空 = 全部死信队列）
     * @return 分页结果，{@code id} 为「当前列表里的 1-based 序号」
     */
    PageResult<DeadLetterVO> page(DeadLetterQuery query);

    /**
     * 重新投递一条死信（§15.2）。
     *
     * <p>⚠️ <b>重投前必须做幂等校验</b>（§15.2 原文）：已成功消费过的消息重投会重复执行业务
     * （例如重复发通知）。校验键与消费端同一套：{@code mq:consumed:{consumerName}:{messageId}}。
     *
     * @param id 死信列表里的 1-based 序号（见 {@link DeadLetterVO} 的「id 的语义」）
     * @return 实际是否重新投递了；{@code false} = 已消费过被幂等挡掉（按 §15.2 仍算成功）
     */
    boolean requeue(long id);
}
