package com.opsdesk.ai.service;

import com.opsdesk.ai.vo.AiAnalysisVO;

/**
 * 工单 AI 分析（工单 D10-02，规格基线 §10.1 / §10.2 / §10.3）
 *
 * <p>「工单描述 → AI → 结构化结果 → 校验 → 落 {@code ai_analysis}」。
 *
 * <h2>⚠️ 本服务是「服务层」，不含触发接线</h2>
 * §10.1 的「工单创建后<b>异步</b>触发」由 D10-04（触发与查询接口）负责接线。
 * 本单只把「一次分析怎么做」做扎实，调用方是谁不关心。
 *
 * <h2>🔴 调用约定：本方法<b>不抛异常</b></h2>
 * §10.2「AI 失败不影响工单主流程」—— 所以：
 * <ul>
 *   <li>LLM 不可用 / 超时 / 返回无法解析 → 内部消化，落一条 {@code FAILED} 记录后正常返回；</li>
 *   <li>调用方拿到的 {@link AiAnalysisVO#getStatus()} 就能判断成败，<b>不需要</b> try-catch；</li>
 *   <li>只有「工单不存在」会返回 {@code null}（没有 ticket_id 就没法落记录，也没必要落）。</li>
 * </ul>
 */
public interface TicketAnalyzeService {

    /**
     * 分析一张工单并存下结果。
     *
     * @param ticketId 工单 ID
     * @return 本次落库的分析记录（含 {@code status}）；工单不存在时返回 {@code null}
     */
    AiAnalysisVO analyze(long ticketId);
}
