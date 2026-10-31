/**
 * 枚举字典 —— 全站唯一的中文标签来源。
 *
 * 规则（派工单 §7）：
 * - `value` 用**后端的英文码**，`label` 用中文，`type` 用 Element Plus 的 tag 类型；
 * - 每个枚举导出成 `[{ value, label, type }]`；
 * - **页面里禁止直接写「待受理」这种中文字面量**，一律走 `labelOf()` / `tagTypeOf()`。
 *
 * 码值与后端 `com.opsdesk.common.enums.*` 逐项对齐。
 */

/** Element Plus 的 `<el-tag type="...">` 允许的取值 */
export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

/** 字典项的统一形状 */
export interface EnumItem {
  /** 后端英文码 */
  readonly value: string
  /** 中文标签 */
  readonly label: string
  /** Element Plus tag 类型 */
  readonly type: TagType
}

// ==================== 工单 ====================

/** 工单状态（8 种）。终态：CLOSED / CANCELLED */
export const TicketStatus = [
  { value: 'OPEN', label: '待受理', type: 'info' },
  { value: 'ASSIGNED', label: '已分派', type: 'primary' },
  { value: 'IN_PROGRESS', label: '处理中', type: 'primary' },
  { value: 'WAITING_USER', label: '待用户补充', type: 'warning' },
  { value: 'WAITING_CONFIRM', label: '待确认', type: 'warning' },
  { value: 'REOPENED', label: '已重新打开', type: 'danger' },
  { value: 'CLOSED', label: '已关闭', type: 'success' },
  { value: 'CANCELLED', label: '已撤销', type: 'info' },
] as const satisfies readonly EnumItem[]

/** 工单优先级（3 种） */
export const TicketPriority = [
  { value: 'P1', label: '紧急', type: 'danger' },
  { value: 'P2', label: '高', type: 'warning' },
  { value: 'P3', label: '普通', type: 'info' },
] as const satisfies readonly EnumItem[]

/** 工单分类（7 种） */
export const TicketCategory = [
  { value: 'NETWORK', label: '网络/VPN', type: 'info' },
  { value: 'ACCOUNT', label: '账号/权限', type: 'info' },
  { value: 'HARDWARE', label: '硬件/设备', type: 'info' },
  { value: 'SOFTWARE', label: '软件/系统', type: 'info' },
  { value: 'EMAIL', label: '邮箱/办公协作', type: 'info' },
  { value: 'PERIPHERAL', label: '外设/打印', type: 'info' },
  { value: 'OTHER', label: '其他', type: 'info' },
] as const satisfies readonly EnumItem[]

/** 工单类型（2 种）：故障异常 / 服务请求 */
export const TicketType = [
  { value: 'INCIDENT', label: '故障异常', type: 'danger' },
  { value: 'SERVICE_REQUEST', label: '服务请求', type: 'primary' },
] as const satisfies readonly EnumItem[]

/** 工单来源（3 种） */
export const TicketSource = [
  { value: 'WEB', label: '页面创建', type: 'info' },
  { value: 'AI_ASSISTANT', label: 'AI 助手创建', type: 'primary' },
  { value: 'ADMIN', label: '管理员代建', type: 'warning' },
] as const satisfies readonly EnumItem[]

/** SLA 状态（3 种） */
export const SlaState = [
  { value: 'NORMAL', label: '正常', type: 'success' },
  { value: 'WARNING', label: '临近超时', type: 'warning' },
  { value: 'BREACHED', label: '已超时', type: 'danger' },
] as const satisfies readonly EnumItem[]

/**
 * 工单历史动作（13 种）—— 时间线展示用。
 *
 * 码值与后端 `com.opsdesk.common.enums.TicketHistoryAction` 逐项对齐。
 * ⚠️ `CREATE` 动作的 `fromStatus` 为 `null`（没有来源状态），渲染时要兜住。
 */
export const TicketHistoryAction = [
  { value: 'CREATE', label: '创建', type: 'info' },
  { value: 'ASSIGN', label: '分派', type: 'primary' },
  { value: 'TRANSFER', label: '转派', type: 'primary' },
  { value: 'ACCEPT', label: '接单', type: 'primary' },
  { value: 'START', label: '开始处理', type: 'primary' },
  { value: 'HOLD', label: '挂起', type: 'warning' },
  { value: 'RESUME', label: '恢复', type: 'primary' },
  { value: 'RESOLVE', label: '提交解决', type: 'success' },
  { value: 'CLOSE', label: '关闭', type: 'success' },
  { value: 'REJECT', label: '驳回重开', type: 'danger' },
  { value: 'CANCEL', label: '撤销', type: 'info' },
  { value: 'FORCE_CLOSE', label: '强制关闭', type: 'danger' },
  { value: 'COMMENT', label: '评论', type: 'info' },
] as const satisfies readonly EnumItem[]

// ==================== 知识库 / 通知 ====================

/** 知识文档解析状态（3 种） */
export const KnowledgeDocStatus = [
  { value: 'PROCESSING', label: '处理中', type: 'warning' },
  { value: 'READY', label: '就绪', type: 'success' },
  { value: 'FAILED', label: '失败', type: 'danger' },
] as const satisfies readonly EnumItem[]

/** 站内通知类型（6 种） */
export const NotificationType = [
  { value: 'TICKET_CREATED', label: '工单创建', type: 'info' },
  { value: 'TICKET_ASSIGNED', label: '工单分派', type: 'primary' },
  { value: 'TICKET_STATUS_CHANGED', label: '状态变更', type: 'primary' },
  { value: 'TICKET_COMMENT', label: '新增评论', type: 'info' },
  { value: 'SLA_WARNING', label: '临近超时', type: 'warning' },
  { value: 'SLA_BREACHED', label: '已超时', type: 'danger' },
] as const satisfies readonly EnumItem[]

// ==================== 权限模型 ====================

/** 角色（3 种） */
export const Role = [
  { value: 'EMPLOYEE', label: '普通员工', type: 'info' },
  { value: 'AGENT', label: 'IT 服务人员', type: 'primary' },
  { value: 'ADMIN', label: '管理员', type: 'danger' },
] as const satisfies readonly EnumItem[]

/** 数据范围（4 种），从窄到宽 */
export const DataScope = [
  { value: 'SELF', label: '仅本人创建', type: 'info' },
  { value: 'ASSIGNED', label: '分配给本人', type: 'primary' },
  { value: 'DEPARTMENT', label: '本部门含子部门', type: 'warning' },
  { value: 'ALL', label: '全部数据', type: 'danger' },
] as const satisfies readonly EnumItem[]

/**
 * 工单流转动作（`TicketDetailVO.canOperate` 的取值）。
 *
 * ⚠️ 这里的 value 是**接口路径的动作名**（小写、下划线转连字符），
 * 不是 `TicketHistoryAction` 的枚举名 —— 两套编码，别混用。
 * 后端 `TicketStateMachine.availableActions` 算出，已综合「状态可流转 + 角色满足 + 前置条件」。
 */
export const TicketOperation = [
  { value: 'assign', label: '分派', type: 'primary' },
  { value: 'accept', label: '受理', type: 'primary' },
  { value: 'start', label: '开始处理', type: 'primary' },
  { value: 'transfer', label: '转派', type: 'primary' },
  { value: 'hold', label: '挂起', type: 'warning' },
  { value: 'resume', label: '恢复', type: 'primary' },
  { value: 'resolve', label: '标记解决', type: 'success' },
  { value: 'close', label: '关闭', type: 'success' },
  { value: 'reject', label: '驳回', type: 'danger' },
  { value: 'cancel', label: '撤销', type: 'info' },
  { value: 'force-close', label: '强制关闭', type: 'danger' },
] as const satisfies readonly EnumItem[]

// ==================== 工具函数 ====================

/**
 * 取枚举的中文标签。
 *
 * 取不到时**返回原值**（派工单要求），空值返回 `'—'` 便于表格直接渲染。
 *
 * @example labelOf(TicketStatus, 'OPEN') // '待受理'
 */
export function labelOf(list: readonly EnumItem[], value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return list.find((item) => item.value === value)?.label ?? value
}

/**
 * 取枚举对应的 Element Plus tag 类型，取不到时返回 `'info'`。
 *
 * @example tagTypeOf(SlaState, 'BREACHED') // 'danger'
 */
export function tagTypeOf(list: readonly EnumItem[], value: string | null | undefined): TagType {
  if (!value) return 'info'
  return list.find((item) => item.value === value)?.type ?? 'info'
}

// ==================== 派生字面量类型（供后续工单给字段加约束） ====================

/** 工单状态取值联合：'OPEN' | 'ASSIGNED' | … */
export type TicketStatusValue = (typeof TicketStatus)[number]['value']
/** 工单优先级取值联合 */
export type TicketPriorityValue = (typeof TicketPriority)[number]['value']
/** 工单分类取值联合 */
export type TicketCategoryValue = (typeof TicketCategory)[number]['value']
/** 工单类型取值联合 */
export type TicketTypeValue = (typeof TicketType)[number]['value']
/** 工单来源取值联合 */
export type TicketSourceValue = (typeof TicketSource)[number]['value']
/** SLA 状态取值联合 */
export type SlaStateValue = (typeof SlaState)[number]['value']
/** 知识文档状态取值联合 */
export type KnowledgeDocStatusValue = (typeof KnowledgeDocStatus)[number]['value']
/** 通知类型取值联合 */
export type NotificationTypeValue = (typeof NotificationType)[number]['value']
/** 历史动作取值联合 */
export type TicketHistoryActionValue = (typeof TicketHistoryAction)[number]['value']
/** 角色取值联合 */
export type RoleValue = (typeof Role)[number]['value']
/** 数据范围取值联合 */
export type DataScopeValue = (typeof DataScope)[number]['value']
