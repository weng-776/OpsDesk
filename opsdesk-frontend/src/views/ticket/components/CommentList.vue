<script setup lang="ts">
/**
 * 处理记录（评论）—— 详情页区块 ⑥。
 *
 * ⚠️ **前端不对 `internal` 做任何过滤。**
 * 后端在 SQL 层就把 `internal = 1` 的行过滤掉了（EMPLOYEE 根本查不到），
 * 前端再加一层 `filter(c => !c.internal)` 只会掩盖「后端过滤失效」这种真问题，
 * 而且会让 AGENT 看不到自己的内部备注。**不要"好心"加这层过滤。**
 *
 * 评论接口是**分页**的（和附件/历史不同），默认一次 20 条，底部「加载更多」。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { addComment, comments } from '@/api/ticket'
import { PAGE_SIZE_MAX } from '@/types/common'
import { formatDateTime } from '@/utils/format'
import { BizError } from '@/utils/request'
import { usePermission } from '@/composables/usePermission'
import type { TicketCommentVO } from '@/types/ticket'

const props = defineProps<{
  /** 工单 ID */
  ticketId: number
}>()

/** 每次加载条数 */
const PAGE_SIZE = 20

const { hasRole, isAdmin } = usePermission()

/** 只有 AGENT / ADMIN 能看到「仅内部可见」开关（员工端不该给这个选项） */
const canInternal = computed<boolean>(() => isAdmin.value || hasRole('AGENT'))

const items = ref<TicketCommentVO[]>([])
const total = ref(0)
const nextPage = ref(1)
const loading = ref(false)
const errorText = ref('')

const content = ref('')
const internal = ref(false)
const submitting = ref(false)

/** 还有没有下一页 */
const hasMore = computed<boolean>(() => items.value.length < total.value)

/**
 * 加载评论。
 *
 * @param reset true 表示从头加载（首次进入 / 发表成功后刷新）
 */
async function load(reset = false): Promise<void> {
  if (loading.value) return
  loading.value = true
  errorText.value = ''
  try {
    const targetPage = reset ? 1 : nextPage.value
    const result = await comments(props.ticketId, { page: targetPage, size: PAGE_SIZE })
    items.value = reset ? (result.list ?? []) : items.value.concat(result.list ?? [])
    total.value = result.total ?? 0
    nextPage.value = targetPage + 1
  } catch (error) {
    errorText.value = (error as BizError).serverMessage
  } finally {
    loading.value = false
  }
}

/**
 * 发表成功后刷新。
 *
 * 评论是**正序**（旧的在前），新评论落在最后一页 —— 只重载第 1 页的话用户看不到自己刚发的那条。
 * 所以把 size 放宽到「已加载条数 + 1」（上限 50），一次把前面全部拉回来。
 */
async function reloadAfterSubmit(): Promise<void> {
  const size = Math.min(Math.max(items.value.length + 1, PAGE_SIZE), PAGE_SIZE_MAX)
  const result = await comments(props.ticketId, { page: 1, size })
  items.value = result.list ?? []
  total.value = result.total ?? 0
  nextPage.value = 2
}

async function submit(): Promise<void> {
  const text = content.value.trim()
  if (!text) {
    ElMessage.warning('请输入评论内容')
    return
  }
  if (submitting.value) return

  submitting.value = true
  try {
    // 员工端根本不渲染开关，这里再判一次角色，避免「隐藏了但值还在」导致越权意图
    const payload = canInternal.value && internal.value ? { content: text, internal: true } : { content: text }
    await addComment(props.ticketId, payload)
    content.value = ''
    internal.value = false
    ElMessage.success('评论已发表')
    await reloadAfterSubmit()
  } catch (error) {
    ElMessage.error((error as BizError).serverMessage)
  } finally {
    submitting.value = false
  }
}

onMounted(() => load(true))
</script>

<template>
  <el-card shadow="never" class="comment">
    <template #header>
      <div class="comment__header">
        <span class="comment__title">处理记录</span>
        <span class="comment__count">共 {{ total }} 条</span>
      </div>
    </template>

    <el-alert
      v-if="errorText"
      type="error"
      :closable="false"
      show-icon
      :title="`评论加载失败：${errorText}`"
      class="comment__error"
    />

    <div v-loading="loading" class="comment__list">
      <div v-if="items.length === 0 && !loading" class="comment__empty">
        <el-empty description="暂无处理记录" :image-size="70" />
      </div>

      <div v-for="item in items" :key="item.id" class="comment__item">
        <div class="comment__meta">
          <span class="comment__user">{{ item.userName }}</span>
          <el-tag v-if="item.internal" type="warning" size="small" disable-transitions>内部</el-tag>
          <span class="comment__time">{{ formatDateTime(item.createdAt) }}</span>
        </div>
        <p class="comment__content">{{ item.content }}</p>
      </div>

      <div v-if="hasMore" class="comment__more">
        <el-button link type="primary" :loading="loading" @click="load(false)">
          加载更多（已显示 {{ items.length }} / {{ total }}）
        </el-button>
      </div>
    </div>

    <el-divider />

    <div class="comment__form">
      <el-input
        v-model="content"
        type="textarea"
        :rows="3"
        maxlength="2000"
        show-word-limit
        placeholder="补充说明、处理进展，或回复对方"
        class="comment__input"
      />
      <div class="comment__form-footer">
        <!-- 员工端不显示这个开关：后端也会拒，但前端不该给出这个选项 -->
        <el-checkbox v-if="canInternal" v-model="internal" class="comment__internal">
          仅 IT 内部可见
        </el-checkbox>
        <span v-else class="comment__form-tip">提交后所有可见该工单的人都能看到</span>

        <el-button type="primary" :loading="submitting" @click="submit">发表评论</el-button>
      </div>
    </div>
  </el-card>
</template>

<style scoped lang="scss">
.comment {
  &__header {
    display: flex;
    align-items: baseline;
    gap: 10px;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
  }

  &__count {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  &__error {
    margin-bottom: 12px;
  }

  &__list {
    min-height: 60px;
  }

  &__empty {
    padding: 8px 0;
  }

  &__item {
    padding: 10px 0;
    border-bottom: 1px dashed var(--el-border-color-lighter);

    &:last-child {
      border-bottom: none;
    }
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
  }

  &__user {
    font-size: 13px;
    font-weight: 600;
  }

  &__time {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  &__content {
    margin: 0;
    color: var(--el-text-color-regular);
    font-size: 13px;
    line-height: 1.7;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__more {
    padding-top: 8px;
    text-align: center;
  }

  &__form-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 10px;
  }

  &__form-tip {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
</style>
