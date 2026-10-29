package com.opsdesk.dashboard.mapper;

import com.opsdesk.dashboard.vo.DashboardAgentVO;
import com.opsdesk.dashboard.vo.DashboardOverviewVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * Dashboard 聚合查询 Mapper（补单 · 只读聚合）
 *
 * <p>规格依据：API 文档 §14.1 / §14.2、规格基线 §15.1（指标与口径）、§21.11（接口清单）。
 *
 * <h2>⚠️ 为什么这里没有 {@code BaseMapper<T>}</h2>
 * Dashboard 不对应任何实体表 —— 它只是<b>两条跨全表的聚合查询</b>。
 * 硬套一个 {@code BaseMapper<Ticket>} 会白白带进 20 多个用不到的 CRUD 方法，
 * 还会让人误以为「Dashboard 能写工单」。所以是纯 Mapper 接口。
 *
 * <h2>⚠️ 为什么 SQL 全在 {@code resources/mapper/DashboardMapper.xml}</h2>
 * SOP §5 红区：自定义聚合 SQL 必须写在 XML 里，<b>不用 {@code wrapper.apply} 拼字符串</b>。
 * 而且这两个查询包含 {@code SUM(CASE WHEN ...)} / {@code TIMESTAMPDIFF} 这类
 * MyBatis-Plus 表达不了的写法，本来就只能用 XML。
 *
 * <h2>⚠️ 「今日」的边界一律由 Java 传入</h2>
 * 两个查询都收 {@code dayStart} / {@code dayEnd}，<b>SQL 里不出现 {@code CURDATE()} / {@code NOW()}</b>。
 * 数据库时区与应用时区一旦不一致，"今日"就会错位，且这种错误只在跨零点时暴露、极难排查
 * （补单要点 4）。注意这里传的是<b>前闭后开</b>区间 {@code [dayStart, dayEnd)}，
 * 不是「今日 00:00 ~ now」—— 用 {@code now} 当上界会漏掉「同一毫秒内写入」的边界情况，
 * 而 {@code closed_at} 甚至可能略晚于 Java 取到的 {@code now}。
 */
@Mapper
public interface DashboardMapper {

    /**
     * 管理端总览的 6 个指标 —— <b>一条聚合 SQL 出全部</b>（补单要点 5：不要 6 次 {@code count(*)}）。
     *
     * <p>过滤条件只有 {@code deleted = 0}：{@code overview} 是<b>全企业</b>统计，
     * <b>不加任何数据范围条件</b>（补单要点 1）。
     *
     * <p>⚠️ 空表时返回<b>一行全 NULL</b>（不带 {@code GROUP BY} 的聚合查询恒返回一行），
     * 由 Service 层做 {@code null → 0}。
     *
     * @param dayStart 今日起点（含），由 Service 用 {@code LocalDate.now().atStartOfDay()} 传入
     * @param dayEnd   明日起点（不含）
     */
    DashboardOverviewVO selectOverview(@Param("dayStart") LocalDateTime dayStart,
                                       @Param("dayEnd") LocalDateTime dayEnd);

    /**
     * AGENT 工作台的 4 个指标。
     *
     * <p>四个指标<b>都在当前用户视角</b>（补单要点 1）：
     * {@code poolCount} 与用户无关（所有人都一样），另外三个都是 {@code assignee_id = #{userId}}。
     *
     * <p>{@code poolCount} 用标量子查询在同一条 SQL 里取（补单 §5 允许），
     * 省一次往返 —— 它不带 {@code GROUP BY}，不会把结果集打散。
     *
     * @param userId   当前用户 id（{@code UserContext.requireUserId()}）
     * @param dayStart 今日起点（含）
     * @param dayEnd   明日起点（不含）
     */
    DashboardAgentVO selectAgent(@Param("userId") Long userId,
                                 @Param("dayStart") LocalDateTime dayStart,
                                 @Param("dayEnd") LocalDateTime dayEnd);
}
