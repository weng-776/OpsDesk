package com.opsdesk.ai.service;

import com.opsdesk.ai.dto.AiAdoptDTO;
import com.opsdesk.ticket.vo.TicketDetailVO;

/**
 * 采纳 AI 建议（工单 D10-05，API 文档 §8.14，规格基线 §10.2 / §3.11）
 *
 * <h2>🔴 这是 AI 结果进入业务数据的<b>唯一入口</b></h2>
 * 整条 AI 链路上，只有这一个方法会改工单字段。前面所有环节
 * （分析、校验、降级、落 {@code ai_analysis}）都只写 AI 自己的表，绝不碰 {@code ticket}。
 * 所以本接口的每个校验都不能省 —— 它同时是「功能入口」和「越权/脏数据的最后一道闸」。
 *
 * <h2>采纳范围</h2>
 * 只有 `category` 与 `priority` 两项，且<b>必须由调用方显式勾选</b>（见 {@link AiAdoptDTO}）。
 * {@code type} 不在其中。
 */
public interface AiAdoptService {

    /**
     * 按开关采纳最近一次成功的 AI 分析结果。
     *
     * <p>校验顺序（每一步都不能省，原因见各条）：
     * <ol>
     *   <li><b>存在性 + 数据范围</b> —— 先 40400 再 40301（不泄露资源存在性）；</li>
     *   <li><b>终态拦截</b> —— {@code CLOSED} / {@code CANCELLED} 采纳没有意义 → 40900；</li>
     *   <li><b>分析记录存在且 SUCCESS</b> —— 没有 → 40400；最近一次是 FAILED → 40001；</li>
     *   <li><b>值白名单二次校验</b> —— 采纳值必须非空且是 §3 枚举常量。</li>
     * </ol>
     *
     * @param ticketId 工单 ID
     * @param dto      采纳开关（都默认 false）
     * @return 采纳后的工单最新快照（§8.12 同款，供前端直接刷新）
     * @throws com.opsdesk.common.BizException 40400 / 40301 / 40900 / 40001
     */
    TicketDetailVO adopt(long ticketId, AiAdoptDTO dto);
}
