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

        // 命中 idx_sla_priority(priority, status)。
        // 用 getOne(wrapper) 而**不是** getOne(wrapper, false)：DDL 上有
        // 唯一索引 uk_sla_active_priority(active_priority) 保证「同一 priority 只有一条 ACTIVE」，
        // 真查出多行说明数据被绕过约束写坏了 —— 宁可抛也不要静默取第一行（known-traps #2）
        SlaPolicy policy = slaPolicyService.getOne(new LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getPriority, priority)
                .eq(SlaPolicy::getStatus, POLICY_STATUS_ACTIVE));

        if (policy == null) {
            log.warn("[SLA] 优先级 {} 没有 ACTIVE 的策略，本次不计算 deadline", priority);
            return null;
        }

        return new SlaSnapshot(
                policy.getId(),
                createdAt.plusMinutes(policy.getResponseMinutes()),
                createdAt.plusMinutes(policy.getResolutionMinutes()));
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
