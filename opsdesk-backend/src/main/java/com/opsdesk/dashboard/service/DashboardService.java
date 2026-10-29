package com.opsdesk.dashboard.service;

import com.opsdesk.dashboard.vo.DashboardAgentVO;
import com.opsdesk.dashboard.vo.DashboardOverviewVO;

/**
 * Dashboard 聚合查询（补单 · 只读）
 *
 * <p>规格依据：API 文档 §14（§14.1 管理端总览 / §14.2 AGENT 工作台）、
 * 规格基线 §15.1（指标与口径）、§21.11（接口清单）。
 *
 * <h2>两个接口的数据口径<b>完全不同</b>，不要试图抽公共方法</h2>
 * <ul>
 *   <li>{@link #overview} —— <b>全企业</b>统计，不加任何数据范围条件</li>
 *   <li>{@link #agent} —— <b>当前用户视角</b>，全部指标围绕 {@code assignee_id = 当前用户} 或公共池</li>
 * </ul>
 * 把两者「统一」成一套过滤条件，就是补单文档明确禁止的第 3 条。
 *
 * <h2>本模块是纯只读</h2>
 * 不写库、不发 MQ、不加 Redis 缓存（补单要点 6：单条聚合 SQL 在万级数据下是毫秒级，
 * 加缓存就要处理失效，收益不抵复杂度；将来真成热点了再在实现类外面包一层，接口契约不变）。
 */
public interface DashboardService {

    /**
     * 管理端总览（API §14.1）—— <b>仅 ADMIN</b>。
     *
     * @return 6 个指标，全部非 null（无数据时为 0）
     * @throws com.opsdesk.common.BizException {@code 40300} 当前用户不是 ADMIN
     *         （注意：AGENT / EMPLOYEE 也持有 {@code dashboard:view} 权限码，
     *         所以「不是 ADMIN」必须在这里判，光靠注解拦不住）
     */
    DashboardOverviewVO overview();

    /**
     * AGENT 工作台（API §14.2）—— <b>AGENT / ADMIN</b>。
     *
     * <p>ADMIN 调它时，{@code myCount} 等字段是 ADMIN <b>自己名下</b>的工单，不是全企业的
     * （§14.2 就是这么定义的，别「顺便」给 ADMIN 返回全局数字）。
     *
     * @return 4 个指标，全部非 null（无数据时为 0）
     * @throws com.opsdesk.common.BizException {@code 40300} 当前用户既不是 AGENT 也不是 ADMIN
     */
    DashboardAgentVO agent();
}
