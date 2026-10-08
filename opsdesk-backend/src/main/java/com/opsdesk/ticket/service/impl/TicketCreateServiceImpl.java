package com.opsdesk.ticket.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.OutboxStatus;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.entity.EventOutbox;
import com.opsdesk.mq.service.EventOutboxService;
import com.opsdesk.sla.service.TicketSlaCalculator;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.entity.TicketHistory;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketHistoryService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.support.IdempotencyGuard;
import com.opsdesk.ticket.support.TicketNoGenerator;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 创建工单实现（工单 D3-01，SOP §5 红区：幂等 + 事务边界）
 *
 * <p>规格依据：API 文档 §8.1（字段与服务端行为）、规格基线 §5.3（工单号）/ §6.1（创建规则）/
 * §9.2（SLA 起算点）/ §13.5（Outbox）/ §23.3（幂等）/ §17.1（流程时序）。
 *
 * <h2>事务内时序（§8.1「服务端行为」逐条照做）</h2>
 * <pre>
 * ① 幂等占位（Redis，不是事务资源 —— 靠事务回调补偿）
 * ② 工单号生成 → INSERT ticket
 * ③ selectById 取回 created_at（MP 的 insert 不回填 DB 默认值）
 * ④ 读 ACTIVE 策略算 deadline → UPDATE 工单的 SLA 字段
 * ⑤ INSERT ticket_history（action = CREATE）
 * ⑥ INSERT event_outbox（status = PENDING）
 * </pre>
 *
 * <h2>⚠️ 为什么必须「先 INSERT 再 UPDATE」，而不是一次 INSERT 写全</h2>
 * §9.2 的权威口径是「deadline 从 {@code created_at} 起算」，而 {@code created_at} 由
 * <b>数据库</b>填（实体的 {@code insertStrategy = NEVER}），MyBatis-Plus 的 insert
 * <b>不会把它回填到对象</b>（MySQL 没有 RETURNING，见 {@code DataLayerTest} 的注释）。
 * 所以只能插完再查回来算。不用 {@code LocalDateTime.now()} 顶替 —— 应用时钟与 DB 时钟
 * 可能差几毫秒，而 SLA 是要拿去判超时的。
 *
 * <h2>⚠️ 事务回调为什么这么写</h2>
 * 幂等记录是 Redis（非事务资源），必须自己补偿：
 * <ul>
 *   <li><b>提交后</b>才写首次结果 —— 提前写的话，事务一旦回滚，幂等记录里就留下了一张
 *       <b>根本不存在的工单</b>，后续重复提交会一直拿到这个假结果</li>
 *   <li><b>回滚后</b>释放占位 —— 不释放的话，用户 24 小时内重试全被挡死，比没有幂等还糟</li>
 * </ul>
 * 结果值用 {@link AtomicReference} 传给回调（注册回调时 {@code doCreate} 还没执行）。
 *
 * <h2>⚠️ 事务内 catch 异常重试是安全的（已核实）</h2>
 * 取号后插入可能撞 {@code uk_ticket_no}（Redis 丢数据时日序列会归零重来），所以要重试。
 * 在事务内 catch {@link DuplicateKeyException} 重试<b>不会</b>把事务标成 rollback-only ——
 * 前提是抛异常的那个方法自己没有 {@code @Transactional} 代理边界。
 * 已反编译核实：{@code IService.save(T)} <b>没有</b> {@code @Transactional}，
 * 而 {@code IService.saveBatch(Collection)} <b>有</b> ——
 * <b>所以这里只能用 {@code save()}，换成 {@code saveBatch()} 就会踩坑</b>
 * （异常穿过它的代理 → 事务被标 rollback-only → 后续语句全部失败）。
 */
@Slf4j
@Service
public class TicketCreateServiceImpl implements TicketCreateService {

    /** 工单号冲突重试上限（§5.3：重试 3 次） */
    private static final int MAX_TICKET_NO_ATTEMPTS = 3;

    /** Outbox 事件类型（§13.3） */
    private static final String EVENT_TYPE_TICKET_CREATED = "TicketCreatedEvent";

    /** 幂等键被占且首次未完成时的提示 */
    private static final String MSG_IN_PROGRESS = "同一请求正在处理中，请勿重复提交";

    /** 创建时未指定优先级则用 P3（§6.1） */
    private static final TicketPriority DEFAULT_PRIORITY = TicketPriority.P3;

    /**
     * 本入口的来源固定为 WEB（§3.9）。
     *
     * <p>API 文档 §8.1 的请求字段表里<b>没有</b> {@code source} —— 也就是说来源不由客户端指定，
     * 而是「按入口写入」（§6.1）。{@code POST /api/tickets} 是 Web 入口 → {@code WEB}；
     * {@code AI_ASSISTANT} / {@code ADMIN} 是别的调用方（AI 工具、管理端代提）。
     */
    private static final TicketSource ENTRY_SOURCE = TicketSource.WEB;

    private final TicketService ticketService;
    private final TicketHistoryService ticketHistoryService;
    private final EventOutboxService eventOutboxService;
    private final TicketNoGenerator ticketNoGenerator;
    private final TicketSlaCalculator ticketSlaCalculator;
    private final IdempotencyGuard idempotencyGuard;
    private final ObjectMapper objectMapper;

    public TicketCreateServiceImpl(TicketService ticketService,
                                   TicketHistoryService ticketHistoryService,
                                   EventOutboxService eventOutboxService,
                                   TicketNoGenerator ticketNoGenerator,
                                   TicketSlaCalculator ticketSlaCalculator,
                                   IdempotencyGuard idempotencyGuard,
                                   ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.ticketHistoryService = ticketHistoryService;
        this.eventOutboxService = eventOutboxService;
        this.ticketNoGenerator = ticketNoGenerator;
        this.ticketSlaCalculator = ticketSlaCalculator;
        this.idempotencyGuard = idempotencyGuard;
        this.objectMapper = objectMapper;
    }

    // ==================== 入口 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketCreatedVO create(TicketCreateDTO dto, String idempotencyKey) {
        boolean idempotent = StringUtils.hasText(idempotencyKey);
        AtomicReference<TicketCreatedVO> resultHolder = new AtomicReference<>();

        if (idempotent) {
            IdempotencyGuard.Reservation reservation = idempotencyGuard.reserve(idempotencyKey);
            switch (reservation.status()) {
                case CACHED -> {
                    log.info("[创建工单] 幂等命中，返回首次结果。key={} ticketNo={}",
                            idempotencyKey, reservation.cached().getTicketNo());
                    return reservation.cached();
                }
                case IN_PROGRESS -> throw new BizException(ErrorCode.CONFLICT, MSG_IN_PROGRESS);
                case FIRST -> registerIdempotencyCallback(idempotencyKey, resultHolder);
            }
        }

        // ⚠️ 私有方法、且**不带** @Transactional：与 create() 同一个事务，
        //    也不存在「自调用导致事务失效」的问题（那种坑出现在自己调自己的 @Transactional 方法上）
        TicketCreatedVO result = doCreate(dto);

        resultHolder.set(result);
        return result;
    }

    /**
     * 注册「提交后写结果 / 回滚后释放占位」的事务回调。
     *
     * <p>用 {@code afterCompletion} 一个方法同时覆盖两种情况（它会在提交/回滚之后被调用，
     * 且一定晚于 {@code afterCommit}）。结果值经 {@code holder} 传进来。
     */
    private void registerIdempotencyCallback(String idempotencyKey,
                                            AtomicReference<TicketCreatedVO> resultHolder) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    TicketCreatedVO created = resultHolder.get();
                    if (created != null) {
                        idempotencyGuard.complete(idempotencyKey, created);
                    }
                }
                else {
                    idempotencyGuard.release(idempotencyKey);
                }
            }
        });
    }

    // ==================== 事务内的实际创建 ====================

    private TicketCreatedVO doCreate(TicketCreateDTO dto) {
        Long creatorId = UserContext.requireUserId();

        Ticket ticket = new Ticket();
        ticket.setTitle(dto.getTitle().trim());
        ticket.setDescription(dto.getDescription());
        ticket.setType(dto.getType());
        ticket.setCategory(dto.getCategory());
        ticket.setPriority(dto.getPriority() == null ? DEFAULT_PRIORITY : dto.getPriority());
        // ⚠️ 这里直接写 status，不走状态机 —— **这是创建，不是流转**，两者别混：
        //    · §6.1 明确要求创建时写入 `status = OPEN`
        //    · §7.2 的「状态 × 角色 × 动作矩阵」管的是**已存在工单**的动作（assign/accept/close…），
        //      那些必须走 D4-01 的 TicketStateMachine（带 `AND status = #{from}` + 乐观锁）
        //    · 项目审查脚本的 M1「直接 setStatus」会把这一行报出来 —— 人工判定为**误报**，
        //      判断依据就是上面两条。真正要拦的是「对已存在的工单直接改状态」。
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setSource(ENTRY_SOURCE);
        ticket.setCreatorId(creatorId);
        // 创建人所属部门「快照」（§6.1）—— 之后用户换部门也不改这张单的历史归属。
        // 直接从 UserContext 取：它由鉴权拦截器从权限缓存里带出来（D2-04 优化），零查询
        ticket.setDepartmentId(departmentIdOfCurrentUser());
        // assignee_id 保持 null → 进公共待领取池（§8.3 的 ② 靠它才能被所有 AGENT 看到）

        insertWithTicketNoRetry(ticket);

        // 取回 DB 填的 created_at（见类注释）
        Ticket saved = ticketService.getById(ticket.getId());
        if (saved == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "工单落库后查不到，请重试");
        }

        applySla(saved);
        writeHistory(saved, creatorId);
        writeOutbox(saved);

        log.info("[创建工单] id={} ticketNo={} creatorId={} departmentId={} priority={} status={}",
                saved.getId(), saved.getTicketNo(), creatorId, saved.getDepartmentId(),
                saved.getPriority(), saved.getStatus());
        return new TicketCreatedVO(saved.getId(), saved.getTicketNo());
    }

    /**
     * 取号 + 插入，工单号冲突时重试。
     *
     * <p>唯一的冲突来源是 {@code uk_ticket_no}：这张 INSERT 上就只有这一个唯一索引
     * （{@code id} 是自增主键，不参与），所以撞 {@link DuplicateKeyException} 必然是工单号重复，
     * <b>不需要解析异常消息去判断是哪个索引</b>（那样反而依赖 MySQL 的报错文案）。
     */
    private void insertWithTicketNoRetry(Ticket ticket) {
        for (int attempt = 1; attempt <= MAX_TICKET_NO_ATTEMPTS; attempt++) {
            ticket.setTicketNo(ticketNoGenerator.next());
            try {
                // ⚠️ 必须是 save()，不能换成 saveBatch()（见类注释）
                ticketService.save(ticket);
                if (attempt > 1) {
                    log.warn("[创建工单] 工单号第 {} 次尝试成功。ticketNo={}", attempt, ticket.getTicketNo());
                }
                return;
            }
            catch (DuplicateKeyException ex) {
                if (attempt == MAX_TICKET_NO_ATTEMPTS) {
                    log.error("[创建工单] 工单号连续 {} 次冲突，放弃。lastTicketNo={}",
                            MAX_TICKET_NO_ATTEMPTS, ticket.getTicketNo());
                    throw new BizException(ErrorCode.CONFLICT, "工单号生成冲突，请重试");
                }
                log.warn("[创建工单] 工单号冲突（第 {} 次尝试）ticketNo={}，重新取号重试",
                        attempt, ticket.getTicketNo());
            }
        }
    }

    /**
     * 按优先级算 SLA 并落库（§6.1 / §9.2）。
     *
     * <p>⚠️ <b>没有 ACTIVE 策略时不阻断创建</b>，只留空 SLA 字段 + WARN。
     * 理由：§6.1 明确「AI 只提供建议，不参与主流程的必经路径」——
     * 配置缺失同理不该让员工提不了单；工单本身是有价值的，SLA 可以事后补。
     * （种子里 P1/P2/P3 都有 ACTIVE 策略，正常走不到这一支。）
     */
    private void applySla(Ticket saved) {
        TicketSlaCalculator.SlaSnapshot snapshot =
                ticketSlaCalculator.calculate(saved.getPriority(), saved.getCreatedAt());
        if (snapshot == null) {
            log.warn("[创建工单] 优先级 {} 没有 ACTIVE 的 SLA 策略，SLA 字段留空。ticketNo={}",
                    saved.getPriority(), saved.getTicketNo());
            return;
        }

        Ticket update = new Ticket();
        update.setId(saved.getId());
        update.setSlaPolicyId(snapshot.policyId());
        update.setResponseDeadline(snapshot.responseDeadline());
        update.setResolutionDeadline(snapshot.resolutionDeadline());
        ticketService.updateById(update);

        log.info("[创建工单] SLA 已算：ticketNo={} policyId={} responseDeadline={} resolutionDeadline={}",
                saved.getTicketNo(), snapshot.policyId(),
                snapshot.responseDeadline(), snapshot.resolutionDeadline());
    }

    /** 写工单历史：创建没有前态，to_status = OPEN（§7.5） */
    private void writeHistory(Ticket saved, Long creatorId) {
        TicketHistory history = new TicketHistory();
        history.setTicketId(saved.getId());
        history.setOperatorId(creatorId);
        history.setAction(TicketHistoryAction.CREATE);
        history.setFromStatus(null);
        history.setToStatus(TicketStatus.OPEN);
        history.setRemark("创建工单");
        ticketHistoryService.save(history);
    }

    /**
     * 写本地消息表（§13.5）：业务事务内落一行 PENDING。
     *
     * <p>投递由定时任务负责（扫描 PENDING → 发 MQ → 置 SENT），
     * <b>不在本单范围</b>（工单 D3-01 只要求「同事务写 event_outbox」）。
     */
    private void writeOutbox(Ticket saved) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketId", saved.getId());
        payload.put("ticketNo", saved.getTicketNo());
        payload.put("creatorId", saved.getCreatorId());
        payload.put("departmentId", saved.getDepartmentId());
        payload.put("priority", saved.getPriority() == null ? null : saved.getPriority().name());
        payload.put("status", saved.getStatus() == null ? null : saved.getStatus().name());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "事件载荷序列化失败", ex);
        }

        EventOutbox outbox = new EventOutbox();
        outbox.setEventType(EVENT_TYPE_TICKET_CREATED);
        // messageId 是消费端幂等键（§13.6），必须唯一（uk_outbox_message_id）
        outbox.setMessageId(UUID.randomUUID().toString());
        // 路由键复用 RabbitMQConfig 的常量，不写裸字符串
        outbox.setRoutingKey(RabbitMQConfig.RK_TICKET_CREATED);
        outbox.setPayload(payloadJson);
        outbox.setStatus(OutboxStatus.PENDING);
        eventOutboxService.save(outbox);
    }

    /** 当前登录用户的部门（快照用）；未设部门返回 null（DDL 允许 department_id 为空） */
    private Long departmentIdOfCurrentUser() {
        UserContext.CurrentUser user = UserContext.get();
        return user == null ? null : user.departmentId();
    }
}
