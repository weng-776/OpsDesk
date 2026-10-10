package com.opsdesk.sla.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsdesk.common.enums.NotificationType;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.sla.service.SlaCalculator;
import com.opsdesk.sla.service.SlaScanService;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.mapper.TicketMapper;
import com.opsdesk.ticket.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SLA 定时扫描实现（工单 D5-03，SOP §5 红区）
 *
 * <p>规格依据：规格基线 §9.5（扫描与幂等）、§23.3（幂等表）、§23.4（Redis 用途清单）。
 * 判定口径不在这里 —— 收口在 {@link SlaCalculator}（D5-02）。
 *
 * <h2>一轮扫描的四步</h2>
 * <pre>
 * ① 抢 Redis 锁 lock:sla:scan（TTL 4 分钟）—— 抢不到直接返回，不阻塞不重试
 * ② 取候选：未关闭（非 CLOSED/CANCELLED）+ 未删除，按 id 做 keyset 分页
 * ③ 逐条判定（批量取策略）→ 状态有变则写库；进入 WARNING/BREACHED 且标志位为 0 则置 1
 * ④ finally 释放锁
 * </pre>
 *
 * <h2>⚠️ 为什么必须释放锁（而不是靠 TTL 自然过期）</h2>
 * 不释放的话，连续两次 {@code scan()} 的第二次会被<b>锁</b>挡住，而不是被<b>幂等标志位</b>挡住 ——
 * 「标志位真的防住了重复通知」这件事就验证不出来了（测试会变成空转）。
 * 释放掉，第二轮才能真跑，验收 1 才有意义。
 *
 * <h2>⚠️ 释放锁为什么用 Lua 做 CAS 删除</h2>
 * 万一本轮扫描超过 4 分钟、锁已过期并被另一个实例抢走，简单的 {@code del} 会把<b>别人的锁</b>删掉。
 * Lua 里先比对 value（本轮的 token）再删，就只有「锁还是我的」时才删。
 * <pre>
 * if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
 * </pre>
 *
 * <h2>⚠️ 为什么是分页而不是「一次全查」</h2>
 * 候选集在生产可能上万张工单，一次读进内存不合适。这里按 {@code id > lastId} 做 keyset 分页
 * （每页 {@value #BATCH_SIZE} 条），每页<b>一次</b>查询 + <b>一次</b>批量取策略 ——
 * 关键是<b>绝不在循环里单条查库</b>。
 *
 * <h2>⚠️ 暂停态工单跳过</h2>
 * 见 {@link SlaScanService} 类注释：§9.4 的顺延发生在 resume 时，暂停期间 {@code remaining}
 * 照常减少，不跳过就会给挂起工单误发超时通知。
 *
 * <h2>⚠️ 单条失败不影响整轮</h2>
 * 每张工单的回写各自 try/catch（记 warn + 计入 {@code failed}）—— 一行数据有问题不该让整轮扫描停摆，
 * 下一轮 5 分钟后自然重试。
 */
@Slf4j
@Service
public class SlaScanServiceImpl implements SlaScanService {

    /** 分布式锁 key（§23.3 / §23.4 固定为 {@code lock:sla:scan}） */
    public static final String LOCK_KEY = "lock:sla:scan";

    /** 锁 TTL（§23.4：4 分钟 —— 略短于 5 分钟的扫描间隔） */
    private static final Duration LOCK_TTL = Duration.ofMinutes(4);

    /** 每页条数（keyset 分页的批大小） */
    private static final int BATCH_SIZE = 500;

    /** 暂停态：§9.4 状态表里标记「暂停」的两个状态，扫描一律跳过 */
    private static final Set<TicketStatus> PAUSED_STATUSES =
            Set.of(TicketStatus.WAITING_USER, TicketStatus.WAITING_CONFIRM);

    /** 终态：不参与扫描（§9.5「扫描未关闭工单」）。从枚举的 terminal 标记推导，避免写死 */
    private static final List<TicketStatus> TERMINAL_STATUSES = Arrays.stream(TicketStatus.values())
            .filter(TicketStatus::isTerminal)
            .toList();

    /**
     * 释放锁的 Lua 脚本：只有 value 还是本轮的 token 时才删。
     *
     * <p>用 Lua 而不是 {@code get} + {@code delete} 两步 —— 两步之间存在窗口期，
     * 恰好锁过期被别人抢走时就会误删别人的锁。
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final TicketService ticketService;
    private final TicketMapper ticketMapper;
    private final SlaCalculator slaCalculator;
    private final StringRedisTemplate redisTemplate;

    public SlaScanServiceImpl(TicketService ticketService,
                              TicketMapper ticketMapper,
                              SlaCalculator slaCalculator,
                              StringRedisTemplate redisTemplate) {
        this.ticketService = ticketService;
        this.ticketMapper = ticketMapper;
        this.slaCalculator = slaCalculator;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public ScanResult scan() {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            // 不阻塞、不重试：本轮直接放弃，5 分钟后的下一轮再来
            log.info("[SLA 扫描] 未抢到分布式锁 {}，本轮跳过（另一实例正在扫描）", LOCK_KEY);
            return ScanResult.lockBusy();
        }

        log.debug("[SLA 扫描] 已获得锁 token={}", token);
        try {
            ScanResult result = doScan();
            log.info("[SLA 扫描] 完成：判定={} 暂停跳过={} 状态变更={} 预警通知={} 超时通知={} 失败={}",
                    result.scanned(), result.pausedSkipped(), result.stateChanged(),
                    result.warningNotified(), result.breachNotified(), result.failed());
            return result;
        }
        finally {
            releaseLock(token);
        }
    }

    // ==================== 私有 ====================

    /** 真正干活的部分（锁已在外面拿好） */
    private ScanResult doScan() {
        LocalDateTime now = LocalDateTime.now();

        int scanned = 0;
        int pausedSkipped = 0;
        int stateChanged = 0;
        int warningNotified = 0;
        int breachNotified = 0;
        int failed = 0;

        long cursor = 0L;
        while (true) {
            List<Ticket> page = fetchPage(cursor, BATCH_SIZE);
            if (page.isEmpty()) {
                break;
            }
            cursor = page.get(page.size() - 1).getId();

            // 先剔掉暂停态，再批量取策略 —— 不给要跳过的工单白查一次策略版本
            List<Ticket> candidates = new ArrayList<>(page.size());
            for (Ticket ticket : page) {
                if (PAUSED_STATUSES.contains(ticket.getStatus())) {
                    pausedSkipped++;
                }
                else {
                    candidates.add(ticket);
                }
            }

            if (!candidates.isEmpty()) {
                // 批量判定：本页涉及的所有策略版本一次取回（D5-02 的 computeBatch）
                Map<Long, SlaCalculator.SlaOutcome> outcomes = slaCalculator.computeBatch(candidates, now);

                for (Ticket ticket : candidates) {
                    scanned++;
                    SlaCalculator.SlaOutcome outcome = outcomes.get(ticket.getId());
                    if (outcome == null) {
                        continue;
                    }
                    try {
                        Applied applied = applyOne(ticket, outcome);
                        if (applied.stateChanged()) {
                            stateChanged++;
                        }
                        if (applied.warningNotified()) {
                            warningNotified++;
                        }
                        if (applied.breachNotified()) {
                            breachNotified++;
                        }
                    }
                    catch (Exception ex) {
                        // 单条失败不拖垮整轮：下一轮 5 分钟后自然重试
                        failed++;
                        log.warn("[SLA 扫描] 工单回写失败，跳过。ticketId={}", ticket.getId(), ex);
                    }
                }
            }

            // 取到最后一页（不足一页）就结束
            if (page.size() < BATCH_SIZE) {
                break;
            }
        }

        return new ScanResult(true, scanned, pausedSkipped, stateChanged,
                warningNotified, breachNotified, failed);
    }

    /**
     * 判定并回写一张工单。
     *
     * <p>「什么都不变就不写库」—— 一次扫描不该产生无谓的 UPDATE（否则每 5 分钟全表刷一遍）。
     */
    private Applied applyOne(Ticket ticket, SlaCalculator.SlaOutcome outcome) {
        // 无 SLA 的那条线（deadline 为空）计算结果是 null；
        // DDL 里两列是 NOT NULL，所以沿用原值，不能写 null
        SlaState newResponse = outcome.responseState() != null
                ? outcome.responseState() : ticket.getSlaResponseState();
        SlaState newResolution = outcome.resolutionState() != null
                ? outcome.resolutionState() : ticket.getSlaResolutionState();

        // 两个标志位是「工单级」的（ticket 表上就这一对，不分响应/解决）→ 任一条线命中即算
        boolean warningNow = newResponse == SlaState.WARNING || newResolution == SlaState.WARNING;
        boolean breachNow = newResponse == SlaState.BREACHED || newResolution == SlaState.BREACHED;

        // 幂等：标志位已是 1 就不再通知（§9.5「同一工单同一轮次只通知一次」）
        boolean needWarning = warningNow && !notified(ticket.getSlaWarningNotified());
        boolean needBreach = breachNow && !notified(ticket.getSlaBreachNotified());

        boolean stateChanged = newResponse != ticket.getSlaResponseState()
                || newResolution != ticket.getSlaResolutionState();

        if (!stateChanged && !needWarning && !needBreach) {
            return Applied.NOTHING;
        }

        // 只改 SLA 四列：不动 status、不 bump version（见 TicketMapper#updateSlaScan 的说明）
        ticketMapper.updateSlaScan(ticket.getId(), newResponse, newResolution, needWarning, needBreach);

        if (needWarning) {
            notifySla(ticket, NotificationType.SLA_WARNING, newResponse, newResolution);
        }
        if (needBreach) {
            notifySla(ticket, NotificationType.SLA_BREACHED, newResponse, newResolution);
        }

        return new Applied(stateChanged, needWarning, needBreach);
    }

    /**
     * 发 SLA 通知。
     *
     * <p>⚠️ <b>本单只记日志</b>：通知模块（工单 D6-03）尚未落地 ——
     * {@code NotificationService} 目前只是 {@code gen_entities.py} 生成的裸 CRUD，
     * 全项目还没有任何地方写 {@code notification} 表。按工单要求「先只写日志并留 TODO」。
     *
     * <p>「只发一次」这件事并不依赖日志：它由 {@code sla_warning_notified} /
     * {@code sla_breach_notified} 标志位保证，且本轮的新增通知条数由
     * {@link ScanResult#warningNotified()} / {@link ScanResult#breachNotified()} 返回，可被断言。
     */
    private void notifySla(Ticket ticket, NotificationType type,
                           SlaState responseState, SlaState resolutionState) {
        // TODO(D6-03): 通知模块落地后改为落库 + 推送 ——
        //   userId 取处理人（未分派则取创建人），title/content 按 NotificationType 模板生成。
        //   注意：置标志位与发通知必须在同一个事务里（本方法目前只有日志，故无需事务）。
        log.info("[SLA 通知] type={}({}) ticketId={} ticketNo={} assigneeId={} creatorId={} "
                        + "responseDeadline={} resolutionDeadline={} responseState={} resolutionState={}",
                type, type.getLabel(), ticket.getId(), ticket.getTicketNo(),
                ticket.getAssigneeId(), ticket.getCreatorId(),
                ticket.getResponseDeadline(), ticket.getResolutionDeadline(),
                responseState, resolutionState);
    }

    /** 取一页候选工单（keyset 分页：{@code id > cursor} 升序） */
    private List<Ticket> fetchPage(long cursor, int size) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                // 只取判定与回写需要的列（不 SELECT *）
                .select(Ticket::getId, Ticket::getTicketNo, Ticket::getStatus, Ticket::getSlaPolicyId,
                        Ticket::getCreatorId, Ticket::getAssigneeId,
                        Ticket::getResponseDeadline, Ticket::getResolutionDeadline,
                        Ticket::getFirstResponseAt,
                        Ticket::getSlaResponseState, Ticket::getSlaResolutionState,
                        Ticket::getSlaWarningNotified, Ticket::getSlaBreachNotified)
                .notIn(Ticket::getStatus, TERMINAL_STATUSES)
                .gt(Ticket::getId, cursor)
                .orderByAsc(Ticket::getId);
        // searchCount=false：keyset 分页不需要 COUNT(*)，否则每页会多打一条 count 查询
        return ticketService.page(new Page<>(1, size, false), wrapper).getRecords();
    }

    /** 释放锁：Lua CAS 删除，只有锁还是本轮的 token 时才删 */
    private void releaseLock(String token) {
        try {
            Long deleted = redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(LOCK_KEY), token);
            log.debug("[SLA 扫描] 释放锁 deleted={}", deleted);
        }
        catch (Exception ex) {
            // 释放失败不影响本轮结果：锁会随 TTL（4 分钟）自然过期
            log.warn("[SLA 扫描] 释放锁失败（会随 TTL {} 自然过期）", LOCK_TTL, ex);
        }
    }

    /** 幂等标志位是否已置位（DDL 默认 0，理论非空，仍做 null 兜底） */
    private boolean notified(Integer flag) {
        return flag != null && flag != 0;
    }

    /**
     * 一张工单的处置结果（私有载体，避免往 {@link #doScan()} 里塞一堆局部累加变量）。
     *
     * @param stateChanged    状态列真的变了、写了库
     * @param warningNotified 本轮新置了预警标志（= 发了一次 SLA_WARNING）
     * @param breachNotified  本轮新置了超时标志（= 发了一次 SLA_BREACHED）
     */
    private record Applied(boolean stateChanged, boolean warningNotified, boolean breachNotified) {

        /** 什么都没变：没写库、没通知 */
        static final Applied NOTHING = new Applied(false, false, false);
    }
}
