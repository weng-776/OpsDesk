package com.opsdesk.sla.service;

import com.opsdesk.common.enums.SlaState;
import com.opsdesk.sla.entity.SlaPolicy;
import com.opsdesk.ticket.entity.Ticket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SLA 状态计算（工单 D5-02，SOP §5 红区：SLA）
 *
 * <p>规格依据：规格基线 <b>§9.5（临近超时与超时判定，逐字实现）</b>、§9.3（「响应」的判定）、
 * §3.8（{@link SlaState}）、§9.1 / §9.7（策略版本化）、§9.8（第一版不做日历）。
 *
 * <h2>§9.5 原文</h2>
 * <pre>
 * 总时长 total     = response_minutes 或 resolution_minutes
 * 剩余   remaining = deadline - now
 *
 * remaining &lt;= 0                → BREACHED
 * 0 &lt; remaining &lt;= 20% × total  → WARNING
 * 否则                           → NORMAL
 * </pre>
 * <ul>
 *   <li>分别计算 {@code sla_response_state} 与 {@code sla_resolution_state}（<b>两条线互不影响</b>）。</li>
 *   <li>第一版<b>不做</b>节假日排除 / 工作时间日历，一律按自然时间（§9.8）。</li>
 *   <li>时间一律 {@link LocalDateTime}，口径与库里的 {@code DATETIME} 一致（yml 已设 {@code GMT+8}）。</li>
 * </ul>
 *
 * <h2>⚠️ 两条线的判定规则是<b>不对称</b>的</h2>
 * <ul>
 *   <li><b>解决线</b>：就是 §9.5 的倒计时，无歧义。</li>
 *   <li><b>响应线</b>：{@code first_response_at} 一旦有值，响应<b>已经发生</b> ——
 *       此时不能再用 {@code remaining = deadline - now} 倒计时（那会把一张早就响应过的工单
 *       在若干天后判成 {@code BREACHED}，而<b>已达成的 deadline 不可能再超时</b>）。
 *       按 §9.3 的口径，此时判定应<b>冻结</b>为「达标 / 未达标」二态：
 *       <pre>
 * first_response_at &lt;= response_deadline              → 达标   → NORMAL
 * first_response_at &gt;  response_deadline              → 未达标 → BREACHED
 * first_response_at IS NULL 且 now &gt; response_deadline → 未响应且已超时 → BREACHED
 *       </pre>
 *       详见 {@link #judgeResponse}。</li>
 * </ul>
 *
 * <h2>⚠️ 暂停期间不做特殊处理（严格按 §9.5）</h2>
 * §9.4 的顺延是<b>在退出暂停（resume）时一次性加 delta</b> —— 也就是说暂停期间
 * {@code resolution_deadline} 还没被推后，{@code remaining} 会照常减少。
 * 本计算器<b>刻意不</b>把 {@code now} 换成 {@code sla_paused_at}：
 * 它只负责 §9.5 的口径，「暂停态工单不应被判超时」是<b>扫描策略</b>，
 * 由 D5-03 的扫描任务跳过 {@code WAITING_USER} / {@code WAITING_CONFIRM} 承担（§9.4 状态表）。
 *
 * <h2>⚠️ total 必须取「冻结的策略版本」</h2>
 * {@code total} 取自 {@code ticket.sla_policy_id} 指向的那一版策略（§9.1/§9.7 版本化：
 * 存量工单不受新版本影响）。
 * <b>不能</b>用 {@code deadline - created_at} 反推 —— 暂停顺延后那个差值会变大，
 * 20% 的 WARNING 带会被撑宽。
 *
 * <h2>为什么是单个 {@code @Component} 而不拆 interface / impl</h2>
 * 与 {@link SlaPauseService} 的形式不同，这里<b>不拆</b>：
 * <ul>
 *   <li>判定逻辑是<b>纯静态函数</b>（{@link #judge} / {@link #judgeResponse}），没有多实现需求；</li>
 *   <li>实例部分只做「按 {@code sla_policy_id} 批量取 minutes」这一件机械的事，
 *       拆出接口只会多一个空壳（SOP §6：不写没有变化的抽象）；</li>
 *   <li>工单 D5-02 的「允许改动」也只列了本文件。</li>
 * </ul>
 *
 * <h2>本单不做</h2>
 * 落库与通知（{@code sla_warning_notified} / {@code sla_breach_notified} 幂等标志）、
 * Redis 分布式锁 {@code lock:sla:scan}、5 分钟定时扫描 —— 全部归 <b>D5-03</b>。
 * 本单只产出「给定工单 + 当前时刻 → 两条线的状态」这个纯计算能力。
 */
@Slf4j
@Component
public class SlaCalculator {

    /** §9.5 的 WARNING 阈值：剩余 ≤ 20% × total */
    private static final int WARNING_PERCENT = 20;

    private final SlaPolicyService slaPolicyService;

    public SlaCalculator(SlaPolicyService slaPolicyService) {
        this.slaPolicyService = slaPolicyService;
    }

    // ==================== 纯函数核心（无依赖，可脱离 Spring 单测）====================

    /**
     * §9.5 单线判定（纯函数）。
     *
     * <pre>
     * remaining &lt;= 0                → BREACHED   （恰好为 0 也是 BREACHED，原文是 &lt;=）
     * 0 &lt; remaining &lt;= 20% × total  → WARNING    （恰好 20% 也是 WARNING，原文是 &lt;=）
     * 否则                           → NORMAL
     * </pre>
     *
     * @param totalMinutes 该线的总时长（{@code response_minutes} 或 {@code resolution_minutes}）。
     *                     为 {@code null} 或 {@code <= 0} 时<b>退化</b>为
     *                     {@code BREACHED / NORMAL} 二态（没有 total 就算不出 WARNING 带）
     * @param deadline     该线的截止时间；为 {@code null} 表示<b>该单无 SLA</b> → 返回 {@code null}
     * @param now          当前时刻
     * @return 该线状态；{@code deadline} 为 {@code null} 时返回 {@code null}（不判定）
     */
    public static SlaState judge(Integer totalMinutes, LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null) {
            // 无 SLA（D3-01 在该优先级没有 ACTIVE 策略时不阻断创建，deadline 留空）→ 不判定
            return null;
        }
        Duration remaining = Duration.between(now, deadline);

        // ① 已超时（含恰好到期）
        if (remaining.isZero() || remaining.isNegative()) {
            return SlaState.BREACHED;
        }

        // ② 没有 total 就算不出 20% 带 → 退化为二态
        if (totalMinutes == null || totalMinutes <= 0) {
            return SlaState.NORMAL;
        }

        // ③ 用 Duration 精确算 20%，避免整数除法把 1 分钟的总时长截成 0
        Duration warningBand = Duration.ofMinutes(totalMinutes)
                .multipliedBy(WARNING_PERCENT)
                .dividedBy(100);

        return remaining.compareTo(warningBand) <= 0 ? SlaState.WARNING : SlaState.NORMAL;
    }

    /**
     * 响应线判定（纯函数）—— 见类注释「两条线的判定规则是不对称的」。
     *
     * <p>已响应（{@code firstResponseAt != null}）时判定<b>冻结</b>为 §9.3 的「达标 / 未达标」，
     * 不再倒计时；未响应时才走 §9.5 的倒计时。
     *
     * @param responseDeadline 响应截止；为 {@code null} 表示无 SLA → 返回 {@code null}
     * @param firstResponseAt  首次受理时间；{@code null} 表示尚未响应
     * @param responseMinutes  策略的 {@code response_minutes}（仅未响应时用于算 WARNING 带）
     * @param now              当前时刻
     * @return 响应线状态；{@code responseDeadline} 为 {@code null} 时返回 {@code null}
     */
    public static SlaState judgeResponse(LocalDateTime responseDeadline,
                                         LocalDateTime firstResponseAt,
                                         Integer responseMinutes,
                                         LocalDateTime now) {
        if (responseDeadline == null) {
            return null;
        }
        if (firstResponseAt != null) {
            // §9.3「响应是否达标」：已响应的判定是二元的、冻结的 —— 迟到的响应要留痕
            return firstResponseAt.isAfter(responseDeadline) ? SlaState.BREACHED : SlaState.NORMAL;
        }
        return judge(responseMinutes, responseDeadline, now);
    }

    // ==================== 实例部分：取策略 minutes 后套用纯函数 ====================

    /**
     * 算一张工单的两条线。
     *
     * @param ticket 工单；为 {@code null} 时返回空结果（两个字段都是 {@code null}）
     * @param now    当前时刻；为 {@code null} 时取 {@link LocalDateTime#now()}（定时任务里可省一次取值）
     * @return 两条线的状态；某条线无 SLA 时该字段为 {@code null}
     */
    public SlaOutcome compute(Ticket ticket, LocalDateTime now) {
        if (ticket == null) {
            return SlaOutcome.NONE;
        }
        LocalDateTime at = now != null ? now : LocalDateTime.now();
        SlaPolicy policy = loadPolicy(ticket.getSlaPolicyId());
        return compute(ticket, policy, at);
    }

    /**
     * 批量算一页工单 —— 给 D5-03 的扫描任务用。
     *
     * <p><b>为什么要有批量版</b>：扫描一次可能捞 100 张工单，
     * 逐条 {@link #compute} 就是 100 次 {@code sla_policy} 查询。这里先按
     * {@code sla_policy_id} 去重、<b>一次</b> {@code listByIds} 取回所有版本，再逐条套纯函数。
     *
     * @param tickets 工单列表；{@code null} 或空 → 返回空 Map
     * @param now     当前时刻
     * @return {@code ticketId -> 两条线状态}（保持入参顺序，用 {@link LinkedHashMap}）
     */
    public Map<Long, SlaOutcome> computeBatch(List<Ticket> tickets, LocalDateTime now) {
        Map<Long, SlaOutcome> result = new LinkedHashMap<>();
        if (tickets == null || tickets.isEmpty()) {
            return result;
        }
        LocalDateTime at = now != null ? now : LocalDateTime.now();

        // 一次取回本页涉及的所有策略版本（去重 + 去掉 null）
        Set<Long> policyIds = tickets.stream()
                .map(Ticket::getSlaPolicyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SlaPolicy> policies = loadPolicies(policyIds);

        for (Ticket ticket : tickets) {
            if (ticket == null || ticket.getId() == null) {
                continue;
            }
            SlaPolicy policy = policies.get(ticket.getSlaPolicyId());
            result.put(ticket.getId(), compute(ticket, policy, at));
        }
        return result;
    }

    // ==================== 私有 ====================

    /** 套用两条线的纯函数（{@code policy} 可为 {@code null} → minutes 全缺，判定退化） */
    private SlaOutcome compute(Ticket ticket, SlaPolicy policy, LocalDateTime now) {
        Integer responseMinutes = policy != null ? policy.getResponseMinutes() : null;
        Integer resolutionMinutes = policy != null ? policy.getResolutionMinutes() : null;

        SlaState responseState = judgeResponse(
                ticket.getResponseDeadline(), ticket.getFirstResponseAt(), responseMinutes, now);
        SlaState resolutionState = judge(
                resolutionMinutes, ticket.getResolutionDeadline(), now);

        return new SlaOutcome(responseState, resolutionState);
    }

    /** 单条取策略版本；取不到只记 warn（策略被删/版本缺失不该让扫描任务整体失败） */
    private SlaPolicy loadPolicy(Long policyId) {
        if (policyId == null) {
            return null;
        }
        SlaPolicy policy = slaPolicyService.getById(policyId);
        if (policy == null) {
            log.warn("[SLA] 工单引用的策略版本不存在（policyId={}），本次按无 total 判定", policyId);
        }
        return policy;
    }

    /** 批量取策略版本；空集合直接返回空 Map（避免 {@code listByIds(empty)} 的边界行为） */
    private Map<Long, SlaPolicy> loadPolicies(Collection<Long> policyIds) {
        if (policyIds.isEmpty()) {
            return Map.of();
        }
        List<SlaPolicy> policies = slaPolicyService.listByIds(policyIds);
        Map<Long, SlaPolicy> byId = new LinkedHashMap<>();
        for (SlaPolicy policy : policies) {
            byId.put(policy.getId(), policy);
        }
        if (byId.size() != policyIds.size()) {
            log.warn("[SLA] 有策略版本查不到（请求 {} 条，命中 {} 条），对应工单按无 total 判定",
                    policyIds.size(), byId.size());
        }
        return byId;
    }

    /**
     * 一张工单两条线的判定结果。
     *
     * @param responseState   响应线；{@code null} = 该单无响应 SLA（{@code response_deadline} 为空）
     * @param resolutionState 解决线；{@code null} = 该单无解决 SLA（{@code resolution_deadline} 为空）
     */
    public record SlaOutcome(SlaState responseState, SlaState resolutionState) {

        /** 空结果：两条线都不判定（工单为 {@code null} 时用） */
        public static final SlaOutcome NONE = new SlaOutcome(null, null);
    }
}
