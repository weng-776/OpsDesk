package com.opsdesk.ticket.service;

import com.opsdesk.ticket.vo.TicketHistoryVO;

import java.util.List;

/**
 * 工单历史查询（工单 D4-05）
 *
 * <p>规格依据：API 文档 §8.5 / §16.9；规格基线 §3.12（动作枚举）。
 *
 * <p>与写侧分开：写入由 {@code TicketHistoryRecorder} 统一封装，读取由本接口负责。
 */
public interface TicketHistoryBizService {

    /**
     * 查某张工单的全部历史（按时间升序，<b>不分页</b> —— §8.5 的响应是数组）。
     *
     * <p>⚠️ 先校验调用者对该工单有<b>数据范围可见性</b>：不可见 → {@code 40301}，
     * 工单不存在 → {@code 40400}（顺序与详情一致，不能反，否则泄露 id 是否存在）。
     *
     * @param ticketId 工单 id
     * @return 按 {@code created_at ASC, id ASC} 排列的历史；无历史返回空数组
     */
    List<TicketHistoryVO> list(Long ticketId);
}
