<script setup lang="ts">
/**
 * 员工端首页（F7-07）—— 登录后的落地页。
 *
 * ⚠️ **不调 `/api/dashboard/*`**：
 * 那两个接口的注解权限码都是 `dashboard:view`（三个角色都有），真正的角色限制在 **Service 层** ——
 * EMPLOYEE 调任意一个都会得到 `40300`，AGENT 调 `/overview` 也是 40300。
 * 所以员工端的统计只能用 `/api/tickets/mine` 自己数。
 *
 * ⚠️ **不做「一个状态一个请求」**：
 * 后端 `status` 只支持单值，但没必要把 8 个状态全打一遍。这里只要 4 个请求：
 * 1 个拿「总数 + 最近 5 条」（size=5 的返回里同时有 total 和 list），
 * 3 个拿关键状态（ASSIGNED / IN_PROGRESS / WAITING_CONFIRM）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { mine } from '@/api/ticket'
import StatusTag from '@/components/StatusTag.vue'
import { usePermission } from '@/composables/usePermission'
import { formatRelative } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { TicketListVO } from '@/types/ticket'

const router = useRouter()
const { hasPerm } = usePermission()

/** 最近工单条数 */
const RECENT_SIZE = 5

const stats = ref({ total: 0, processing: 0, waitingConfirm: 0 })
const recent = ref<TicketListVO[]>([])
const loading = ref(false)
const errorText = ref('')

/** 能不能提交工单（AGENT 没有 menu:ticket:create，不该看到这个入口） */
const canCreate = computed<boolean>(() => hasPerm('menu:ticket:create'))

/** 问候语 */
const greeting = computed<string>(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

/** 新用户：一张工单都没有 */
const isEmpty = computed<boolean>(() => !loading.value && !errorText.value && stats.value.total === 0)

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    // 4 个请求并发：不要为了凑数字把 8 个状态各打一遍
    const [all, assigned, inProgress, waitingConfirm] = await Promise.all([
      mine({ page: 1, size: RECENT_SIZE }),
      mine({ status: 'ASSIGNED', page: 1, size: 1 }),
      mine({ status: 'IN_PROGRESS', page: 1, size: 1 }),
      mine({ status: 'WAITING_CONFIRM', page: 1, size: 1 }),
    ])

    stats.value = {
      total: Number(all.total ?? 0),
      // 「处理中」= 已分派 + 处理中。REOPENED（已重新打开）数量极少，未单列
      processing: Number(assigned.total ?? 0) + Number(inProgress.total ?? 0),
      waitingConfirm: Number(waitingConfirm.total ?? 0),
    }
    recent.value = all.list ?? []
  } catch (error) {
    errorText.value = (error as BizError).serverMessage
  } finally {
    loading.value = false
  }
}

function goCreate(): void {
  void router.push('/tickets/create')
}

function goDetail(ticket: TicketListVO): void {
  void router.push({ name: 'TicketDetail', params: { id: ticket.id } })
}

function goList(): void {
  void router.push('/tickets')
}

onMounted(load)
</script>

<template>
  <div class="home">
    <!-- 问候 -->
    <div class="home__greeting">
      <span class="home__hello">{{ greeting }}</span>
      <span class="home__sub">这里是你的工单概览</span>
    </div>

    <el-alert
      v-if="errorText"
      type="error"
      :closable="false"
      show-icon
      :title="`加载失败：${errorText}`"
      class="home__error"
    >
      <template #default>
        <el-button size="small" @click="load">重试</el-button>
      </template>
    </el-alert>

    <!-- 统计卡片 -->
    <div v-loading="loading" class="home__stats">
      <el-card shadow="never" class="stat">
        <div class="stat__value">{{ stats.total }}</div>
        <div class="stat__label">我的工单总数</div>
      </el-card>
      <el-card shadow="never" class="stat stat--processing">
        <div class="stat__value">{{ stats.processing }}</div>
        <div class="stat__label">处理中</div>
      </el-card>
      <el-card shadow="never" class="stat stat--waiting">
        <div class="stat__value">{{ stats.waitingConfirm }}</div>
        <div class="stat__label">待我确认</div>
      </el-card>
    </div>

    <!-- 快捷入口 -->
    <el-card v-if="canCreate" shadow="never" class="home__quick">
      <div class="quick">
        <div class="quick__text">
          <div class="quick__title">遇到问题？提交一张工单</div>
          <div class="quick__desc">描述越具体，IT 定位越快</div>
        </div>
        <el-button type="primary" size="large" @click="goCreate">提交新工单</el-button>
      </div>
    </el-card>

    <!-- 空态：一张工单都没有 -->
    <el-card v-if="isEmpty" shadow="never" class="home__empty">
      <el-empty description="还没有工单">
        <p class="home__empty-tip">点击下方按钮，提交你的第一个问题</p>
        <el-button v-if="canCreate" type="primary" @click="goCreate">提交第一个工单</el-button>
        <el-button v-else @click="goList">查看工单列表</el-button>
      </el-empty>
    </el-card>

    <!-- 最近工单 -->
    <el-card v-else shadow="never" class="home__recent">
      <template #header>
        <div class="home__recent-header">
          <span class="home__recent-title">最近工单</span>
          <el-button link type="primary" @click="goList">查看全部</el-button>
        </div>
      </template>

      <div v-loading="loading" class="recent">
        <div v-if="recent.length === 0 && !loading" class="recent__empty">
          <el-empty description="暂无工单" :image-size="70" />
        </div>

        <div
          v-for="ticket in recent"
          :key="ticket.id"
          class="recent__item"
          @click="goDetail(ticket)"
        >
          <div class="recent__main">
            <span class="recent__no">{{ ticket.ticketNo }}</span>
            <span class="recent__title">{{ ticket.title }}</span>
          </div>
          <div class="recent__meta">
            <StatusTag :status="ticket.status" />
            <span class="recent__time">{{ formatRelative(ticket.createdAt) }}</span>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.home {
  max-width: 1080px;
  margin: 0 auto;
  padding: 20px 16px 32px;

  &__greeting {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin-bottom: 16px;
  }

  &__hello {
    font-size: 20px;
    font-weight: 600;
  }

  &__sub {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  &__error {
    margin-bottom: 16px;
  }

  &__stats {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 16px;
    margin-bottom: 16px;
  }

  &__quick {
    margin-bottom: 16px;
  }

  &__recent-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__recent-title {
    font-size: 15px;
    font-weight: 600;
  }

  &__empty-tip {
    margin: 0 0 14px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}

.stat {
  text-align: center;

  &__value {
    font-size: 28px;
    font-weight: 700;
    line-height: 1.2;
  }

  &__label {
    margin-top: 6px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  &--processing &__value {
    color: var(--el-color-primary);
  }

  &--waiting &__value {
    color: var(--el-color-warning);
  }
}

.quick {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;

  &__title {
    font-size: 15px;
    font-weight: 600;
  }

  &__desc {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.recent {
  &__item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 12px 4px;
    border-bottom: 1px dashed var(--el-border-color-lighter);
    cursor: pointer;

    &:last-child {
      border-bottom: none;
    }

    &:hover {
      background-color: var(--el-fill-color-light);
    }
  }

  &__main {
    display: flex;
    align-items: baseline;
    gap: 10px;
    min-width: 0;
  }

  &__no {
    flex: 0 0 auto;
    color: var(--el-text-color-secondary);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }

  &__title {
    overflow: hidden;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__meta {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 10px;
  }

  &__time {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  &__empty {
    padding: 8px 0;
  }
}

@media (max-width: 720px) {
  .home__stats {
    grid-template-columns: 1fr;
  }

  .quick {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
