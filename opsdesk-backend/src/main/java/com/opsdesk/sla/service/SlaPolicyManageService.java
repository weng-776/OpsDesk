package com.opsdesk.sla.service;

import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.sla.dto.SlaPolicyUpdateDTO;
import com.opsdesk.sla.vo.SlaPolicyVO;

import java.util.List;

/**
 * SLA 策略管理（工单 D5-01，SOP §5 红区：SLA）
 *
 * <p>规格依据：规格基线 §9.1（策略表字段）、<b>§9.7（策略变更规则：版本化而非原地修改）</b>；
 * API 文档 §9.1 / §9.2 / §9.3 / §16.13。
 *
 * <h2>⚠️ 为什么不复用 {@code SlaPolicyService}</h2>
 * {@code SlaPolicyService} 是 {@code gen_entities.py} 生成的 {@code IService<SlaPolicy>}
 * （表 CRUD），按项目约定（D3-04 明确）业务方法不加在生成物上，而是另立接口。
 *
 * <h2>本接口的核心语义：修改 ≠ UPDATE</h2>
 * {@link #update} <b>不原地改</b>，而是「旧版本置 {@code INACTIVE} + 新增一条 {@code ACTIVE}」。
 * 存量工单的 {@code ticket.sla_policy_id} 仍指向旧行 → 口径不变、可追溯（§9.7）。
 */
public interface SlaPolicyManageService {

    /**
     * 策略列表（API 文档 §9.1）。
     *
     * <p>版本化之后同一 {@code priority} 会有多行（一条 ACTIVE + 若干历史 INACTIVE），
     * 所以这个列表天然是「版本历史」视图。
     *
     * @param priority 按优先级过滤；{@code null} 表示不过滤
     * @param status   按状态过滤（{@code ACTIVE} / {@code INACTIVE}）；空表示不过滤
     */
    List<SlaPolicyVO> list(TicketPriority priority, String status);

    /**
     * 策略详情（API 文档 §9.2）。
     *
     * @throws com.opsdesk.common.BizException 不存在（40400）
     */
    SlaPolicyVO detail(Long id);

    /**
     * 修改 SLA 策略（API 文档 §9.3）—— <b>版本化修改</b>。
     *
     * <p>语义（§9.7）：
     * <pre>
     * 旧版本 status = INACTIVE
     * 新版本 status = ACTIVE，effective_from = now
     * </pre>
     * 返回<b>新版本</b>。
     *
     * <p>⚠️ 顺序必须是「先停旧、再插新」：反过来的话，那一瞬间会有两条 ACTIVE，
     * 唯一索引 {@code uk_sla_active_priority} 会直接拒绝。
     *
     * @throws com.opsdesk.common.BizException 不存在（40400）；
     *                     目标不是 ACTIVE 版本、或该优先级的 ACTIVE 版本数异常（40900）；
     *                     解决时限 &lt; 响应时限（40001）
     */
    SlaPolicyVO update(Long id, SlaPolicyUpdateDTO dto);
}
