package com.opsdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}

