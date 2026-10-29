package com.opsdesk.dashboard.vo;

import lombok.Data;

/**
 * 管理端总览出参（API 文档 §14.1、规格基线 §15.1）
 *
 * <pre>
 * GET /api/dashboard/overview  →  data: {
 *   "todayCreated": 12, "pending": 21, "processing": 13,
 *   "resolvedToday": 8, "slaBreached": 2, "avgResolveMinutes": 180
 * }
 * </pre>
 *
 * <h2>⚠️ 本类同时被当作 MyBatis 的 {@code resultType}</h2>
 * 聚合 SQL 的列别名（{@code today_created} 等）靠
 * {@code mybatis-plus.configuration.map-underscore-to-camel-case = true} 映射到这里的字段，
 * <b>改字段名必须同步改 {@code DashboardMapper.xml} 的别名</b>，否则静默映射不上（值为 null）。
 *
 * <h2>⚠️ 为什么字段是包装类型 {@code Integer} 而不是 {@code int}</h2>
 * MySQL 的 {@code SUM(...)} 在<b>空表时返回 NULL</b>。若用基本类型，MyBatis 会给 {@code int}
 * 的默认值 {@code 0} —— 数字看起来「对」，但那不是「我们处理了 null」，而是「框架碰巧帮我们吞了」。
 * 用包装类型才能让 Service 层显式做 {@code null → 0}（补单要点 5 / 7），
 * 并且在真的拿到 null 时能被写单测断言到。
 */
@Data
public class DashboardOverviewVO {

    /** 今日新建：{@code created_at} ∈ [今日 00:00, 明日 00:00) */
    private Integer todayCreated;

    /** 待处理：{@code status IN ('OPEN','ASSIGNED')} */
    private Integer pending;

    /** 处理中：{@code status IN ('IN_PROGRESS','WAITING_USER','REOPENED')}（含挂起、重开） */
    private Integer processing;

    /** 今日已解决：{@code resolved_at} ∈ 今日 —— ⚠️ 是 {@code resolved_at}，不是 {@code closed_at} */
    private Integer resolvedToday;

    /** SLA 已超时且未关闭：{@code sla_resolution_state = 'BREACHED'} 且 {@code status NOT IN ('CLOSED','CANCELLED')} */
    private Integer slaBreached;

    /**
     * 今日平均解决时长（分钟）：今日<b>已关闭</b>工单的 {@code avg(closed_at - created_at)}。
     *
     * <p>⚠️ 用的是 {@code closed_at}（与 {@code resolvedToday} 的 {@code resolved_at} 不是同一列）。
     * <p>今日无关闭工单时为 {@code 0}，不是 {@code null}（前端要能直接渲染，补单要点 7）。
     */
    private Long avgResolveMinutes;
}
