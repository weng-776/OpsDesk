package com.opsdesk.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.ticket.entity.Ticket;
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
}

