package com.opsdesk.ai.tool;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.CodeEnum;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.common.utils.EnumUtils;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import com.opsdesk.ticket.vo.TicketDetailVO;
import com.opsdesk.ticket.vo.TicketListVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * AI 可调用的工单工具集（工单 D11-01，规格基线 §11.1 / §11.2 / §11.3 / §8.4）
 *
 * <h2>🔴 原则：AI 不是权限主体，用户才是</h2>
 * 每个工具都以「当前登录用户」的身份执行并走数据范围校验（§11.3）。
 * 具体落在三件事上：
 * <ol>
 *   <li><b>入参里绝不出现身份</b> —— 没有 {@code userId} / {@code creatorId}；
 *       身份是<b>构造参数</b>，由调用方从 {@code UserContext} 捕获后传进来，
 *       <b>LLM 无从干预</b>（它只能填业务参数）；</li>
 *   <li><b>越权查询不泄露存在性</b> —— 一律返回「无权查看该工单」，与 §25.3 用例 #8 一致；</li>
 *   <li><b>权限校验在代码层</b> —— 不依赖系统提示词（提示词可被注入，代码不会）。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么这个类<b>不能</b>做成单例 {@code @Component}</h2>
 * 它承载了「当前用户」这一<b>可变状态</b>（{@link #currentUser}）。做成单例 bean 后，
 * 并发用户的请求会共用同一个实例 → <b>身份串号</b>（A 用户看到 B 的工单）。
 * 所以：由调用方<b>每次调用 new 一个</b>（D11-02 的 {@code AiChatService} 负责）。
 *
 * <h2>🔴🔴 跨线程：为什么每个工具方法都要用 {@link #withIdentity}</h2>
 * {@code UserContext} 是 {@link ThreadLocal}，由 {@code AuthInterceptor} 在<b>请求线程</b>写入。
 * 但 AI 调用走 D10-01 的出口，那个出口会把调用丢到 {@code opsdesk-ai-call} <b>线程池</b>执行 ——
 * 也就是工具方法跑在<b>另一个线程</b>上，那里 {@code UserContext} 是 {@code null}。
 *
 * <p>后果不是「工具拿不到身份」这么轻 —— 而是它调用的<b>业务服务</b>拿不到：
 * <ul>
 *   <li>{@code TicketQueryServiceImpl#detail} → {@code assertVisible(ticket, UserContext.get())}
 *       → null 被判 {@code NONE} → <b>fail-closed 40301 → 连自己的工单都查不到</b>；</li>
 *   <li>{@code TicketCreateServiceImpl#doCreate} → {@code UserContext.requireUserId()} → <b>抛 UNAUTHORIZED</b>。</li>
 * </ul>
 *
 * <p>所以把身份传进构造参数<b>只解决了一半</b>，还必须在进入业务调用前把身份放回当前线程，
 * 用完立刻清理 —— 见 {@link #withIdentity}。
 *
 * <h2>⚠️ 为什么要 catch 异常返回文案，而不是让它抛出去</h2>
 * Spring AI 的默认异常处理器（{@code DefaultToolExecutionExceptionProcessor}，{@code alwaysThrow=false}）
 * 会把异常信息当作工具返回值塞回模型 —— 那样模型看到的是 Java 异常栈，而不是「无权查看该工单」。
 * 越权、不存在这类业务异常尤其要转成人话（§11.3 明确要求返回「无权查看该工单」）。
 */
@Slf4j
public class TicketAiTools {

    /** 越权统一说辞（§11.3：不泄露资源是否存在性） */
    private static final String MSG_NO_PERMISSION = "无权查看该工单";

    /** 创建额度用尽时的统一说辞（两道闸门共用） */
    private static final String MSG_CREATE_LIMIT = "本次对话已经提交过工单了。为避免重复报修，不再创建第二张 —— "
            + "如需追加信息，建议在已有工单下补充说明。";

    /** 「我的工单」最多返回几条 —— 用户可能有几百张，全塞给模型会爆上下文 */
    private static final int MY_TICKETS_LIMIT = 10;

    /** 与 DDL / {@code TicketCreateDTO} 的长度约束保持一致 */
    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 4000;

    /** 描述字段太长会挤掉模型的上下文，只回前若干字 */
    private static final int DESCRIPTION_PREVIEW_LENGTH = 200;

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 当前用户身份（<b>构造时绑定，之后不可变</b>）。
     *
     * <p>用完整的 {@link UserContext.CurrentUser} 而不是只存一个 userId：
     * 业务层还要用 {@code departmentId}（创建工单时写部门快照）与 {@code permissions}。
     */
    private final UserContext.CurrentUser currentUser;

    private final TicketQueryService ticketQueryService;
    private final TicketCreateService ticketCreateService;

    /**
     * 防滥用闸门：一个工具实例最多创建 1 张工单。
     *
     * <p>调用方是「每次 AI 调用 new 一个实例」→ 本闸门等价于「单次调用最多创建 1 张」。
     * 真正的<b>对话级</b>限制由 {@link CreateQuota} 承担（D11-02 补：一场对话最多 1 张）。
     */
    private final AtomicBoolean createdOnce = new AtomicBoolean(false);

    /**
     * 对话级创建额度；<b>可为 null</b>（表示不做对话级限制，只有实例级闸门）。
     *
     * <p>D11-01 只有实例级上限 —— 而实例是每次请求新建的，用户在同一场对话里连问三次
     * 就能建 3 张。D11-02 起由调用方传入基于 `conversationId` 的额度（Redis SETNX + TTL）。
     */
    private final CreateQuota createQuota;

    public TicketAiTools(UserContext.CurrentUser currentUser,
                         TicketQueryService ticketQueryService,
                         TicketCreateService ticketCreateService) {
        this(currentUser, ticketQueryService, ticketCreateService, null);
    }

    public TicketAiTools(UserContext.CurrentUser currentUser,
                         TicketQueryService ticketQueryService,
                         TicketCreateService ticketCreateService,
                         CreateQuota createQuota) {
        // fail-fast：没有身份的工具体等于「匿名调用业务接口」，宁可构造时就炸
        this.currentUser = Objects.requireNonNull(currentUser,
                "currentUser 不能为空：AI 工具必须以当前登录用户身份执行（§11.3）");
        this.ticketQueryService = ticketQueryService;
        this.ticketCreateService = ticketCreateService;
        this.createQuota = createQuota;
    }

    /**
     * 对话级「最多创建 1 张工单」的额度闸门。
     *
     * <p>做成嵌套接口而不是单独一个文件：它只服务于本工具的创建动作，
     * 且实现依赖具体的会话标识（Redis key 里含 `conversationId`），放在这里语义最完整。
     */
    @FunctionalInterface
    public interface CreateQuota {

        /**
         * 尝试占用一次创建额度。
         *
         * @return {@code true} = 额度可用（本次允许创建）；{@code false} = 本对话额度已用尽
         */
        boolean tryAcquire();
    }

    // ==================== 工具 1：查单张工单 ====================

    /**
     * 查询单张工单（§11.1）。
     *
     * <p>⚠️ 工单号（{@code ticketNo}）不能用来查 —— 规格给的工具签名就是 {@code queryTicket(ticketId)}，
     * 且数据范围校验是基于 id 的。这里只接受数字 ID。
     */
    @Tool(name = "queryTicket",
            description = "按工单 ID 查询单张工单的详情（标题、状态、优先级、分类、处理人、解决时限）。"
                    + "只能查到当前用户有权查看的工单；无权查看时返回『无权查看该工单』。")
    public String queryTicket(
            @ToolParam(description = "工单 ID，必须是数字", required = true) String ticketId) {
        Long id = parseTicketId(ticketId);
        if (id == null) {
            return "工单 ID 必须是数字，你提供的是「" + safe(ticketId) + "」。";
        }
        try {
            TicketDetailVO vo = withIdentity(() -> ticketQueryService.detail(id));
            return describeTicket(vo);
        }
        catch (BizException ex) {
            return describeBizFailure(ex);
        }
        catch (RuntimeException ex) {
            log.warn("[AI 工具] queryTicket 失败 ticketId={}", id, ex);
            return "查询工单失败，请稍后重试。";
        }
    }

    // ==================== 工具 2：查我的工单 ====================

    /**
     * 查询当前用户的工单（§11.1）。
     *
     * <p>🔴 <b>刻意无参</b>：这个工具的语义是「我的」，而「我」只能由服务端的
     * {@link UserContext} 决定 —— 一旦开放参数，LLM 就能填别人的 userId（§11.3）。
     */
    @Tool(name = "queryMyTickets",
            description = "查询当前登录用户自己的工单列表（最多最近 10 条）。不需要任何参数。")
    public String queryMyTickets() {
        try {
            TicketQuery query = new TicketQuery();
            query.setPage(1L);
            query.setSize((long) MY_TICKETS_LIMIT);
            PageResult<TicketListVO> page = withIdentity(() -> ticketQueryService.mine(query));

            if (page == null || page.getList() == null || page.getList().isEmpty()) {
                return "你当前没有任何工单。";
            }
            StringBuilder text = new StringBuilder()
                    .append("你的工单（共 ").append(page.getTotal()).append(" 张，仅显示最近 ")
                    .append(page.getList().size()).append(" 条）：");
            for (TicketListVO item : page.getList()) {
                text.append("\n- ").append(item.getTicketNo())
                        .append("（ID：").append(item.getId()).append("）")
                        .append(item.getTitle())
                        .append("｜状态：").append(label(item.getStatus()))
                        .append("｜优先级：").append(label(item.getPriority()));
            }
            return text.toString();
        }
        catch (RuntimeException ex) {
            log.warn("[AI 工具] queryMyTickets 失败 userId={}", currentUser == null ? null : currentUser.userId(), ex);
            return "查询你的工单失败，请稍后重试。";
        }
    }

    // ==================== 工具 3：知识库检索（Day 12 上线） ====================

    /**
     * 知识库检索（§11.1）。
     *
     * <p>Day 12 才交付 RAG / 知识库，本单<b>只留调用点</b>并返回友好提示 ——
     * 工具是「模型可选的动作」，某个动作暂不可用时应当<b>如实告知</b>，
     * 而不是抛异常（抛异常会让整轮对话中断，用户看到的是报错）。
     */
    @Tool(name = "queryKnowledge",
            description = "检索企业内部知识库（VPN 使用手册、网络故障排查、邮箱配置等）。")
    public String queryKnowledge(
            @ToolParam(description = "检索关键词，例如「VPN 连不上」", required = true) String keyword) {
        log.debug("[AI 工具] queryKnowledge 被调用（知识库尚未上线）keyword={}", keyword);
        return "知识库检索功能尚未上线（计划 Day 12 交付），暂时无法按关键词「" + safe(keyword)
                + "」检索资料。你可以问我工单相关的问题，或者让我帮你创建一张工单。";
    }

    // ==================== 工具 4：创建工单 ====================

    /**
     * 创建工单（§11.1）。
     *
     * <p>🔴 身份落点：{@code creator_id} 来自 {@code UserContext}（由 {@code TicketCreateServiceImpl}
     * 自己取，工具不参与），{@code source} 固定 {@link TicketSource#AI_ASSISTANT}。
     *
     * <p>⚠️ 参数一律用 {@code String} 接、内部再走 {@link EnumUtils} 校验：
     * 若直接声明成 {@code TicketType} 类型，模型传了非法值时会在 <b>Jackson 参数反序列化阶段</b>就抛异常，
     * 模型拿到的是框架报错，而不是「只能是 INCIDENT / SERVICE_REQUEST」这种它能自己纠正的提示。
     */
    @Tool(name = "createTicket",
            description = "帮当前用户创建一张 IT 工单。仅在用户明确要求提交/报修工单时调用，不要凭猜测创建。")
    public String createTicket(
            @ToolParam(description = "工单标题，不超过 128 字", required = true) String title,
            @ToolParam(description = "问题描述，不超过 4000 字", required = true) String description,
            @ToolParam(description = "工单类型，只能是 INCIDENT（故障异常）或 SERVICE_REQUEST（服务请求）",
                    required = true) String type,
            @ToolParam(description = "工单分类，只能是 NETWORK / ACCOUNT / HARDWARE / SOFTWARE / EMAIL / "
                    + "PERIPHERAL / OTHER 之一", required = true) String category) {

        // ① 参数校验放在「占位」之前 —— 参数写错要让模型能改了重试，不能白白消耗掉唯一的一次机会
        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.isEmpty()) {
            return "工单标题不能为空，请先问清楚用户要报什么问题。";
        }
        if (safeTitle.length() > MAX_TITLE_LENGTH) {
            return "工单标题太长（" + safeTitle.length() + " 字），请压缩到 " + MAX_TITLE_LENGTH + " 字以内。";
        }
        String safeDescription = description == null ? "" : description.trim();
        if (safeDescription.isEmpty()) {
            return "工单描述不能为空，请先让用户补充具体现象。";
        }
        if (safeDescription.length() > MAX_DESCRIPTION_LENGTH) {
            return "工单描述太长（" + safeDescription.length() + " 字），请压缩到 "
                    + MAX_DESCRIPTION_LENGTH + " 字以内。";
        }
        Optional<TicketType> parsedType = EnumUtils.fromCode(TicketType.class, type);
        if (parsedType.isEmpty()) {
            return "工单类型不合法：「" + safe(type) + "」。只能是 " + codes(TicketType.class) + " 之一。";
        }
        Optional<TicketCategory> parsedCategory = EnumUtils.fromCode(TicketCategory.class, category);
        if (parsedCategory.isEmpty()) {
            return "工单分类不合法：「" + safe(category) + "」。只能是 " + codes(TicketCategory.class) + " 之一。";
        }

        // ② 防滥用：两道闸门，且都放在参数校验【之后】—— 参数写错不该消耗额度
        //    闸门 1（对话级）：同一场对话最多建 1 张（D11-02 补，由调用方注入基于 conversationId 的额度）
        if (createQuota != null && !createQuota.tryAcquire()) {
            return MSG_CREATE_LIMIT;
        }
        //    闸门 2（实例级）：同一实例最多建 1 张（防模型在一次回答里连续调两次）
        if (!createdOnce.compareAndSet(false, true)) {
            return MSG_CREATE_LIMIT;
        }

        // ③ 真正创建（身份与来源由服务端决定）
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setTitle(safeTitle);
        dto.setDescription(safeDescription);
        dto.setType(parsedType.get());
        dto.setCategory(parsedCategory.get());
        // 刻意不传 priority：§11.1 的工具签名里没有它，缺省走 §6.1 的 P3
        try {
            TicketCreatedVO created = withIdentity(
                    () -> ticketCreateService.create(dto, null, TicketSource.AI_ASSISTANT));
            log.info("[AI 工具] 已创建工单 id={} ticketNo={} creatorId={}",
                    created.getId(), created.getTicketNo(), currentUser == null ? null : currentUser.userId());
            return "已为你创建工单：" + created.getTicketNo() + "（ID：" + created.getId()
                    + "），状态为待受理，已进入处理队列。";
        }
        catch (BizException ex) {
            log.warn("[AI 工具] createTicket 被业务拒绝：{}", ex.getMessage());
            return "创建工单失败：" + ex.getMessage();
        }
        catch (RuntimeException ex) {
            log.error("[AI 工具] createTicket 异常", ex);
            return "创建工单失败，请稍后重试。";
        }
    }

    // ==================== 身份透传 ====================

    /**
     * 在当前（AI 线程池）线程上临时恢复登录身份，执行完立刻清理。
     *
     * <p>为什么需要它，见类注释的「跨线程」一节。两个要点：
     * <ul>
     *   <li>{@code set} 的是<b>完整</b> {@code CurrentUser} —— 业务层要用 departmentId / permissions；</li>
     *   <li>{@code clear} 在 {@code finally} 里，<b>不能省</b> ——
     *       {@code AiChatGateway} 的线程池线程是<b>复用</b>的，不清会把 A 用户的身份带到 B 用户的请求上。</li>
     * </ul>
     */
    private <T> T withIdentity(Supplier<T> action) {
        UserContext.set(currentUser);
        try {
            return action.get();
        }
        finally {
            UserContext.clear();
        }
    }

    // ==================== 结果转文本 ====================

    /**
     * 把工单详情转成「模型读得懂」的文本。
     *
     * <p>不返回 VO 对象：{@code TicketDetailVO} 有 25+ 个字段（含各种时间戳与权限标记），
     * 序列化后既占上下文又难理解。只回模型判断下一步真正需要的那些。
     */
    private String describeTicket(TicketDetailVO vo) {
        return "工单 " + vo.getTicketNo() + "（ID：" + vo.getId() + "）"
                + "\n标题：" + vo.getTitle()
                + "\n状态：" + label(vo.getStatus())
                + "\n优先级：" + label(vo.getPriority())
                + "\n分类：" + label(vo.getCategory())
                + "\n类型：" + label(vo.getType())
                + "\n处理人：" + (vo.getAssigneeName() == null ? "未分派" : vo.getAssigneeName())
                + "\n解决时限：" + (vo.getResolutionDeadline() == null
                        ? "未设置" : DATETIME.format(vo.getResolutionDeadline()))
                + "\n描述：" + truncate(vo.getDescription(), DESCRIPTION_PREVIEW_LENGTH);
    }

    /**
     * 业务异常 → 模型能理解的话。
     *
     * <p>⚠️ 越权与不存在<b>返回不同文案</b>（「无权查看该工单」vs「工单不存在」）——
     * 上游 {@code TicketQueryService#detail} 已经保证了「先判存在性（40400）再判可见性（40301）」的顺序，
     * 所以从 40301 推不出「这个 id 存在」（§2.7 / §8.4）。
     */
    private String describeBizFailure(BizException ex) {
        ErrorCode code = ex.getErrorCode();
        if (code == ErrorCode.DATA_SCOPE_DENIED) {
            return MSG_NO_PERMISSION;
        }
        if (code == ErrorCode.NOT_FOUND) {
            return "没有找到这张工单，请确认 ID 是否正确。";
        }
        return "查询失败：" + ex.getMessage();
    }

    // ==================== 小工具 ====================

    /** 解析工单 ID；非数字返回 {@code null}（不抛，让调用方给出可读提示） */
    private Long parseTicketId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 枚举 → 「中文名(CODE)」，同时给出中文与 code，模型两条线索都能用 */
    private String label(CodeEnum value) {
        if (value == null) {
            return "未知";
        }
        return value.getLabel() + "(" + ((Enum<?>) value).name() + ")";
    }

    /** 生成某枚举的全部合法 code，形如 {@code INCIDENT / SERVICE_REQUEST} */
    private <E extends Enum<E> & CodeEnum> String codes(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining(" / "));
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
