package com.opsdesk.sla.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.sla.dto.SlaPolicyUpdateDTO;
import com.opsdesk.sla.entity.SlaPolicy;
import com.opsdesk.sla.service.SlaPolicyManageService;
import com.opsdesk.sla.service.SlaPolicyService;
import com.opsdesk.sla.vo.SlaPolicyVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 策略版本化管理实现（工单 D5-01，SOP §5 红区：SLA）
 *
 * <p>规格依据：规格基线 §9.1、<b>§9.7</b>；API 文档 §9.1 / §9.2 / §9.3。
 *
 * <h2>版本化的两步（顺序不能反）</h2>
 * <pre>
 * ① UPDATE 旧行：status = 'INACTIVE'
 * ② INSERT 新行：同 priority、新时限、status = 'ACTIVE'、effective_from = now
 * </pre>
 * 反过来的话，① 与 ② 之间会有<b>两条 ACTIVE</b>，唯一索引 {@code uk_sla_active_priority} 直接拒绝。
 * 先停再插，全程 ACTIVE 条数只有 0 或 1。
 *
 * <h2>为什么存量工单不受影响（§9.7）</h2>
 * 只改了旧行的 {@code status}，<b>它的 id 与时限字段一个都没动</b>。
 * 存量工单的 {@code ticket.sla_policy_id} 仍指向这个 id，所以口径不变、可追溯；
 * 新工单由 {@code TicketSlaCalculator} 查 {@code status='ACTIVE'` 拿到新版本。
 *
 * <h2>「同一 priority 只能一条 ACTIVE」的三层保障</h2>
 * <ol>
 *   <li><b>业务层</b>：写入前显式查一次该优先级的 ACTIVE 条数，异常即拒（不靠 DB 报错）</li>
 *   <li><b>顺序</b>：先停旧再插新 —— 从流程上不可能出现两条</li>
 *   <li><b>兜底</b>：万一并发下仍撞了唯一索引，把 {@link DuplicateKeyException} 转成业务 40900，
 *       绝不让数据库约束错误以 50000 漏给客户端</li>
 * </ol>
 */
@Slf4j
@Service
public class SlaPolicyManageServiceImpl implements SlaPolicyManageService {

    /** §9.1：{@code sla_policy.status} 的两个取值（DDL 里是 VARCHAR，不是枚举，所以收口在这里） */
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";

    private final SlaPolicyService slaPolicyService;

    public SlaPolicyManageServiceImpl(SlaPolicyService slaPolicyService) {
        this.slaPolicyService = slaPolicyService;
    }

    // ==================== 查询 ====================

    @Override
    public List<SlaPolicyVO> list(TicketPriority priority, String status) {
        LambdaQueryWrapper<SlaPolicy> wrapper = new LambdaQueryWrapper<>();
        if (priority != null) {
            wrapper.eq(SlaPolicy::getPriority, priority);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SlaPolicy::getStatus, status);
        }
        // 排序：优先级升序 → 生效时间倒序（新版本在前）→ id 倒序做 tiebreaker。
        // effective_from 精度到秒，同秒写入的多条若只按它排，顺序会抖
        wrapper.orderByAsc(SlaPolicy::getPriority)
                .orderByDesc(SlaPolicy::getEffectiveFrom)
                .orderByDesc(SlaPolicy::getId);

        return slaPolicyService.list(wrapper).stream().map(this::toVO).toList();
    }

    @Override
    public SlaPolicyVO detail(Long id) {
        SlaPolicy policy = slaPolicyService.getById(id);
        if (policy == null) {
            throw BizException.notFound("SLA 策略不存在");
        }
        return toVO(policy);
    }

    // ==================== 版本化修改 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SlaPolicyVO update(Long id, SlaPolicyUpdateDTO dto) {
        // 跨字段约束（§9.3）：解决时限 ≥ 响应时限。放这里而不是 DTO 注解上，见 DTO 注释
        if (dto.getResolutionMinutes() < dto.getResponseMinutes()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "解决时限不能小于响应时限");
        }

        // ① 存在性 → 40400
        SlaPolicy current = slaPolicyService.getById(id);
        if (current == null) {
            throw BizException.notFound("SLA 策略不存在");
        }

        // ② 只能"修改"当前生效的版本。历史版本是只读的 —— 否则版本链会分叉
        if (!STATUS_ACTIVE.equals(current.getStatus())) {
            log.warn("[SLA 版本化] 试图修改非 ACTIVE 版本被拒：id={} status={}", id, current.getStatus());
            throw BizException.conflict("只能修改当前生效（ACTIVE）的策略版本，历史版本不可改");
        }

        // ③ 业务层先校验不变量：该 priority 的 ACTIVE 恰好一条，且就是它自己。
        //    ⚠️ 正常路径下数据库唯一索引已保证 ≤1；这里是**不依赖数据库报错**的显式校验，
        //       也能挡住「索引被绕过/删掉」造成的脏数据
        assertSingleActive(current);

        // ④ 停旧（只改 status，id 与时限字段保持原值 → 存量工单口径不变）
        SlaPolicy deactivate = new SlaPolicy();
        deactivate.setId(id);
        deactivate.setStatus(STATUS_INACTIVE);
        slaPolicyService.updateById(deactivate);

        // ⑤ 插新
        SlaPolicy fresh = new SlaPolicy();
        fresh.setPriority(current.getPriority());
        fresh.setResponseMinutes(dto.getResponseMinutes());
        fresh.setResolutionMinutes(dto.getResolutionMinutes());
        fresh.setStatus(STATUS_ACTIVE);
        fresh.setEffectiveFrom(LocalDateTime.now());
        // ⚠️ 绝不 setActivePriority —— 它是生成列（insertStrategy/updateStrategy = NEVER），
        //    由数据库按 IF(status='ACTIVE', priority, NULL) 自己算
        try {
            slaPolicyService.save(fresh);
        }
        catch (DuplicateKeyException ex) {
            // 兜底：并发下两个请求可能都走到这里 → 转成业务错误，别让 DB 约束错误漏出去
            log.warn("[SLA 版本化] 并发写入撞唯一索引，已转为业务冲突：priority={}", current.getPriority());
            throw BizException.conflict("该优先级已存在生效版本，请刷新后重试");
        }

        log.info("[SLA 版本化] 优先级 {} 已换版：旧 id={} → INACTIVE，新 id={}（{} / {} 分钟）",
                current.getPriority(), id, fresh.getId(),
                dto.getResponseMinutes(), dto.getResolutionMinutes());

        // ⑥ 返回新版本（created_at 由 DB 填，getById 取回完整行）
        return toVO(slaPolicyService.getById(fresh.getId()));
    }

    // ==================== 私有 ====================

    /**
     * 校验「同一 priority 恰好一条 ACTIVE，且就是给定这一条」。
     *
     * <p>这是 §9.7 约束的<b>业务层</b>实现 —— 不依赖 {@code uk_sla_active_priority} 报错，
     * 因为数据库报错对客户端来说是一团没法解释的信息（且会变成 50000）。
     */
    private void assertSingleActive(SlaPolicy current) {
        List<SlaPolicy> actives = slaPolicyService.list(new LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getPriority, current.getPriority())
                .eq(SlaPolicy::getStatus, STATUS_ACTIVE));

        boolean exactlyThisOne = actives.size() == 1 && actives.get(0).getId().equals(current.getId());
        if (!exactlyThisOne) {
            log.error("[SLA 版本化] 优先级 {} 的 ACTIVE 版本异常：期望恰好 id={}，实际 {}",
                    current.getPriority(), current.getId(),
                    actives.stream().map(SlaPolicy::getId).toList());
            throw BizException.conflict(
                    "优先级 " + current.getPriority() + " 的生效版本数据异常，请联系管理员修复");
        }
    }

    private SlaPolicyVO toVO(SlaPolicy policy) {
        SlaPolicyVO vo = new SlaPolicyVO();
        vo.setId(policy.getId());
        vo.setPriority(policy.getPriority());
        vo.setResponseMinutes(policy.getResponseMinutes());
        vo.setResolutionMinutes(policy.getResolutionMinutes());
        vo.setStatus(policy.getStatus());
        vo.setEffectiveFrom(policy.getEffectiveFrom());
        return vo;
    }
}
