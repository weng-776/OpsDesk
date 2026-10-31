<script setup lang="ts">
/**
 * 我的工单列表（F7-04）。
 *
 * ⚠️ 数据源固定 `GET /api/tickets/mine`（强制 SELF：只返回当前用户创建的），
 * **不能用** `GET /api/tickets` —— 后者按角色数据范围走，ADMIN 会拿到全部，语义完全不同。
 *
 * ⚠️ 筛选条件一律传给后端，**不在前端做二次过滤**：
 * 前端过滤会让 `total` 失真，用户在第二页看到空列表，还以为数据丢了。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { mine } from '@/api/ticket'
import SlaTag from '@/components/SlaTag.vue'
import StatusTag from '@/components/StatusTag.vue'
import {
  TicketCategory,
  TicketPriority,
  TicketStatus,
  TicketType,
  labelOf,
  tagTypeOf,
} from '@/constants/enums'
import { PAGE_SIZE_MAX } from '@/types/common'
import { formatDateTime } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { TicketListVO } from '@/types/ticket'

const router = useRouter()

/** 列表数据 */
const rows = ref<TicketListVO[]>([])
/** 总条数（后端 `PageResult.total`） */
const total = ref(0)
/** 加载中 */
const loading = ref(false)
/** 请求失败文案（失败时给「重试」按钮，不留白屏） */
const errorText = ref('')

/** 查询条件：status / keyword 之外的都不做（文档只要求这两项） */
const query = reactive({
  status: '' as string,
  keyword: '',
  page: 1,
  size: 10,
})

/** 每页条数选项，上限就是后端硬约束 50 */
const PAGE_SIZES = [10, 20, 50]

/** 拉数据 */
async function fetchList(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const result = await mine({
      page: query.page,
      // 夹紧上限：后端会把 >50 静默收敛成 50，不报错，前端不能指望它替我们兜底
      size: Math.min(query.size, PAGE_SIZE_MAX),
      status: query.status ? (query.status as TicketListVO['status']) : undefined,
      keyword: query.keyword.trim() || undefined,
    })
    rows.value = result.list ?? []
    total.value = result.total ?? 0
    // 以响应里的 page/size 为准（后端会收敛越界值），否则分页器显示与实际不符
    query.page = Number(result.page ?? query.page)
    query.size = Number(result.size ?? query.size)
  } catch (error) {
    // 拦截器已经弹过提示，这里落一个常驻的错误态 + 重试入口
    errorText.value = (error as BizError).message
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询：条件变了必须回到第 1 页，否则会停在一个不存在的页码上看到空列表 */
function handleSearch(): void {
  query.page = 1
  void fetchList()
}

/** 重置：条件清空 + 回第 1 页 */
function handleReset(): void {
  query.status = ''
  query.keyword = ''
  query.page = 1
  void fetchList()
}

/** 翻页 */
function handlePageChange(page: number): void {
  query.page = page
  void fetchList()
}

/** 改每页条数：同样要回第 1 页 */
function handleSizeChange(size: number): void {
  query.size = size
  query.page = 1
  void fetchList()
}

/** 点行进详情（F7-06 实现页面，这里先跳路由） */
function goDetail(row: TicketListVO): void {
  void router.push({ name: 'TicketDetail', params: { id: row.id } })
}

onMounted(fetchList)
</script>

<template>
  <div class="ticket-list">
    <!-- ========== 筛选区 ========== -->
    <el-card shadow="never" class="ticket-list__card">
      <div class="ticket-list__filters">
        <el-select
          v-model="query.status"
          placeholder="全部状态"
          clearable
          class="ticket-list__status"
          @change="handleSearch"
        >
          <el-option
            v-for="item in TicketStatus"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <el-input
          v-model="query.keyword"
          placeholder="工单号 / 标题"
          clearable
          class="ticket-list__keyword"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />

        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <!-- ========== 错误态：常驻提示 + 重试，不留白屏 ========== -->
      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        show-icon
        class="ticket-list__error"
        :title="`加载失败：${errorText}`"
      >
        <template #default>
          <el-button size="small" @click="fetchList">重试</el-button>
        </template>
      </el-alert>

      <!-- ========== 表格 ========== -->
      <el-table
        v-loading="loading"
        :data="rows"
        class="ticket-list__table"
        row-key="id"
        @row-click="goDetail"
      >
        <el-table-column prop="ticketNo" label="工单号" width="150" />

        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />

        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ labelOf(TicketType, row.type) }}</template>
        </el-table-column>

        <el-table-column label="分类" width="130">
          <template #default="{ row }">{{ labelOf(TicketCategory, row.category) }}</template>
        </el-table-column>

        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="tagTypeOf(TicketPriority, row.priority)" size="small" disable-transitions>
              {{ labelOf(TicketPriority, row.priority) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <StatusTag :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="处理人" width="100">
          <template #default="{ row }">
            <span v-if="row.assigneeName">{{ row.assigneeName }}</span>
            <span v-else class="ticket-list__unassigned">未分派</span>
          </template>
        </el-table-column>

        <el-table-column label="SLA 解决" width="110">
          <template #default="{ row }">
            <SlaTag :state="row.slaResolutionState" />
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <template #empty>
          <el-empty
            :description="errorText ? '加载失败' : '暂无工单'"
            :image-size="90"
          />
        </template>
      </el-table>

      <!-- ========== 分页 ========== -->
      <div class="ticket-list__pager">
        <el-pagination
          :current-page="query.page"
          :page-size="query.size"
          :page-sizes="PAGE_SIZES"
          :total="total"
          background
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.ticket-list {
  padding: 16px;

  &__card {
    max-width: 1400px;
    margin: 0 auto;
  }

  &__filters {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    margin-bottom: 16px;
  }

  &__status {
    width: 160px;
  }

  &__keyword {
    width: 260px;
  }

  &__error {
    margin-bottom: 16px;
  }

  &__table {
    width: 100%;

    :deep(.el-table__row) {
      cursor: pointer;
    }
  }

  &__unassigned {
    color: var(--el-text-color-placeholder);
  }

  &__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }
}
</style>
