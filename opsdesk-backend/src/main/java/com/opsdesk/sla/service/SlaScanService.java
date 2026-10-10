package com.opsdesk.sla.service;

import com.opsdesk.common.enums.NotificationType;

/**
 * SLA 定时扫描（工单 D5-03，SOP §5 红区：定时扫描 / 超时标记）
 *
 * <p>规格依据：规格基线 <b>§9.5（临近超时与超时判定 + 扫描与幂等）</b>、
 * §23.3（幂等表）、§23.4（Redis 用途清单）、§3.10（{@link NotificationType}）。
 *
 * <h2>§9.5 原文（扫描部分）</h2>
 * <pre>
 * 定时任务每 5 分钟扫描一次未关闭工单：
 *   · 分别计算 sla_response_state 与 sla_resolution_state
 *   · 到达 WARNING  时发 SLA_WARNING  通知，置 sla_warning_notified = 1
 *   · 到达 BREACHED 时发 SLA_BREACHED 通知，置 sla_breach_notified = 1
 *   · 幂等：标志位保证同一工单同一轮次只通知一次
 *   · 扫描任务加 Redis 分布式锁 lock:sla:scan（TTL 4 分钟），防止多实例重复扫描
 * </pre>
 *
 * <h2>本接口的职责边界</h2>
 * <ul>
 *   <li><b>只做「扫描一轮」这一件事</b>：加锁 → 取候选 → 判定 → 回写状态与标志位 → 释放锁。
 *       触发时机（{@code @Scheduled}）在 {@code sla/job/SlaScanJob}，不在这里 ——
 *       这样扫描逻辑可以被测试直接调用，不依赖调度器。</li>
 *   <li><b>判定本身不在这里</b>：口径收口在 {@link SlaCalculator}（D5-02）。
 *       本接口只负责「取数 → 调用它 → 回写」。</li>
 *   <li><b>通知只记日志</b>：通知模块（D6-03）尚未落地（{@code NotificationService} 目前
 *       只是 {@code gen_entities.py} 生成的裸 CRUD，全项目还没有任何地方写 {@code notification} 表），
 *       所以本单按工单要求「先只写日志并留 TODO」，见 {@code SlaScanServiceImpl#notifySla}。</li>
 * </ul>
 *
 * <h2>⚠️ 暂停态工单必须跳过</h2>
 * §9.4 的顺延是<b>在退出暂停（resume）时一次性加 delta</b>，所以暂停期间
 * {@code resolution_deadline} 还没被推后、{@code remaining} 照常减少 ——
 * 不跳过的话，一张挂起 3 天的工单会在挂起期间被判 {@code BREACHED} 并发出超时通知，
 * 而 §9.4 的立场是「等待员工期间不应由 IT 背锅」。
 * 所以 {@code WAITING_USER} / {@code WAITING_CONFIRM} 一律跳过（见 {@code SlaCalculator} 类注释）。
 */
public interface SlaScanService {

    /**
     * 执行一轮扫描。
     *
     * <p><b>不抛异常</b>：拿不到锁、单条工单回写失败都不该让整轮失败 ——
     * 失败信息进 {@link ScanResult} 与日志，下一轮（5 分钟后）自然重试。
     *
     * @return 本轮统计；没抢到锁时 {@link ScanResult#lockBusy()}
     */
    ScanResult scan();

    /**
     * 一轮扫描的统计。
     *
     * <p>这些计数不只是日志装饰 —— 它们是「幂等是否生效」的<b>可观察证据</b>：
     * 第一轮 {@code warningNotified = 1}、第二轮 {@code warningNotified = 0}，
     * 就证明了标志位真的挡住了重复通知（比数日志行数可靠）。
     *
     * @param lockAcquired    是否抢到分布式锁；{@code false} 表示本轮被别的实例占用、直接跳过
     * @param scanned         本轮实际判定过的工单数（不含暂停态与终态）
     * @param pausedSkipped   因处于暂停态（{@code WAITING_USER} / {@code WAITING_CONFIRM}）而跳过的工单数
     * @param stateChanged    状态发生变化、真正写了库的工单数
     * @param warningNotified 本轮<b>新</b>触发 {@code SLA_WARNING} 的工单数（标志位 0 → 1）
     * @param breachNotified  本轮<b>新</b>触发 {@code SLA_BREACHED} 的工单数（标志位 0 → 1）
     * @param failed          单条回写失败被跳过的工单数（记 warn，不影响其余工单）
     */
    record ScanResult(boolean lockAcquired,
                      int scanned,
                      int pausedSkipped,
                      int stateChanged,
                      int warningNotified,
                      int breachNotified,
                      int failed) {

        /** 没抢到锁：本轮什么都没做 */
        public static ScanResult lockBusy() {
            return new ScanResult(false, 0, 0, 0, 0, 0, 0);
        }
    }
}
