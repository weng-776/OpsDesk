package com.opsdesk.ai.service;

import com.opsdesk.ai.vo.AiAnalysisVO;

/**
 * AI 分析的触发与查询（工单 D10-04，API 文档 §11.1 / §8.13）
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>{@link #triggerAnalyze} —— 手动触发（§11.1），用于<b>重试</b>或手动刷新；</li>
 *   <li>{@link #latest} —— 查询最新结果（§8.13）。</li>
 * </ul>
 * 真正的「分析怎么做」不在这里，而是 {@link TicketAnalyzeService}（D10-02/03）。
 *
 * <h2>🔴 两个方法都要做数据范围校验（这是本单最容易漏的地方）</h2>
 * 「能看见工单」和「能操作工单」是两件事，但**降级到只做后者**同样是越权：
 * <ul>
 *   <li>{@code triggerAnalyze} 不带校验的话，任意 AGENT 传一个 id 就能触发<b>他人工单</b>的 AI 分析 ——
 *       既消耗配额，也会把分析结果写进别人的工单；</li>
 *   <li>{@code latest} 不带校验的话，能直接读到别人的工单分析（含摘要与建议，等于泄露工单内容）。</li>
 * </ul>
 *
 * <h2>⚠️ 判定顺序：先「存在」再「可见」，不能反</h2>
 * <pre>
 * ① 工单查不到        → 40400「工单不存在」
 * ② 查到了但不可见    → 40301
 * </pre>
 * 反过来的话，一个<b>不存在</b>的 id 也会得到 40301，攻击者就能从
 * 「是 40301 而不是 40400」推断出这个 id 是存在的（§2.7 要求不泄露资源存在性）。
 * 与 {@code TicketQueryServiceImpl#detail} 保持完全一致的顺序。
 */
public interface AiAnalysisQueryService {

    /**
     * 手动触发一次分析（API 文档 §11.1，鉴权 {@code ticket:process}）。
     *
     * <p>⚠️ <b>重复触发会落一条新记录</b>（{@code ai_analysis} 本来就是多条的，有
     * {@code idx_ai_ticket(ticket_id, created_at)} 索引）—— 这样才有"重试/刷新"的意义：
     * 旧结果留着可对比，查询按最新一条返回。**不是**覆盖更新。
     *
     * <p>⚠️ 分析本身<b>不会抛异常</b>（D10-02/03 保证：不是降级就是落 FAILED），
     * 所以本方法正常返回的 VO 里 {@code status} 可能是 {@code FAILED} —— 那是业务结果，不是接口错误。
     *
     * @param ticketId 工单 ID
     * @return 本次落库的分析记录
     * @throws com.opsdesk.common.BizException 工单不存在（40400）/ 数据范围外（40301）
     */
    AiAnalysisVO triggerAnalyze(long ticketId);

    /**
     * 查询某工单最新的分析结果（API 文档 §8.13）。
     *
     * <p>⚠️ 三种结果的语义要分清：
     * <ul>
     *   <li>工单不存在 → <b>40400</b>「工单不存在」；</li>
     *   <li>工单可见但从未分析过 → <b>40400</b>「尚未生成分析结果」（§8.13 的 40400 指的是这个）；</li>
     *   <li>有记录，但状态是 {@code FAILED} → <b>返回 200 + FAILED 的 VO</b>。
     *       这里<b>不能</b>返回 40400 —— "分析失败了"与"还没分析过"是两件事，
     *       合并成同一个错误码会让前端无法区分该"重试"还是该"等待"。</li>
     * </ul>
     *
     * @param ticketId 工单 ID
     * @return 最新一条分析记录
     * @throws com.opsdesk.common.BizException 工单不存在 / 尚未生成分析结果（40400）/ 数据范围外（40301）
     */
    AiAnalysisVO latest(long ticketId);
}
