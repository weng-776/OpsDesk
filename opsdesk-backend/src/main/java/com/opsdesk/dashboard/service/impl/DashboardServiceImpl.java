package com.opsdesk.dashboard.service.impl;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.dashboard.mapper.DashboardMapper;
import com.opsdesk.dashboard.service.DashboardService;
import com.opsdesk.dashboard.vo.DashboardAgentVO;
import com.opsdesk.dashboard.vo.DashboardOverviewVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Dashboard 聚合查询实现（补单 · 只读）
 *
 * <p>规格依据：API 文档 §14.1 / §14.2、规格基线 §15.1、补单文档要点 1 ~ 7。
 *
 * <h2>本类只做三件事</h2>
 * <ol>
 *   <li><b>角色判定</b>：{@code overview} 仅 ADMIN、{@code agent} 仅 AGENT / ADMIN —— 不是 ADMIN 抛 40300</li>
 *   <li><b>算「今日」的时间边界</b>（Java 侧算，不交给数据库，见下）</li>
 *   <li><b>null → 0 兜底</b>：空表时 {@code SUM(...)} 返回 NULL，前端拿到 null 会渲染成空白 / NaN</li>
 * </ol>
 *
 * <h2>⚠️ 为什么要在这里判角色，而不是只靠 {@code @RequirePermission}</h2>
 * 种子数据里 ADMIN / AGENT / <b>EMPLOYEE 都有</b> {@code dashboard:view}
 * （补单文档「规格依据」段落明确写了，且要求不改库）。
 * 所以注解只能拦住「完全没有 dashboard 权限」的人，拦不住「有权限但不该看这个接口」的人 ——
 * 验收 #3（AGENT 调 overview → 40300）与 #4（EMPLOYEE 调任意一个 → 40300）都必须在这里兜。
 * 这与 {@code force-close} 的处理方式一致：注解管权限码，角色约束在业务层。
 *
 * <h2>⚠️ 「今日」为什么由 Java 传入，不在 SQL 里写 {@code CURDATE()}</h2>
 * 部署环境里数据库时区与应用时区一旦漂移，"今日"就会整体错位一天；而这种错只在跨零点时暴露，
 * 极难排查。项目 yml 里虽然设了 {@code serverTimezone=GMT%2b8}，但<b>统计口径不依赖这个配置</b>，
 * 边界始终由应用计算（补单要点 4）。
 *
 * <p>上界取的是<b>明日 00:00</b>（区间前闭后开），不是 {@code now}：
 * 用 {@code now} 当上界会漏掉「Java 取 now 与 SQL 执行之间新写入的那几条」（竞态窗口），
 * 而 {@code closed_at} 甚至有可能是略晚于 {@code now} 的未来毫秒。
 */
@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;

    public DashboardServiceImpl(DashboardMapper dashboardMapper) {
        this.dashboardMapper = dashboardMapper;
    }

    // ==================== 对外 ====================

    @Override
    public DashboardOverviewVO overview() {
        // §14.1「鉴权：dashboard:view + ADMIN」—— 第二个条件在这里兜
        if (!UserContext.isAdmin()) {
            log.warn("[Dashboard] 拒绝访问 overview：userId={} 非 ADMIN", UserContext.getUserId());
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        LocalDateTime dayStart = todayStart();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        DashboardOverviewVO vo = dashboardMapper.selectOverview(dayStart, dayEnd);

        // 理论上聚合查询恒返回一行；真拿到 null（驱动 / 结果集异常）也必须给前端一个能渲染的对象，
        // 不能把 null 直接塞进 Result.ok(data) —— 那会让前端在 .todayCreated 上抛异常
        if (vo == null) {
            log.warn("[Dashboard] overview 聚合查询返回空行，按全 0 处理。dayStart={}", dayStart);
            vo = new DashboardOverviewVO();
        }

        vo.setTodayCreated(nz(vo.getTodayCreated()));
        vo.setPending(nz(vo.getPending()));
        vo.setProcessing(nz(vo.getProcessing()));
        vo.setResolvedToday(nz(vo.getResolvedToday()));
        vo.setSlaBreached(nz(vo.getSlaBreached()));
        vo.setAvgResolveMinutes(vo.getAvgResolveMinutes() == null ? 0L : vo.getAvgResolveMinutes());

        return vo;
    }

    @Override
    public DashboardAgentVO agent() {
        // §14.2「鉴权：dashboard:view + AGENT / ADMIN」
        if (!UserContext.isAdmin() && !UserContext.hasRole(Role.AGENT)) {
            log.warn("[Dashboard] 拒绝访问 agent：userId={} 既非 AGENT 也非 ADMIN", UserContext.getUserId());
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        // 身份只从服务端上下文取，绝不接受前端 / LLM 传入的 userId（§11.3 同一条原则）
        Long userId = UserContext.requireUserId();

        LocalDateTime dayStart = todayStart();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        DashboardAgentVO vo = dashboardMapper.selectAgent(userId, dayStart, dayEnd);
        if (vo == null) {
            log.warn("[Dashboard] agent 聚合查询返回空行，按全 0 处理。userId={} dayStart={}", userId, dayStart);
            vo = new DashboardAgentVO();
        }

        vo.setPoolCount(nz(vo.getPoolCount()));
        vo.setMyCount(nz(vo.getMyCount()));
        vo.setWarningCount(nz(vo.getWarningCount()));
        vo.setResolvedToday(nz(vo.getResolvedToday()));

        return vo;
    }

    // ==================== 私有 ====================

    /**
     * 今日零点的应用侧计算 —— 抽成方法便于单测替换（也便于按验收 #7 把边界改到昨天来验证
     * 「边界确实是参数控制的、不是 DB 的 CURDATE()」）。
     */
    private LocalDateTime todayStart() {
        return LocalDate.now().atStartOfDay();
    }

    /** {@code null → 0}：空表时 {@code SUM(...)} 返回 NULL，这里统一兜成 0（补单要点 5 / 7） */
    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
