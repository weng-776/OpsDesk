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
import { TicketPriority, TicketType, labelOf, tagTypeOf } from '@/constants/enums'
import { formatDateTime } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { TicketDetailVO } from '@/types/ticket'

import AttachmentList from './components/AttachmentList.vue'
import BasicInfo from './components/BasicInfo.vue'
import CommentList from './components/CommentList.vue'
import HistoryTimeline from './components/HistoryTimeline.vue'
import SlaPanel from './components/SlaPanel.vue'
import TicketActions from './components/TicketActions.vue'

const route = useRoute()
const router = useRouter()

/** 工单 ID 只从路由取（刷新后仍能重新拉数据） */
const ticketId = computed<number>(() => Number(route.params.id))

const ticket = ref<TicketDetailVO | null>(null)
const loading = ref(false)
/** 失败时的业务码：40301 / 40400 / 其他 */
const errorCode = ref<number | null>(null)
const errorText = ref('')

/**
 * 子区块重挂令牌。
 *
 * 动作成功后详情 VO 换了，但评论 / 附件 / 历史三个子组件各自持有数据，
 * 光换父组件的 VO 它们不会更新（**历史时间线必须出现新的一条**）。
 * 每成功一次动作就 +1，子组件 `:key` 变化 → 重新挂载 → 各自重新拉数据。
 */
const reloadToken = ref(0)

/** 描述是否展开（长文本默认折叠，否则一张长描述会把整个处理页撑爆） */
const descExpanded = ref(false)
/** 超过这个长度才折叠 —— 短描述折起来反而多一次点击 */
const DESC_COLLAPSE_THRESHOLD = 200
const isDescLong = computed<boolean>(
  () => (ticket.value?.description?.length ?? 0) > DESC_COLLAPSE_THRESHOLD,
)

async function load(): Promise<void> {
  ticket.value = null
  descExpanded.value = false
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

/**
 * 动作成功：直接用响应体里的最新快照，**不再发一次详情请求**（§8.12 明确返回最新 VO）。
 * 同时 bump 令牌，让三个子区块重新拉数据。
 */
function onActionUpdated(next: TicketDetailVO): void {
  ticket.value = next
  reloadToken.value += 1
}

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
            <div
              class="detail__desc-wrap"
              :class="{ 'is-collapsed': isDescLong && !descExpanded }"
            >
              <p class="detail__desc">{{ ticket.description }}</p>
            </div>
            <el-button
              v-if="isDescLong"
              link
              type="primary"
              class="detail__desc-toggle"
              @click="descExpanded = !descExpanded"
            >
              {{ descExpanded ? '收起' : '展开全文' }}
            </el-button>
          </el-card>

          <AttachmentList :key="`att-${reloadToken}`" :ticket-id="ticket.id" />
          <CommentList :key="`cmt-${reloadToken}`" :ticket-id="ticket.id" />
          <HistoryTimeline :key="`his-${reloadToken}`" :ticket-id="ticket.id" />
        </div>

        <!-- 右栏 -->
        <aside class="detail__side">
          <SlaPanel :ticket="ticket" />

          <el-card shadow="never">
            <template #header>
              <span class="detail__section-title">可用操作</span>
            </template>

            <!--
              ⚠️ canOperate 是渲染依据，不是安全边界（§16.8）：
              前端只保证「不显示必然失败的按钮」，后端每个流转接口仍独立校验。
            -->
            <TicketActions :ticket="ticket" @updated="onActionUpdated" @stale="load" />
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

  &__desc-wrap {
    position: relative;

    // 折叠时截断 + 底部渐隐，暗示"还有内容"
    &.is-collapsed {
      max-height: 120px;
      overflow: hidden;

      &::after {
        content: '';
        position: absolute;
        right: 0;
        bottom: 0;
        left: 0;
        height: 40px;
        background: linear-gradient(to bottom, transparent, var(--el-bg-color));
      }
    }
  }

  &__desc-toggle {
    margin-top: 6px;
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
