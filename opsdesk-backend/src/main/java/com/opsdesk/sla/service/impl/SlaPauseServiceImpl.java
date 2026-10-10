package com.opsdesk.sla.service.impl;

import com.opsdesk.sla.service.SlaPauseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * SLA 暂停顺延实现（工单 D4-03）
 *
 * <p>规格依据：规格基线 §9.4（逐字实现）。
 *
 * <h2>两种「分钟」口径（不要混）</h2>
 * <ul>
 *   <li>{@code resolution_deadline += delta} —— 用<b>精确 Duration</b> 偏移。
 *       90 秒的暂停就顺延 90 秒，不截断。</li>
 *   <li>{@code sla_paused_minutes += delta（分钟）} —— 字段是<b>分钟</b>，用
 *       {@link Duration#toMinutes()} <b>截断</b>（90 秒 → +1 分钟）。</li>
 * </ul>
 * 两者会短暂不一致（90 秒 → deadline 挪 90 秒、分钟数 +1），这是 §9.4 原文的口径，
 * 已与用户确认。累计暂停时长是「统计口径」，deadline 才是「判定口径」。
 *
 * <h2>三种防御性处理（都不写坏数据）</h2>
 * <ol>
 *   <li>{@code sla_paused_at} 为 null —— 正常不可达（{@code WAITING_USER} 必有起点，
 *       状态机保证了这点）。真出现说明数据被绕过约束改过：按「无暂停」处理（delta = 0），
 *       记 warn，<b>不抛异常</b>（抛了会让工单卡在 WAITING_USER 出不来）。</li>
 *   <li>{@code delta} 为负（时钟回拨 / 起点被写成未来）—— 钳到 0，记 warn。
 *       否则会把 deadline <b>往前挪</b>，等于凭空吃掉时限。</li>
 *   <li>{@code resolution_deadline} 为 null —— 保持 null（D3-01 里该优先级无 ACTIVE 策略时
 *       不阻断创建，允许 SLA 为空）。null 上做加法会 NPE。</li>
 * </ol>
 */
@Slf4j
@Service
public class SlaPauseServiceImpl implements SlaPauseService {

    @Override
    public ResumeOutcome resume(LocalDateTime slaPausedAt,
                                LocalDateTime resolutionDeadline,
                                Integer slaPausedMinutes,
                                LocalDateTime now) {
        int currentPausedMinutes = slaPausedMinutes == null ? 0 : slaPausedMinutes;

        if (slaPausedAt == null) {
            // 数据不一致：本方法只在 WAITING_USER → IN_PROGRESS 时调用，那时必然有起点。
            // 保守处理成「没有暂停过」，让工单能正常恢复，而不是卡死
            log.warn("[SLA 顺延] sla_paused_at 为空，按无暂停处理（不移动 deadline）。now={}", now);
            return new ResumeOutcome(resolutionDeadline, currentPausedMinutes, Duration.ZERO);
        }

        Duration paused = Duration.between(slaPausedAt, now);
        if (paused.isNegative()) {
            log.warn("[SLA 顺延] 暂停时长为负（时钟回拨？），钳为 0。slaPausedAt={} now={}",
                    slaPausedAt, now);
            paused = Duration.ZERO;
        }

        // ① 解决时限顺延精确时长（§9.4 的 resolution_deadline += delta）
        LocalDateTime newDeadline =
                resolutionDeadline == null ? null : resolutionDeadline.plus(paused);

        // ② 累计暂停分钟按分钟截断（§9.4 的 sla_paused_minutes += delta（分钟））
        long deltaMinutes = paused.toMinutes();
        int newPausedMinutes = Math.toIntExact(currentPausedMinutes + deltaMinutes);

        log.info("[SLA 顺延] 暂停 {} 分钟（{}），resolution_deadline {} → {}",
                deltaMinutes, paused, resolutionDeadline, newDeadline);

        return new ResumeOutcome(newDeadline, newPausedMinutes, paused);
    }
}
