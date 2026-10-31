<script setup lang="ts">
/**
 * 待受理池（F8-01）—— AGENT 看公共池、接单、分派、导出。
 *
 * ⚠️ 数据源是 `GET /api/tickets?status=OPEN`，**不额外传 `assigneeId`**：
 * AGENT 的数据范围在后端已经是「分配给我的 ∪ 公共待领取池（OPEN 且未分派）∪ 本部门子树」，
 * 前端再按 `assigneeId == null` 过滤一遍，等于把后端的数据范围规则抄一份到前端，
 * 迟早和后端对不上。
 *
 * ⚠️ 并发接单：两人同时接同一单，后到的会收到 **40900**。必须提示「已被他人接单」并刷新，
 * 不能笼统说「操作失败」——这是本单最容易被做歪的地方。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { accept, assign, exportTickets, list } from '@/api/ticket'
import { ErrorCode } from '@/constants/errorCode'
import { TicketCategory, TicketPriority, labelOf, tagTypeOf } from '@/constants/enums'
import { formatDateTime } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { TicketListVO } from '@/types/ticket'

const router = useRouter()

const rows = ref<TicketListVO[]>([])
const total = ref(0)
const loading = ref(false)
const errorText = ref('')

const query = reactive({
  priority: '' as string,
  keyword: '',
  page: 1,
  size: 10,
})

/** 正在接单的工单 ID（行级 loading） */
const acceptingId = ref<number | null>(null)
/** 刚接单成功的工单，用于展示「去处理」入口 */
const acceptedTicket = ref<TicketListVO | null>(null)

/** 分派弹窗 */
const assignVisible = ref(false)
const assignTarget = ref<TicketListVO | null>(null)
const assigneeId = ref<number | null>(null)
const assigning = ref(false)

const exporting = ref(false)

/**
 * 等待时长：当前时间 − createdAt。
 *
 * 待受理池的价值就在「谁等最久」，所以这一列要显眼：
 * 超过 2 小时标黄，超过 8 小时标红。
 */
function waitingOf(createdAt: string): { text: string; level: 'normal' | 'warning' | 'danger' } {
  const start = new Date(createdAt.replace(' ', 'T')).getTime()
  if (Number.isNaN(start)) return { text: '—', level: 'normal' }

  const minutes = Math.max(0, Math.floor((Date.now() - start) / 60_000))
  const level = minutes >= 8 * 60 ? 'danger' : minutes >= 2 * 60 ? 'warning' : 'normal'

  let text: string
  if (minutes < 60) {
    text = `${minutes} 分钟`
  } else if (minutes < 24 * 60) {
    text = `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
  } else {
    text = `${Math.floor(minutes / 1440)} 天 ${Math.floor((minutes % 1440) / 60)} 小时`
  }
  return { text, level }
}

/** 拉列表：状态固定 OPEN，其余条件按用户选择 */
async function fetchList(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const result = await list({
      status: 'OPEN',
      priority: query.priority ? (query.priority as TicketListVO['priority']) : undefined,
      keyword: query.keyword.trim() || undefined,
      page: query.page,
      size: query.size,
    })
    rows.value = result.list ?? []
    total.value = Number(result.total ?? 0)
    query.page = Number(result.page ?? query.page)
    query.size = Number(result.size ?? query.size)
  } catch (error) {
    errorText.value = (error as BizError).serverMessage
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.page = 1
  void fetchList()
}

function handleReset(): void {
  query.priority = ''
  query.keyword = ''
  query.page = 1
  void fetchList()
}

/**
 * 并发冲突处理：接单 / 分派时「这张单已经不是你的了」的两种表现。
 *
 * ⚠️ 后端会返回**两种**码，都要当「被别人抢走了」处理，不能只判 40900：
 * - `40900`：工单还在你的可见范围内，但状态已经不是 `OPEN`（同部门场景）
 * - `40301`：工单被分派给了别人，**你已经看不见它了**（跨部门场景，后端先判数据范围再判状态）
 *
 * 只判 40900 的后果：跨部门工单被抢时用户看到「数据范围越权」，而且列表不刷新、
 * 那行一直留在页面上，再点还是错。
 *
 * @returns true 表示已按冲突处理（调用方不要再弹通用错误）
 */
function handleTakenAway(error: BizError): boolean {
  const conflict = error.code === ErrorCode.CONFLICT
  const invisible = error.code === ErrorCode.DATA_SCOPE_DENIED
  if (!conflict && !invisible) return false

  ElMessage.warning(
    invisible ? '该工单已被他人接单（你已无权查看该工单），列表已刷新' : '该工单已被他人接单，列表已刷新',
  )
  void fetchList()
  return true
}

/** 接单 */
async function handleAccept(row: TicketListVO): Promise<void> {
  acceptingId.value = row.id
  try {
    await accept(row.id)
    acceptedTicket.value = row
    ElMessage.success('已接单')
    await fetchList()
  } catch (error) {
    if (!handleTakenAway(error as BizError)) {
      ElMessage.error((error as BizError).serverMessage)
    }
  } finally {
    acceptingId.value = null
  }
}

/** 打开分派弹窗 */
function openAssign(row: TicketListVO): void {
  assignTarget.value = row
  assigneeId.value = null
  assignVisible.value = true
}

/** 提交分派 */
async function submitAssign(): Promise<void> {
  if (!assignTarget.value) return
  if (!assigneeId.value || assigneeId.value <= 0) {
    ElMessage.warning('请输入有效的处理人 ID')
    return
  }

  assigning.value = true
  try {
    await assign(assignTarget.value.id, assigneeId.value)
    ElMessage.success('已分派')
    assignVisible.value = false
    await fetchList()
  } catch (error) {
    assignVisible.value = false
    if (!handleTakenAway(error as BizError)) {
      ElMessage.error((error as BizError).serverMessage)
    }
  } finally {
    assigning.value = false
  }
}

/**
 * 导出当前筛选结果。
 *
 * ⚠️ 不传 page / size（导出不分页）；空结果也会返回只有表头的 xlsx，不是失败。
 * 失败时拦截器已弹提示（含 blob 里解析出的业务错误），这里不再重复。
 */
async function handleExport(): Promise<void> {
  exporting.value = true
  try {
    await exportTickets({
      status: 'OPEN',
      priority: query.priority ? (query.priority as TicketListVO['priority']) : undefined,
      keyword: query.keyword.trim() || undefined,
    })
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

function goDetail(id: number): void {
  void router.push({ name: 'TicketDetail', params: { id } })
}

function handlePageChange(page: number): void {
  query.page = page
  void fetchList()
}

function handleSizeChange(size: number): void {
  query.size = size
  query.page = 1
  void fetchList()
}

onMounted(fetchList)
</script>

<template>
  <div class="pool">
    <el-card shadow="never" class="pool__card">
      <template #header>
        <div class="pool__header">
          <div class="pool__title-wrap">
            <span class="pool__title">待受理池</span>
            <span class="pool__sub">所有未分派的待受理工单，先接先得</span>
          </div>
          <!-- 只有 AGENT / ADMIN 有 ticket:export，EMPLOYEE 的元素会被指令直接移除 -->
          <el-button v-perm="'ticket:export'" :loading="exporting" @click="handleExport">
            导出 Excel
          </el-button>
        </div>
      </template>

      <!-- 接单成功后的「去处理」入口 -->
      <el-alert
        v-if="acceptedTicket"
        type="success"
        :closable="true"
        show-icon
        class="pool__accepted"
        :title="`已接单：${acceptedTicket.ticketNo}`"
        @close="acceptedTicket = null"
      >
        <template #default>
          <el-button link type="primary" @click="goDetail(acceptedTicket.id)">去处理 →</el-button>
        </template>
      </el-alert>

      <!-- 筛选 -->
      <div class="pool__filters">
        <el-select v-model="query.priority" placeholder="全部优先级" clearable class="pool__priority" @change="handleSearch">
          <el-option
            v-for="item in TicketPriority"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <el-input
          v-model="query.keyword"
          placeholder="工单号 / 标题"
          clearable
          class="pool__keyword"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />

        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        show-icon
        class="pool__error"
        :title="`加载失败：${errorText}`"
      >
        <template #default>
          <el-button size="small" @click="fetchList">重试</el-button>
        </template>
      </el-alert>

      <!-- 列表 -->
      <el-table v-loading="loading" :data="rows" row-key="id" class="pool__table">
        <el-table-column prop="ticketNo" label="工单号" width="150" />

        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="goDetail(row.id)">
              {{ row.title }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="120">
          <template #default="{ row }">{{ labelOf(TicketCategory, row.category) }}</template>
        </el-table-column>

        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="tagTypeOf(TicketPriority, row.priority)" size="small" disable-transitions>
              {{ labelOf(TicketPriority, row.priority) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 待受理池的核心信息：等多久了 -->
        <el-table-column label="已等待" width="150">
          <template #default="{ row }">
            <span
              class="pool__waiting"
              :class="`pool__waiting--${waitingOf(row.createdAt).level}`"
            >
              {{ waitingOf(row.createdAt).text }}
            </span>
          </template>
        </el-table-column>

        <el-table-column prop="creatorName" label="创建人" width="100" />

        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <!-- 不再放「状态」列：整个池子按定义都是待受理，这一列纯属噪音，还会把表格撑宽 -->
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="acceptingId === row.id"
              @click="handleAccept(row as TicketListVO)"
            >
              接单
            </el-button>
            <el-button link type="primary" @click="openAssign(row as TicketListVO)">分派</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无待受理工单 🎉" :image-size="90" />
        </template>
      </el-table>

      <div class="pool__pager">
        <el-pagination
          :current-page="query.page"
          :page-size="query.size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          background
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 分派弹窗 -->
    <el-dialog v-model="assignVisible" title="分派工单" width="440px">
      <el-form label-width="90px">
        <el-form-item label="工单">
          <span class="pool__assign-no">{{ assignTarget?.ticketNo }}</span>
        </el-form-item>
        <el-form-item label="处理人 ID" required>
          <el-input-number
            v-model="assigneeId"
            :min="1"
            :step="1"
            :precision="0"
            controls-position="right"
            placeholder="请输入数字 ID"
            class="pool__assign-input"
          />
        </el-form-item>
      </el-form>

      <!--
        ⚠️ 后端没有「查 AGENT 列表」的接口：GET /api/users 要 user:list，AGENT 调会 40300。
        所以这里只能手填 ID（已与需求方确认）。补了受限查询接口之后，这里换成下拉。
      -->
      <p class="pool__assign-tip">
        处理人必须是启用的 AGENT / ADMIN。ID 可从工单详情的「处理人」处查看，或向管理员索取。
      </p>

      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="submitAssign">确定分派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.pool {
  padding: 16px;

  &__card {
    max-width: 1400px;
    margin: 0 auto;
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  &__title-wrap {
    display: flex;
    align-items: baseline;
    gap: 10px;
  }

  &__title {
    font-size: 16px;
    font-weight: 600;
  }

  &__sub {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  &__accepted {
    margin-bottom: 14px;
  }

  &__filters {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    margin-bottom: 16px;
  }

  &__priority {
    width: 160px;
  }

  &__keyword {
    width: 260px;
  }

  &__error {
    margin-bottom: 16px;
  }

  &__waiting {
    font-weight: 500;

    &--normal {
      color: var(--el-text-color-regular);
    }

    &--warning {
      color: var(--el-color-warning);
    }

    &--danger {
      color: var(--el-color-danger);
      font-weight: 600;
    }
  }

  &__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  &__assign-no {
    color: var(--el-text-color-secondary);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }

  &__assign-input {
    width: 180px;
  }

  &__assign-tip {
    margin: 4px 0 0;
    padding-left: 90px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.6;
  }
}
</style>
