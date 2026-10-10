package com.opsdesk.ticket.service;

import com.opsdesk.common.PageResult;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.vo.TicketDetailVO;
import com.opsdesk.ticket.vo.TicketListVO;

/**
 * 工单查询（工单 D3-02）
 *
 * <p>规格依据：API 文档 §8.2 / §8.3、规格基线 §8.3（可见范围）、§18.4（{@code idx_ticket_scope}）。
 *
 * <p><b>本接口的两个方法数据范围不同</b>，这是最容易做错的地方：
 * <ul>
 *   <li>{@link #page} —— 按<b>角色</b>叠加（EMPLOYEE→SELF，AGENT→ASSIGNED∪公共池∪DEPARTMENT，ADMIN→ALL）</li>
 *   <li>{@link #mine} —— <b>强制 SELF</b>（§8.2 抬头明确写「数据范围：SELF」），
 *       ADMIN 调它也只能看到自己创建的</li>
 * </ul>
 */
public interface TicketQueryService {

    /**
     * 工单列表（API 文档 §8.3）—— 数据范围按当前用户角色自动叠加。
     */
    PageResult<TicketListVO> page(TicketQuery query);

    /**
     * 我的工单（API 文档 §8.2）—— 只看自己创建的（{@code creator_id = 当前用户}）。
     */
    PageResult<TicketListVO> mine(TicketQuery query);

    /**
     * 工单详情（API 文档 §8.4）—— 数据范围<b>校验</b>（不是过滤）：不可见抛 40301。
     *
     * @throws com.opsdesk.common.BizException 工单不存在（40400）；数据范围外（40301）
     */
    TicketDetailVO detail(Long id);

    /**
     * 状态流转后的最新快照（API 文档 §8.12「返回最新工单快照，便于前端直接刷新」）。
     *
     * <p>与 {@link #detail(Long)} 的唯一区别：<b>不再做数据范围校验</b>。
     *
     * <h2>⚠️ 为什么必须去掉这道校验</h2>
     * 流转会<b>改变工单的归属</b>，从而改变操作者的可见性。典型例子：
     * AGENT 把一张跨部门的 {@code OPEN} 工单 {@code assign} 给别的 AGENT ——
     * 流转前它靠 §8.3 的「公共待领取池」可见，流转后变成 {@code ASSIGNED} 且处理人是别人，
     * <b>操作者自己反而看不见了</b>。此时若再校验一次，就会出现
     * 「数据库已改成功、接口却返回 40301」——前端弹错误、用户以为失败而重试。
     *
     * <h2>安全性说明（为什么这不构成越权）</h2>
     * 调用方必须是<b>已经通过权限 + 数据范围校验</b>的流转方法（{@code TicketFlowService}）：
     * 操作者在本操作<b>入口</b>已经证明过自己有权看见并操作这张工单，
     * 返回「他自己刚改出来的结果」不产生任何新的信息披露。
     *
     * <p>⚠️ 因此本方法<b>只允许状态流转回写调用</b>，
     * 不得用于任何查询接口 —— 查询一律走 {@link #detail(Long)}。
     *
     * @throws com.opsdesk.common.BizException 工单不存在（40400）
     */
    TicketDetailVO detailForFlow(Long id);
}
