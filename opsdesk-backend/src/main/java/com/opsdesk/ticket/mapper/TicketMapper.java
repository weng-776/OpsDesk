package com.opsdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.statemachine.TicketStatePatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 工单（核心业务对象） Mapper
 *
 * <p><b>由 tools/gen_entities.py 生成</b>；简单 CRUD 直接用继承来的方法，
 * 复杂查询写在同名的 XML 或 {@code @Select} 里。
 */
@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    /**
     * 状态流转 CAS 更新（规格基线 §7.4，工单 D4-01）。
     *
     * <p>§7.4 要求「乐观锁 + 状态前置条件」双条件：
     * <pre>
     * UPDATE ticket SET status = #{to}, version = version + 1
     * WHERE id = #{id} AND status = #{from} AND version = #{version} AND deleted = 0
     * </pre>
     *
     * <h2>⚠️ 为什么不能用 {@code updateById}</h2>
     * 实体上的 {@code @Version} 只让 MP 追加 {@code version} 条件，<b>不会带 {@code status}</b>。
     * 少了 status 条件就漏掉一类并发：A、B 同时读到 {@code ASSIGNED}，
     * 但 A 先把它推进到 {@code IN_PROGRESS} —— 此时 B 的 version 若恰好也对（例如 A 的操作
     * 未改 version 的场景），B 就会在错误的状态上强行流转。带上 {@code status = #{from}}
     * 才能保证「我看到的那个状态」到现在没变过。
     *
     * <p>另外 §7.4 的 SQL 是本项目<b>第二个自定义 SQL</b>，按 SOP §5 红区必须写在
     * mapper XML 里（{@code resources/mapper/TicketMapper.xml}），不用 {@code wrapper.apply}。
     *
     * <h2>返回值</h2>
     * 影响行数。<b>0 表示 CAS 失败</b>（状态或版本已被别人改掉），调用方据此抛 {@code 40900}。
     * 这就是 §25.4 用例 #2「两人同时 accept，一人成功一人 40900」的实现基础。
     *
     * @param id              工单 id
     * @param from            期望的当前状态（不匹配则更新 0 行）
     * @param to              目标状态
     * @param version         期望的版本号（乐观锁）
     * @param assigneeId      新的处理人；{@code null} 表示不改该列
     * @param firstResponseAt 首次响应时间；{@code null} 表示不改该列（已响应过则保留首轮值，§9.6）
     * @return 影响行数（0 = CAS 失败）
     */
    int updateStatusCas(@Param("id") Long id,
                        @Param("from") TicketStatus from,
                        @Param("to") TicketStatus to,
                        @Param("version") Integer version,
                        @Param("assigneeId") Long assigneeId,
                        @Param("firstResponseAt") LocalDateTime firstResponseAt);

    /**
     * 带附加列的状态流转 CAS（工单 D4-03 引入，D4-04 扩展）。
     *
     * <p>与 {@link #updateStatusCas} 是<b>同一个 CAS 语义</b>（共用 XML 里的 {@code casWhere} 片段：
     * {@code id + status + version + deleted} 四条件，影响 0 行 = 并发冲突 → 40900），
     * 只是额外能改「SLA + 生命周期」那些列。
     *
     * <h2>为什么不复用 {@link #updateStatusCas}</h2>
     * 这些列必须能表达三种意图：「写一个值」「不写（保持原值）」「<b>置 NULL</b>」。
     * {@code <if test="x != null">} 只能表达前两种，所以附加列统一收进
     * {@link TicketStatePatch}（{@code null} = 不写），其中「置 NULL」用
     * {@code clearSlaPausedAt} 标志位单独表达。
     *
     * <h2>⚠️ 这里没有 {@code response_deadline}</h2>
     * §9.4 明确「{@code response_deadline} 不参与顺延」，{@link TicketStatePatch} 也不提供该字段 ——
     * 所以「响应时限不被改动」是结构性保证，不靠调用方自觉。
     *
     * @param patch 要写的附加列；{@code null} 的字段不写（保持原值）
     * @return 影响行数（0 = CAS 失败）
     */
    int updateStatusCasExtended(@Param("id") Long id,
                                @Param("from") TicketStatus from,
                                @Param("to") TicketStatus to,
                                @Param("version") Integer version,
                                @Param("patch") TicketStatePatch patch);

    /**
     * SLA 定时扫描的结果回写（工单 D5-03，规格基线 §9.5）。
     *
     * <p>只改 SLA 相关列，<b>不碰 {@code status}，也绝不 bump {@code version}</b>。
     *
     * <h2>⚠️ 为什么不能复用 {@link #updateStatusCasExtended}</h2>
     * 那条 SQL 会写 {@code status = #{to}} 并且 {@code version = version + 1}。
     * 扫描只是刷新<b>派生数据</b>（SLA 状态是算出来的），它：
     * <ul>
     *   <li><b>不该改状态</b> —— 状态只由 §7.2 矩阵的流转动作改变；</li>
     *   <li><b>不该 bump version</b> —— 否则每 5 分钟一次扫描就会让所有未关闭工单的
     *       {@code version} 全部前进，用户此时提交的任何流转都会 CAS 失败 → 莫名其妙的 40900。
     *       SLA 状态丢一次竞争是无害的（下一轮 5 分钟后自然纠正），不值得为它引入冲突。</li>
     * </ul>
     *
     * <h2>⚠️ 两个标志位只「置 1」不清 0</h2>
     * 用 {@code <if>} 条件追加列（而不是无条件写值），所以扫描<b>永远不会</b>把
     * {@code sla_warning_notified} / {@code sla_breach_notified} 从 1 写回 0。
     * 清零只发生在 §9.6 的 reopen（D4-04 已实现）—— 若扫描会清零，
     * 状态在 WARNING/NORMAL 之间抖动时就会反复轰炸通知。
     *
     * <h2>WHERE 条件只有 id + deleted</h2>
     * 没有 {@code status} / {@code version}：扫描要处理的就是「当前这一批」工单，
     * 不要求「我看到的状态到现在没变过」（见上）。{@code deleted = 0} 必须自己带 ——
     * 手写 SQL 绕过了 MP 的 {@code @TableLogic}。
     *
     * @param id              工单 id
     * @param responseState   响应线状态；由调用方保证非 {@code null}
     *                        （DDL 里这两列是 {@code NOT NULL}，无 SLA 的工单沿用原值）
     * @param resolutionState 解决线状态；同上
     * @param warningNotified {@code true} → 置 {@code sla_warning_notified = 1}；
     *                        {@code false} → <b>不写该列</b>（保持原值）
     * @param breachNotified  {@code true} → 置 {@code sla_breach_notified = 1}；{@code false} → 不写
     * @return 影响行数
     */
    int updateSlaScan(@Param("id") Long id,
                      @Param("responseState") SlaState responseState,
                      @Param("resolutionState") SlaState resolutionState,
                      @Param("warningNotified") boolean warningNotified,
                      @Param("breachNotified") boolean breachNotified);
}

