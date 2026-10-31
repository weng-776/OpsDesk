<script setup lang="ts">
/**
 * 工单详情页（F7-06）。
 *
 * 本单只做**只读展示 + 内容交互**（评论 / 附件），流转按钮留给 F8-02。
 *
 * 数据策略：详情自己拉，三个子区块（附件 / 评论 / 历史）各自拉各自的 ——
 * 它们是三个独立接口、独立分页规则，塞进一个请求里只会让父组件变成数据中转站。
 *
 * ⚠️ 刷新恢复：只从路由拿 `id` 重新拉数据，**不通过 query 传工单对象**（刷新会丢）。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { detail } from '@/api/ticket'
import SlaTag from '@/components/SlaTag.vue'
import StatusTag from '@/components/StatusTag.vue'
import { ErrorCode } from '@/constants/errorCode'
import { TicketOperation, TicketPriority, TicketType, labelOf, tagTypeOf } from '@/constants/enums'
import { formatDateTime } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { TicketDetailVO } from '@/types/ticket'

import AttachmentList from './components/AttachmentList.vue'
import BasicInfo from './components/BasicInfo.vue'
import CommentList from './components/CommentList.vue'
import HistoryTimeline from './components/HistoryTimeline.vue'
import SlaPanel from './components/SlaPanel.vue'

const route = useRoute()
const router = useRouter()

/** 工单 ID 只从路由取（刷新后仍能重新拉数据） */
const ticketId = computed<number>(() => Number(route.params.id))

const ticket = ref<TicketDetailVO | null>(null)
const loading = ref(false)
/** 失败时的业务码：40301 / 40400 / 其他 */
const errorCode = ref<number | null>(null)
const errorText = ref('')

async function load(): Promise<void> {
  ticket.value = null
  errorCode.value = null
  errorText.value = ''

  if (!Number.isFinite(ticketId.value) || ticketId.value <= 0) {
    errorCode.value = ErrorCode.NOT_FOUND
    errorText.value = '工单 ID 不合法'
    return
  }

  loading.value = true
  try {
    ticket.value = await detail(ticketId.value)
  } catch (error) {
    const bizError = error as BizError
    errorCode.value = bizError.code
    errorText.value = bizError.serverMessage
  } finally {
    loading.value = false
  }
}

// 同一个组件实例内切换 id（比如从一张工单跳到另一张）也要重新拉
watch(ticketId, load, { immediate: true })

function backToList(): void {
  void router.push('/tickets')
}
</script>

<template>
  <div class="detail">
    <!-- ========== 加载中 ========== -->
    <div v-if="loading" class="detail__state">
      <el-skeleton :rows="8" animated />
    </div>

    <!-- ========== 40301：存在但无权 ========== -->
    <div v-else-if="errorCode === ErrorCode.DATA_SCOPE_DENIED" class="detail__state">
      <el-result icon="warning" title="无权访问该数据" :sub-title="errorText">
        <template #extra>
          <el-button type="primary" @click="backToList">返回我的工单</el-button>
        </template>
      </el-result>
    </div>

    <!-- ========== 40400：不存在 ========== -->
    <div v-else-if="errorCode === ErrorCode.NOT_FOUND" class="detail__state">
      <el-result icon="error" title="工单不存在或已删除" :sub-title="errorText">
        <template #extra>
          <el-button type="primary" @click="backToList">返回我的工单</el-button>
        </template>
      </el-result>
    </div>

    <!-- ========== 其他失败 ========== -->
    <div v-else-if="errorCode !== null" class="detail__state">
      <el-result icon="error" title="加载失败" :sub-title="errorText">
        <template #extra>
          <el-button @click="load">重试</el-button>
        </template>
      </el-result>
    </div>

    <!-- ========== 正常内容 ========== -->
    <template v-else-if="ticket">
      <!-- 头部 -->
      <el-card shadow="never" class="detail__header">
        <div class="detail__title-row">
          <span class="detail__no">{{ ticket.ticketNo }}</span>
          <div class="detail__tags">
            <StatusTag :status="ticket.status" />
            <el-tag
              :type="tagTypeOf(TicketPriority, ticket.priority)"
              disable-transitions
            >
              {{ labelOf(TicketPriority, ticket.priority) }}
            </el-tag>
            <SlaTag :state="ticket.slaResolutionState" />
          </div>
        </div>

        <h1 class="detail__title">{{ ticket.title }}</h1>

        <div class="detail__sub">
          <span>{{ labelOf(TicketType, ticket.type) }}</span>
          <span class="detail__dot">·</span>
          <span>创建于 {{ formatDateTime(ticket.createdAt) }}</span>
        </div>
      </el-card>

      <div class="detail__grid">
        <!-- 左栏 -->
        <div class="detail__main">
          <BasicInfo :ticket="ticket" />

          <el-card shadow="never">
            <template #header>
              <span class="detail__section-title">问题描述</span>
            </template>
            <p class="detail__desc">{{ ticket.description }}</p>
          </el-card>

          <AttachmentList :ticket-id="ticket.id" />
          <CommentList :ticket-id="ticket.id" />
          <HistoryTimeline :ticket-id="ticket.id" />
        </div>

        <!-- 右栏 -->
        <aside class="detail__side">
          <SlaPanel :ticket="ticket" />

          <!--
            动作区占位：F8-02 在这里接入真实按钮。
            本单只把后端算好的 canOperate 只读展示出来，证明这个字段是通的。
            ⚠️ canOperate 不是安全边界，每个流转接口后端都会独立校验。
          -->
          <el-card shadow="never">
            <template #header>
              <span class="detail__section-title">可用操作</span>
            </template>

            <div v-if="ticket.canOperate?.length" class="detail__ops">
              <el-tag
                v-for="operation in ticket.canOperate"
                :key="operation"
                :type="tagTypeOf(TicketOperation, operation)"
                size="small"
                disable-transitions
              >
                {{ labelOf(TicketOperation, operation) }}
              </el-tag>
            </div>
            <p v-else class="detail__ops-empty">当前状态下没有可执行的流转动作</p>

            <p class="detail__ops-tip">按钮由 F8-02 接入，此处仅只读展示后端返回的动作</p>
          </el-card>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.detail {
  padding: 16px;

  &__state {
    max-width: 880px;
    margin: 40px auto;
  }

  &__header {
    max-width: 1400px;
    margin: 0 auto 16px;
  }

  &__title-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
    margin-bottom: 10px;
  }

  &__no {
    color: var(--el-text-color-secondary);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
  }

  &__tags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  &__title {
    margin: 0 0 8px;
    font-size: 20px;
    font-weight: 600;
    line-height: 1.4;
  }

  &__sub {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  &__dot {
    opacity: 0.6;
  }

  &__grid {
    display: flex;
    align-items: flex-start;
    gap: 16px;
    max-width: 1400px;
    margin: 0 auto;
  }

  &__main {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 16px;
    min-width: 0;
  }

  &__side {
    display: flex;
    flex: 0 0 320px;
    flex-direction: column;
    gap: 16px;
    position: sticky;
    top: 16px;
  }

  &__section-title {
    font-size: 15px;
    font-weight: 600;
  }

  &__desc {
    margin: 0;
    color: var(--el-text-color-regular);
    font-size: 13px;
    line-height: 1.8;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__ops {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  &__ops-empty {
    margin: 0;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  &__ops-tip {
    margin: 12px 0 0;
    color: var(--el-text-color-placeholder);
    font-size: 12px;
  }
}

@media (max-width: 1080px) {
  .detail__grid {
    flex-direction: column;
  }

  .detail__side {
    position: static;
    flex: none;
    width: 100%;
  }
}
</style>
