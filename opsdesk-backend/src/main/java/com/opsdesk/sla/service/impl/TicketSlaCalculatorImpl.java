package com.opsdesk.sla.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.sla.entity.SlaPolicy;
import com.opsdesk.sla.service.SlaPolicyService;
import com.opsdesk.sla.service.TicketSlaCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * SLA 计算最小版实现（工单 D3-01）
 *
 * <p>规格依据：规格基线 §9.1（策略表 + 版本化）、§9.2（从 created_at 起算）。
 */
@Slf4j
@Service
public class TicketSlaCalculatorImpl implements TicketSlaCalculator {

    /**
     * {@code sla_policy.status} 的 ACTIVE 值。
     *
     * <p>DDL 里这一列是 {@code VARCHAR(16)} 而不是枚举（§3.12 没有为它定义枚举），
     * 所以这里用一个私有常量收口，不在业务代码里散落字面量（SOP §6）。
     */
    private static final String POLICY_STATUS_ACTIVE = "ACTIVE";

    private final SlaPolicyService slaPolicyService;

    public TicketSlaCalculatorImpl(SlaPolicyService slaPolicyService) {
        this.slaPolicyService = slaPolicyService;
    }

    @Override
    public SlaSnapshot calculate(TicketPriority priority, LocalDateTime createdAt) {
        if (priority == null || createdAt == null) {
            return null;
        }

        SlaPolicy policy = activePolicyOf(priority);
        if (policy == null) {
            log.warn("[SLA] 优先级 {} 没有 ACTIVE 的策略，本次不计算 deadline", priority);
            return null;
        }

        return new SlaSnapshot(
                policy.getId(),
                createdAt.plusMinutes(policy.getResponseMinutes()),
                createdAt.plusMinutes(policy.getResolutionMinutes()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>算法见接口注释（差值平移）。判空一律返回 {@code null}，让调用方决定「不重算」怎么记录。
     */
    @Override
    public RescheduleResult rescheduleForPriorityChange(Long oldPolicyId, TicketPriority newPriority,
                                                        LocalDateTime currentResolutionDeadline) {
        // 没有基准就无从平移（创建时就没有 SLA 或数据被清空）—— 不猜、不硬造
        if (newPriority == null || currentResolutionDeadline == null) {
            return null;
        }
        if (oldPolicyId == null) {
            log.warn("[SLA 重算] 工单没有冻结的政策版本 id，无法计算差值，保持原解决时限不动");
            return null;
        }

        SlaPolicy oldPolicy = slaPolicyService.getById(oldPolicyId);
        if (oldPolicy == null || oldPolicy.getResolutionMinutes() == null) {
            log.warn("[SLA 重算] 取不到旧策略或旧 resolution_minutes（policyId={}），保持原解决时限不动",
                    oldPolicyId);
            return null;
        }

        SlaPolicy newPolicy = activePolicyOf(newPriority);
        if (newPolicy == null || newPolicy.getResolutionMinutes() == null) {
            log.warn("[SLA 重算] 新优先级 {} 没有 ACTIVE 策略，保持原解决时限不动", newPriority);
            return null;
        }

        // 新旧命中同一版策略 → 分钟数必然相同，平移量为 0，不必写库
        if (newPolicy.getId().equals(oldPolicy.getId())) {
            return null;
        }

        long deltaMinutes = (long) newPolicy.getResolutionMinutes() - oldPolicy.getResolutionMinutes();
        LocalDateTime newDeadline = currentResolutionDeadline.plusMinutes(deltaMinutes);
        log.info("[SLA 重算] 优先级变更：policyId {} → {}，解决时限平移 {} 分钟（{} → {}）",
                oldPolicy.getId(), newPolicy.getId(), deltaMinutes,
                currentResolutionDeadline, newDeadline);
        return new RescheduleResult(newPolicy.getId(), newDeadline);
    }

    /**
     * 取某优先级的当前 ACTIVE 策略；没有则返回 {@code null}。
     *
     * <p>命中 {@code idx_sla_priority(priority, status)}。
     * 用 {@code getOne(wrapper)} 而<b>不是</b> {@code getOne(wrapper, false)}：DDL 上有
     * 唯一索引 {@code uk_sla_active_priority(active_priority)} 保证「同一 priority 只有一条 ACTIVE」，
     * 真查出多行说明数据被绕过约束写坏了 —— 宁可抛也不要静默取第一行（known-traps #2）。
     */
    private SlaPolicy activePolicyOf(TicketPriority priority) {
        return slaPolicyService.getOne(new LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getPriority, priority)
                .eq(SlaPolicy::getStatus, POLICY_STATUS_ACTIVE));
    }

    @Override
    public LocalDateTime reopenResolutionDeadline(Long slaPolicyId, LocalDateTime reopenAt) {
        if (slaPolicyId == null || reopenAt == null) {
            return null;
        }
        // 按 id 取「工单冻结的那一版」策略（§9.1/§9.7 版本化：不受新版本影响）
        SlaPolicy policy = slaPolicyService.getById(slaPolicyId);
        if (policy == null || policy.getResolutionMinutes() == null) {
            log.warn("[SLA] 重新打开时取不到策略或 resolution_minutes（policyId={}），解决时限保持不变",
                    slaPolicyId);
            return null;
        }
        return reopenAt.plusMinutes(policy.getResolutionMinutes());
    }
}
