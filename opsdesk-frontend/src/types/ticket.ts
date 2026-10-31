/**
 * 工单相关类型。
 *
 * 字段与后端 `com.opsdesk.ticket.vo.TicketListVO` **逐项对齐**（已核对源码，不是抄文档）。
 *
 * ⚠️ 时间字段的真实形态：`TicketListVO` 上带 `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")`，
 * 所以返回的是 `2026-10-27 21:21:36`（**空格分隔，不是 ISO 的 `T`**）。
 * 全局约定里说的「后端统一 ISO 带 T」在这张 VO 上不成立 —— `utils/format.ts` 两种都能解析，
 * 但不要在别处假设时间一定是 ISO。
 */

import type {
  SlaStateValue,
  TicketCategoryValue,
  TicketHistoryActionValue,
  TicketPriorityValue,
  TicketSourceValue,
  TicketStatusValue,
  TicketTypeValue,
} from '@/constants/enums'

/**
 * 工单列表项（`GET /api/tickets` 与 `GET /api/tickets/mine` 共用）。
 *
 * 比详情 VO 少：没有描述、SLA 策略、AI 分析、附件 —— 列表一次最多 50 行，字段要克制。
 */
export interface TicketListVO {
  /** 工单 ID */
  id: number
  /** 工单号，如 TK202610270001 */
  ticketNo: string
  /** 标题 */
  title: string
  /** 类型 */
  type: TicketTypeValue
  /** 分类 */
  category: TicketCategoryValue
  /** 优先级 */
  priority: TicketPriorityValue
  /** 状态 */
  status: TicketStatusValue
  /** 创建人姓名 */
  creatorName: string
  /** 处理人姓名；**未分派时为 null** */
  assigneeName: string | null
  /** 解决时限的 SLA 状态 */
  slaResolutionState: SlaStateValue
  /** 解决截止时间 */
  resolutionDeadline: string | null
  /** 创建时间 */
  createdAt: string
}

/**
 * 工单列表查询参数。
 *
 * 后端 `TicketQuery` 支持 status / priority / category / type / keyword / assigneeId /
 * startTime / endTime / sort 一整套，本单只用到文档要求的两项（status + keyword）。
 * 其余留给 F7-05「工单管理」。
 *
 * ⚠️ `size` 上限 50：后端 `PageQuery.normalize()` 会把超限值**静默收敛**成 50，
 * 不会报错 —— 所以前端必须自己夹紧，否则用户选了 100 却只拿到 50 条，会以为是丢数据。
 */
export interface TicketQueryParams {
  /** 状态筛选 */
  status?: TicketStatusValue
  /** 关键字：后端匹配 ticket_no / title */
  keyword?: string
  /** 页码，从 1 开始 */
  page?: number
  /** 每页条数，≤ 50 */
  size?: number
}

/**
 * 创建工单入参（`POST /api/tickets`）。
 *
 * 字段与后端 `TicketCreateDTO` 逐项对齐：
 * - `title` 必填 ≤128
 * - `description` 必填 ≤4000
 * - `type` / `category` 必填（枚举白名单由 Jackson 反序列化保证，传错值直接 40001）
 * - `priority` 可空，后端缺省 `P3`
 */
export interface TicketCreatePayload {
  /** 标题，≤128 */
  title: string
  /** 描述，≤4000 */
  description: string
  /** 类型 */
  type: TicketTypeValue
  /** 分类 */
  category: TicketCategoryValue
  /** 优先级，不传则后端默认 P3 */
  priority?: TicketPriorityValue
}

/**
 * 创建工单出参（`POST /api/tickets` 的 data）。
 *
 * ⚠️ 这个对象也是后端幂等缓存的载体：同一 `Idempotency-Key` 重复提交时，
 * 后端会把首次的 `{ id, ticketNo }` 原样返回 —— 所以拿到它之后跳详情页是安全的。
 */
export interface TicketCreatedVO {
  /** 工单主键 */
  id: number
  /** 工单号：OD + yyyyMMdd + 5 位日序列，如 OD2026101000001 */
  ticketNo: string
}

/**
 * 工单详情（`GET /api/tickets/{id}`）。
 *
 * 后端 `TicketDetailVO extends TicketListVO`，所以列表的 12 个字段全都有，
 * 这里再追加 16 个详情独有字段。
 *
 * ⚠️ **没有 `attachments` 字段**，也**没有 `aiAnalysis` 字段**（后者后端还没做）：
 * 附件必须单独调 `GET /api/tickets/{id}/attachments`。
 */
export interface TicketDetailVO extends TicketListVO {
  /** 问题描述 */
  description: string
  /** 来源：WEB / AI_ASSISTANT / ADMIN */
  source: TicketSourceValue
  /** 创建人 ID */
  creatorId: number
  /** 创建人所属部门 ID（创建时快照） */
  departmentId: number | null
  /** 处理人 ID；未分派为 null */
  assigneeId: number | null
  /** 创建人部门名称 */
  departmentName: string | null
  /** 创建时适用的 SLA 策略版本 id */
  slaPolicyId: number | null
  /** 响应截止时间 */
  responseDeadline: string | null
  /** 首次受理时间；未受理为 null */
  firstResponseAt: string | null
  /** 响应 SLA 状态 */
  slaResponseState: SlaStateValue
  /** 累计暂停时长（分钟） */
  slaPausedMinutes: number
  /** 重新打开次数 */
  reopenCount: number
  /** 撤销原因；仅 CANCELLED 时有值 */
  cancelReason: string | null
  /** 进入 WAITING_CONFIRM 的时间 */
  resolvedAt: string | null
  /** 关闭时间 */
  closedAt: string | null
  /**
   * 当前用户可执行的流转动作名，如 `['assign', 'cancel']`。
   *
   * ⚠️ **不是安全边界**：只用于渲染按钮，每个流转接口仍独立校验权限 + 状态 + 数据范围。
   * 本单（F7-06）只做只读展示，按钮由 F8-02 接入。
   */
  canOperate: string[]
}

/**
 * 工单历史（`GET /api/tickets/{id}/history`）。
 *
 * ⚠️ 后端返回的是**数组，不分页**，且已按 `created_at ASC, id ASC` 排好序。
 */
export interface TicketHistoryVO {
  /** 记录 ID */
  id: number
  /** 操作人 ID */
  operatorId: number
  /** 操作人姓名 */
  operatorName: string
  /** 动作 */
  action: TicketHistoryActionValue
  /** 变更前状态；`CREATE` 动作为 **null** */
  fromStatus: TicketStatusValue | null
  /** 变更后状态 */
  toStatus: TicketStatusValue | null
  /** 备注 */
  remark: string | null
  /** 时间 */
  createdAt: string
}

/**
 * 工单评论（`GET /api/tickets/{id}/comments` 的列表项）。
 *
 * ⚠️ `internal` 为 true 的评论，EMPLOYEE **在后端 SQL 层就被过滤掉了**，
 * 前端不需要也不应该再做一次过滤。
 */
export interface TicketCommentVO {
  /** 评论 ID */
  id: number
  /** 评论人 ID */
  userId: number
  /** 评论人姓名 */
  userName: string
  /** 内容 */
  content: string
  /** 是否仅 IT 内部可见 */
  internal: boolean
  /** 时间 */
  createdAt: string
}

/** 发表评论入参（`POST /api/tickets/{id}/comments`） */
export interface CommentCreatePayload {
  /** 内容，≤2000 */
  content: string
  /**
   * 是否仅 IT 内部可见。
   *
   * ⚠️ 只有 AGENT / ADMIN 能设为 true；EMPLOYEE 传 true 会被后端**静默降级**为 false。
   * 前端本来就不给员工这个开关。
   */
  internal?: boolean
}

/** 附件（`GET /api/tickets/{id}/attachments` 的列表项） */
export interface AttachmentVO {
  /** 附件 ID（下载接口的入参） */
  id: number
  /** 原始文件名 */
  fileName: string
  /** 字节数 */
  fileSize: number
  /** MIME */
  contentType: string | null
  /** 上传人姓名 */
  uploaderName: string
  /** 上传时间 */
  createdAt: string
}

/** 附件上传响应（`POST /api/tickets/{id}/attachments`）—— 只有 3 个字段 */
export interface AttachmentUploadVO {
  /** 附件 ID */
  id: number
  /** 原始文件名 */
  fileName: string
  /** 字节数 */
  fileSize: number
}
