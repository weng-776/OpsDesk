package com.opsdesk.ticket.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.ticket.dto.CommentCreateDTO;
import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.dto.TicketCancelDTO;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.service.AttachmentService;
import com.opsdesk.ticket.service.TicketCommentBizService;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketFlowService;
import com.opsdesk.ticket.service.TicketHistoryBizService;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.support.IdempotencyGuard;
import com.opsdesk.ticket.vo.AttachmentUploadVO;
import com.opsdesk.ticket.vo.AttachmentVO;
import com.opsdesk.ticket.vo.TicketCommentVO;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import com.opsdesk.ticket.vo.TicketDetailVO;
import com.opsdesk.ticket.vo.TicketHistoryVO;
import com.opsdesk.ticket.vo.TicketListVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 工单接口（API 文档 §8）
 *
 * <p>D3-01 创建、D3-02 列表与我的工单、D3-03 详情、D3-04 评论、D3-05 附件上传与列表；
 * 状态流转归 D4 系列。
 *
 * <p>⚠️ 附件<b>下载</b>不在这里 —— 它是 {@code GET /api/attachments/{id}/download}，
 * 路径前缀是 {@code /api/attachments} 而不是 {@code /api/tickets}，
 * 见 {@link AttachmentController}。
 *
 * <p>鉴权：需登录（{@code AuthInterceptor}）+ 权限码（{@code ticket:create} / {@code ticket:list}）。
 * 数据范围由 {@code TicketDataScopeHelper} 在 service 层叠加，Controller 不参与。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketCreateService ticketCreateService;
    private final TicketQueryService ticketQueryService;
    private final TicketCommentBizService ticketCommentBizService;
    private final AttachmentService attachmentService;
    private final TicketFlowService ticketFlowService;
    private final TicketHistoryBizService ticketHistoryBizService;

    public TicketController(TicketCreateService ticketCreateService,
                            TicketQueryService ticketQueryService,
                            TicketCommentBizService ticketCommentBizService,
                            AttachmentService attachmentService,
                            TicketFlowService ticketFlowService,
                            TicketHistoryBizService ticketHistoryBizService) {
        this.ticketCreateService = ticketCreateService;
        this.ticketQueryService = ticketQueryService;
        this.ticketCommentBizService = ticketCommentBizService;
        this.attachmentService = attachmentService;
        this.ticketFlowService = ticketFlowService;
        this.ticketHistoryBizService = ticketHistoryBizService;
    }

    /**
     * 创建工单（§8.1）。
     *
     * <p>幂等：可选请求头 {@code Idempotency-Key}（§2.5 / §23.3）。
     * 带该头时，重复提交会返回<b>首次的响应体</b>，而不是再建一张单。
     *
     * @param idempotencyKey 声明 {@code required = false} —— §8.1 说「支持」而非「必填」
     */
    @RequirePermission(PermissionCodes.TICKET_CREATE)
    @PostMapping
    public Result<TicketCreatedVO> create(
            @Valid @RequestBody TicketCreateDTO dto,
            @RequestHeader(value = IdempotencyGuard.HEADER_NAME, required = false) String idempotencyKey) {
        return Result.ok(ticketCreateService.create(dto, idempotencyKey));
    }

    /**
     * 工单列表（§8.3）—— 数据范围按角色自动叠加。
     *
     * <p>⚠️ 路径顺序无关，但 {@code /mine} 是字面量路径，Spring 会优先匹配它而不是
     * 将来的 {@code /{id}}，不会被吃掉。
     */
    @RequirePermission(PermissionCodes.TICKET_LIST)
    @GetMapping
    public Result<PageResult<TicketListVO>> page(@Valid TicketQuery query) {
        return Result.ok(ticketQueryService.page(query));
    }

    /**
     * 我的工单（§8.2）—— <b>只看自己创建的</b>（{@code creator_id = 当前用户}）。
     *
     * <p>注意它<b>不走角色数据范围</b>：§8.2 抬头明确写「数据范围：SELF」，
     * 所以 ADMIN 调它也只能看到自己创建的工单。
     */
    @RequirePermission(PermissionCodes.TICKET_LIST)
    @GetMapping("/mine")
    public Result<PageResult<TicketListVO>> mine(@Valid TicketQuery query) {
        return Result.ok(ticketQueryService.mine(query));
    }

    /**
     * 工单详情（§8.4）。
     *
     * <p>鉴权 {@code ticket:detail}；数据范围是<b>校验</b>而非过滤 —— 不可见返回 {@code 40301}，
     * 不存在返回 {@code 40400}（两者不能混，见 service 里的顺序说明）。
     *
     * <p>路径 {@code /{id}} 排在 {@code /mine} 之后注册，Spring 会优先匹配字面量路径
     * {@code /mine}，不会被 {@code /{id}} 吃掉。
     */
    @RequirePermission(PermissionCodes.TICKET_DETAIL)
    @GetMapping("/{id}")
    public Result<TicketDetailVO> detail(@PathVariable Long id) {
        return Result.ok(ticketQueryService.detail(id));
    }

    /**
     * 评论列表（§8.7）—— 分页。
     *
     * <p>鉴权 {@code ticket:comment}（按 §22 的权限矩阵，评论的读写用同一个权限码）。
     * EMPLOYEE 查不到 {@code internal = 1} 的内部备注（SQL 层过滤，§8.7）。
     *
     * <p>⚠️ 路径 {@code /{id}/comments} 比 {@code /{id}} 多一段，不会与详情冲突。
     */
    @RequirePermission(PermissionCodes.TICKET_COMMENT)
    @GetMapping("/{id}/comments")
    public Result<PageResult<TicketCommentVO>> comments(@PathVariable Long id,
                                                        @Valid TicketQuery query) {
        return Result.ok(ticketCommentBizService.list(id, query));
    }

    /**
     * 添加评论（§8.8）。
     *
     * <p>同事务写评论 + {@code ticket_history}（{@code action = COMMENT}）。
     *
     * @param dto {@code internal} 仅 AGENT / ADMIN 可设为 {@code true}，
     *            EMPLOYEE 传 {@code true} 会被静默降级为 {@code false}（§8.8 + 已与用户确认）
     */
    @RequirePermission(PermissionCodes.TICKET_COMMENT)
    @PostMapping("/{id}/comments")
    public Result<TicketCommentVO> addComment(@PathVariable Long id,
                                              @Valid @RequestBody CommentCreateDTO dto) {
        return Result.ok(ticketCommentBizService.add(id, dto));
    }

    // ==================== 状态流转（D4-01 ~ D4-05，§8.12 / 规格基线 §7.2） ====================

    /**
     * 分派工单（§8.12 #1，矩阵 #2）：{@code OPEN → ASSIGNED}。
     *
     * <p>权限 {@code ticket:assign}；角色 AGENT / ADMIN。
     * 请求体 {@code { "assigneeId": 3 }} —— 被分派人必须是启用的 AGENT / ADMIN。
     *
     * <p>⚠️ 状态流转的判定<b>不在 Controller</b>：这里只声明「要执行 assign 这个动作」，
     * 「当前状态能不能 assign、这个角色能不能 assign」由 {@code TicketStateMachine} 决定。
     */
    @RequirePermission(PermissionCodes.TICKET_ASSIGN)
    @PostMapping("/{id}/assign")
    public Result<TicketDetailVO> assign(@PathVariable Long id,
                                         @Valid @RequestBody TicketAssignDTO dto) {
        return Result.ok(ticketFlowService.assign(id, dto));
    }

    /**
     * 受理工单（§8.12 #3，矩阵 #3）：{@code OPEN → ASSIGNED}，处理人 = 当前用户。
     *
     * <p>权限 {@code ticket:accept}；角色 AGENT / ADMIN。无请求体。
     */
    @RequirePermission(PermissionCodes.TICKET_ACCEPT)
    @PostMapping("/{id}/accept")
    public Result<TicketDetailVO> accept(@PathVariable Long id) {
        return Result.ok(ticketFlowService.accept(id));
    }

    /**
     * 开始处理（§8.12 #4，矩阵 #5）：{@code ASSIGNED → IN_PROGRESS}。
     *
     * <p>权限 {@code ticket:process}；仅当前 assignee 或 ADMIN。无请求体。
     *
     * <p>副作用：{@code first_response_at} 为空时写入当前时间（§9.3「响应」的判定点）。
     * 注：按 §9.3 / §8.12，assign / accept 时<b>已经</b>写过它；
     * 这里的「若空」是兜底（如历史数据或直接改库导致为空）。
     */
    @RequirePermission(PermissionCodes.TICKET_PROCESS)
    @PostMapping("/{id}/start")
    public Result<TicketDetailVO> start(@PathVariable Long id) {
        return Result.ok(ticketFlowService.start(id));
    }

    /**
     * 转派工单（§8.12 #2，矩阵 #6）：{@code ASSIGNED → ASSIGNED}，只换处理人。
     *
     * <p>权限 {@code ticket:transfer}；角色 AGENT / ADMIN。
     * 请求体 {@code { "assigneeId": 3 }}（与 assign 同构，复用 {@link TicketAssignDTO}）。
     *
     * <p>⚠️ {@code first_response_at} 保持不变（已响应过，不重置）。
     */
    @RequirePermission(PermissionCodes.TICKET_TRANSFER)
    @PostMapping("/{id}/transfer")
    public Result<TicketDetailVO> transfer(@PathVariable Long id,
                                           @Valid @RequestBody TicketAssignDTO dto) {
        return Result.ok(ticketFlowService.transfer(id, dto));
    }

    /**
     * 挂起工单（§8.12 #5，矩阵 #8）：{@code IN_PROGRESS → WAITING_USER}，SLA 进入暂停。
     *
     * <p>权限 {@code ticket:process}；仅当前 assignee 或 ADMIN。无请求体。
     *
     * <p>幂等：已在 {@code WAITING_USER} 再 hold → {@code 40900}（状态机表里无该入边）。
     */
    @RequirePermission(PermissionCodes.TICKET_PROCESS)
    @PostMapping("/{id}/hold")
    public Result<TicketDetailVO> hold(@PathVariable Long id) {
        return Result.ok(ticketFlowService.hold(id));
    }

    /**
     * 恢复工单（§8.12 #6，矩阵 #9）：{@code WAITING_USER → IN_PROGRESS}，SLA 恢复并顺延（§9.4）。
     *
     * <p>权限 {@code ticket:process}；仅当前 assignee 或 ADMIN。无请求体。
     *
     * <p>⚠️ 只顺延 {@code resolution_deadline}；{@code response_deadline} 不动。
     */
    @RequirePermission(PermissionCodes.TICKET_PROCESS)
    @PostMapping("/{id}/resume")
    public Result<TicketDetailVO> resume(@PathVariable Long id) {
        return Result.ok(ticketFlowService.resume(id));
    }

    /**
     * 标记解决（§8.12 #7，矩阵 #10）：{@code IN_PROGRESS → WAITING_CONFIRM}，SLA 进入暂停。
     *
     * <p>权限 {@code ticket:resolve}；仅当前 assignee 或 ADMIN。无请求体。
     */
    @RequirePermission(PermissionCodes.TICKET_RESOLVE)
    @PostMapping("/{id}/resolve")
    public Result<TicketDetailVO> resolve(@PathVariable Long id) {
        return Result.ok(ticketFlowService.resolve(id));
    }

    /**
     * 关闭工单（§8.12 #8，矩阵 #11）：{@code WAITING_CONFIRM → CLOSED}，SLA 恢复。
     *
     * <p>权限 {@code ticket:close}；<b>创建人或 ADMIN</b>（EMPLOYEE 也持有该权限码，
     * 但仅限自己创建的工单 —— 这层由状态机的 {@code CREATOR_OR_ADMIN} 前置条件保证）。无请求体。
     */
    @RequirePermission(PermissionCodes.TICKET_CLOSE)
    @PostMapping("/{id}/close")
    public Result<TicketDetailVO> close(@PathVariable Long id) {
        return Result.ok(ticketFlowService.close(id));
    }

    /**
     * 驳回（§8.12 #9，矩阵 #12）：{@code WAITING_CONFIRM → REOPENED}，按 §9.6 重算 SLA。
     *
     * <p>权限 {@code ticket:reopen}；<b>仅创建人</b>。无请求体。
     *
     * <p>⚠️ §7.3：{@code REOPENED} 是<b>持久状态</b> —— 本接口只走到 {@code REOPENED}，
     * 要回到 {@code IN_PROGRESS} 必须由处理人再调一次 {@code POST /{id}/start}。
     */
    @RequirePermission(PermissionCodes.TICKET_REOPEN)
    @PostMapping("/{id}/reject")
    public Result<TicketDetailVO> reject(@PathVariable Long id) {
        return Result.ok(ticketFlowService.reject(id));
    }

    /**
     * 撤销工单（§8.12 #10，矩阵 #4 / #7）：{@code OPEN} / {@code ASSIGNED} → {@code CANCELLED}。
     *
     * <p>权限 {@code ticket:cancel}；<b>创建人或 ADMIN</b>。
     * 请求体 {@code { "reason": "问题已自行解决" }} —— <b>reason 必填</b>（不带 → {@code 40001}）。
     */
    @RequirePermission(PermissionCodes.TICKET_CANCEL)
    @PostMapping("/{id}/cancel")
    public Result<TicketDetailVO> cancel(@PathVariable Long id,
                                         @Valid @RequestBody TicketCancelDTO dto) {
        return Result.ok(ticketFlowService.cancel(id, dto));
    }

    /**
     * 强制关闭（§8.12 #11，矩阵 #14）：任意非终态 → {@code CLOSED}。
     *
     * <p>权限 {@code ticket:close}；<b>仅 ADMIN</b>（EMPLOYEE / AGENT → {@code 40300}）。无请求体。
     *
     * <p>⚠️ 终态工单不可强制关闭 —— 状态机表里没有入边，天然 {@code 40900}。
     */
    @RequirePermission(PermissionCodes.TICKET_CLOSE)
    @PostMapping("/{id}/force-close")
    public Result<TicketDetailVO> forceClose(@PathVariable Long id) {
        return Result.ok(ticketFlowService.forceClose(id));
    }

    /**
     * 工单历史（§8.5）—— {@code TicketHistoryVO[]}，按时间升序，<b>不分页</b>。
     *
     * <p>鉴权用 {@code ticket:detail}：历史是「看这张工单」的一部分，与详情同权限。
     * 数据范围先校验（不可见 {@code 40301}，不存在 {@code 40400}）。
     *
     * <p>⚠️ 路径 {@code /{id}/history} 比 {@code /{id}} 多一段，不会与详情冲突。
     */
    @RequirePermission(PermissionCodes.TICKET_DETAIL)
    @GetMapping("/{id}/history")
    public Result<List<TicketHistoryVO>> history(@PathVariable Long id) {
        return Result.ok(ticketHistoryBizService.list(id));
    }

    // ==================== 附件（D3-05，§8.9 / §8.10） ====================

    /**
     * 上传附件（§8.9）—— {@code multipart/form-data}，字段名 {@code file}。
     *
     * <p>鉴权用 {@code ticket:comment}：附件是工单的补充材料（报错截图、日志），
     * 与评论同属「参与这张工单」的行为；规格 §3.6 没有 {@code attachment:*} 权限码
     * （已与用户确认复用本码）。
     *
     * <p>约束：单文件 ≤10MB；类型白名单 {@code jpg/png/gif/pdf/txt/log/docx/xlsx}；
     * 存储名 UUID 化。详细校验链见 {@code AttachmentServiceImpl}。
     *
     * <p>⚠️ {@code @RequestParam("file")} 显式写名字 —— 不写的话依赖参数名保留
     * （{@code -parameters} 编译选项），换个构建方式就默默坏了。
     */
    @RequirePermission(PermissionCodes.TICKET_COMMENT)
    @PostMapping("/{id}/attachments")
    public Result<AttachmentUploadVO> uploadAttachment(@PathVariable Long id,
                                                       @RequestParam("file") MultipartFile file) {
        return Result.ok(attachmentService.upload(id, file));
    }

    /**
     * 附件列表（§8.10）—— 不分页（§8.10 的响应是 {@code AttachmentVO[]}，不是分页结构）。
     *
     * <p>鉴权用 {@code ticket:comment} 与上传保持一致 —— 上传者与查看者是同一批人。
     */
    @RequirePermission(PermissionCodes.TICKET_COMMENT)
    @GetMapping("/{id}/attachments")
    public Result<List<AttachmentVO>> listAttachments(@PathVariable Long id) {
        return Result.ok(attachmentService.list(id));
    }
}
