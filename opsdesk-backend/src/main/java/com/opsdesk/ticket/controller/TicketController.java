package com.opsdesk.ticket.controller;

import com.opsdesk.auth.annotation.RequirePermission;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.Result;
import com.opsdesk.common.constant.PermissionCodes;
import com.opsdesk.ticket.dto.CommentCreateDTO;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.service.AttachmentService;
import com.opsdesk.ticket.service.TicketCommentBizService;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.support.IdempotencyGuard;
import com.opsdesk.ticket.vo.AttachmentUploadVO;
import com.opsdesk.ticket.vo.AttachmentVO;
import com.opsdesk.ticket.vo.TicketCommentVO;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import com.opsdesk.ticket.vo.TicketDetailVO;
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

    public TicketController(TicketCreateService ticketCreateService,
                            TicketQueryService ticketQueryService,
                            TicketCommentBizService ticketCommentBizService,
                            AttachmentService attachmentService) {
        this.ticketCreateService = ticketCreateService;
        this.ticketQueryService = ticketQueryService;
        this.ticketCommentBizService = ticketCommentBizService;
        this.attachmentService = attachmentService;
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
