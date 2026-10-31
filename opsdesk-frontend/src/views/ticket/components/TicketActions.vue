<script setup lang="ts">
/**
 * 动作区（F8-02）—— 按 `canOperate` 渲染状态流转按钮。
 *
 * ⚠️ **`canOperate` 是渲染依据，不是安全边界**（§16.8 原文）。
 * 前端只负责「不显示必然失败的按钮」；后端每个流转接口都会独立校验
 * 权限 + 状态 + 数据范围 —— 把按钮藏起来不等于授权。
 *
 * ⚠️ 不要在这里写任何角色判断（`isAdmin` / `hasRole`）：
 * 「`force-close` 仅 ADMIN、`reject` 仅创建人、`close` 创建人/ADMIN」
 * 这些约束后端已经算进 `canOperate` 了，前端再判一次只会和后端对不上。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  buildActionButtons,
  useTicketActions,
  type TicketActionMeta,
} from '@/composables/useTicketActions'
import type { TicketDetailVO } from '@/types/ticket'

const props = defineProps<{
  /** 工单详情（用其中的 id / status / canOperate） */
  ticket: TicketDetailVO
}>()

const emit = defineEmits<{
  /** 动作成功：带上最新的工单快照（不要再请求一次详情） */
  (e: 'updated', ticket: TicketDetailVO): void
  /** 状态已变（40900 / 40301）：调用方应刷新 */
  (e: 'stale'): void
}>()

const { submitting, runAction } = useTicketActions()

const buttons = computed<TicketActionMeta[]>(() => buildActionButtons(props.ticket.canOperate))

/** 工单是否已结束（决定空态文案） */
const isFinished = computed<boolean>(
  () => props.ticket.status === 'CLOSED' || props.ticket.status === 'CANCELLED',
)

// ==================== 弹窗（assignee / reason 共用） ====================

const dialogVisible = ref(false)
const currentAction = ref<TicketActionMeta | null>(null)
const assigneeId = ref<number | null>(null)
const reason = ref('')

function openDialog(meta: TicketActionMeta): void {
  currentAction.value = meta
  assigneeId.value = null
  reason.value = ''
  dialogVisible.value = true
}

/** 真正执行 */
async function execute(meta: TicketActionMeta, payload: { assigneeId?: number; reason?: string }): Promise<void> {
  const result = await runAction(props.ticket.id, meta.action, payload)
  if (result.kind === 'success') {
    emit('updated', result.ticket)
  } else if (result.kind === 'stale') {
    emit('stale')
  }
}

/** 点按钮：按弹窗形态分流 */
async function handleClick(meta: TicketActionMeta): Promise<void> {
  if (submitting.value) return

  if (meta.dialog === 'none') {
    await execute(meta, {})
    return
  }

  if (meta.dialog === 'confirm') {
    try {
      await ElMessageBox.confirm(meta.tip ?? `确定要执行「${meta.label}」吗？`, meta.label, {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: meta.level === 'danger' ? 'warning' : 'info',
        // 危险动作用红色确认按钮，减少误点
        confirmButtonClass: meta.level === 'danger' ? 'el-button--danger' : undefined,
      })
    } catch {
      return // 用户取消
    }
    await execute(meta, {})
    return
  }

  openDialog(meta)
}

/** 弹窗确认 */
async function submitDialog(): Promise<void> {
  const meta = currentAction.value
  if (!meta) return

  if (meta.dialog === 'assignee') {
    if (!assigneeId.value || assigneeId.value <= 0) {
      ElMessage.warning('请输入有效的处理人 ID')
      return
    }
    dialogVisible.value = false
    await execute(meta, { assigneeId: assigneeId.value })
    return
  }

  if (meta.dialog === 'reason') {
    const text = reason.value.trim()
    if (!text) {
      ElMessage.warning('请填写撤销原因')
      return
    }
    if (text.length > 200) {
      ElMessage.warning('撤销原因不能超过 200 字')
      return
    }
    dialogVisible.value = false
    // ⚠️ 撤销的字段名是 reason，不是 remark
    await execute(meta, { reason: text })
  }
}
</script>

<template>
  <div class="actions">
    <!-- 空态：已结束 vs 当前用户无可用动作，文案要分开 -->
    <div v-if="buttons.length === 0" class="actions__empty">
      {{ isFinished ? '工单已结束，无可执行操作' : '当前状态下你暂无可执行的操作' }}
    </div>

    <div v-else class="actions__buttons">
      <el-button
        v-for="btn in buttons"
        :key="btn.action"
        :type="btn.level === 'primary' ? 'primary' : btn.level === 'danger' ? 'danger' : 'default'"
        :plain="btn.level === 'danger'"
        :loading="submitting === btn.action"
        :disabled="!!submitting && submitting !== btn.action"
        @click="handleClick(btn)"
      >
        {{ btn.label }}
      </el-button>
    </div>

    <!-- 分派 / 转派 / 撤销 共用一个弹窗 -->
    <el-dialog v-model="dialogVisible" :title="currentAction?.label" width="440px">
      <template v-if="currentAction?.dialog === 'assignee'">
        <el-form label-width="90px">
          <el-form-item label="处理人 ID" required>
            <el-input-number
              v-model="assigneeId"
              :min="1"
              :step="1"
              :precision="0"
              controls-position="right"
              placeholder="请输入数字 ID"
              class="actions__assignee"
            />
          </el-form-item>
        </el-form>
        <!--
          ⚠️ 后端没有「查 AGENT 列表」的接口：GET /api/users 要 user:list，AGENT 调会 40300。
          所以只能手填 ID（已与需求方确认）。补了受限查询之后，这里换成下拉。
        -->
        <p class="actions__tip">
          处理人必须是启用的 AGENT / ADMIN。ID 可从工单详情的「处理人」处查看，或向管理员索取。
        </p>
      </template>

      <template v-else-if="currentAction?.dialog === 'reason'">
        <el-input
          v-model="reason"
          type="textarea"
          :rows="4"
          maxlength="200"
          show-word-limit
          placeholder="请说明撤销原因（必填，不超过 200 字）"
        />
      </template>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting === currentAction?.action"
          @click="submitDialog"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.actions {
  &__empty {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  &__buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  &__assignee {
    width: 180px;
  }

  &__tip {
    margin: 4px 0 0;
    padding-left: 90px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.6;
  }
}
</style>
