package com.opsdesk.sla.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * SLA 暂停与顺延（工单 D4-03，SOP §5 红区：SLA）
 *
 * <p>规格依据：规格基线 <b>§9.4（暂停与顺延，逐字实现）</b>。
 *
 * <h2>§9.4 原文</h2>
 * <pre>
 * 进入暂停状态：sla_paused_at = now
 * 退出暂停状态：delta = now - sla_paused_at
 *              resolution_deadline += delta
 *              sla_paused_minutes += delta（分钟）
 *              sla_paused_at = null
 *
 * > response_deadline 不参与顺延（响应发生在早期，通常已达成）。
 * </pre>
 *
 * <h2>为什么是纯函数</h2>
 * 本接口<b>不碰数据库、不依赖 Ticket 实体</b>，入参出参都是值对象 ——
 * 顺延是本项目最容易算错的一处（时区、截断、负 delta），
 * 拆成纯函数后可以用普通单测把边界钉死，不必起 Spring 上下文。
 *
 * <p>「进入暂停」不需要计算（就是 {@code now}），所以本接口只有 {@link #resume}。
 */
public interface SlaPauseService {

    /**
     * 按 §9.4 计算「退出暂停」的结果。
     *
     * <p><b>⚠️ 只顺延 {@code resolution_deadline}，绝不碰 {@code response_deadline}。</b>
     * 本方法连 responseDeadline 都不接收 —— 让「响应时限不顺延」成为<b>结构性保证</b>，
     * 而不是靠调用方自觉。
     *
     * @param slaPausedAt        暂停起点（{@code ticket.sla_paused_at}）；为 {@code null} 时按「无暂停」处理
     * @param resolutionDeadline 当前解决截止；为 {@code null} 时保持 {@code null}（该单无 SLA）
     * @param slaPausedMinutes   累计暂停分钟（{@code ticket.sla_paused_minutes}）；为 {@code null} 按 0
     * @param now                恢复时刻
     * @return 顺延后的解决截止与累计暂停分钟
     */
    ResumeOutcome resume(LocalDateTime slaPausedAt,
                         LocalDateTime resolutionDeadline,
                         Integer slaPausedMinutes,
                         LocalDateTime now);

    /**
     * 「退出暂停」的计算产物。
     *
     * @param resolutionDeadline 顺延后的解决截止（原值 + 暂停时长）
     * @param pausedMinutes      顺延后的累计暂停分钟
     * @param paused             本次实际计入的暂停时长（已钳掉负值），便于日志与测试断言
     */
    record ResumeOutcome(LocalDateTime resolutionDeadline, int pausedMinutes, Duration paused) {
    }
}
