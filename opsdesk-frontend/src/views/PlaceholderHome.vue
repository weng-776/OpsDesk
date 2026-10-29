<script setup lang="ts">
/**
 * 工程自检页（F7-01 占位页）。
 *
 * 唯一目的：在浏览器里证明这几件事是活的 ——
 * 1. Vite proxy 通到后端（/api/auth/me 会返回 401 + code 40100）；
 * 2. `utils/request.ts` 的双分支与 401 去重生效（提示一次、跳一次 /login）；
 * 3. Element Plus 按需引入有样式（不是裸文字）；
 * 4. 枚举常量 / 格式化函数可直接用。
 *
 * ⚠️ 这不是业务页面，F7-02 起会被真正的首页替换掉。
 */
import { computed, onMounted, ref } from 'vue'

import { me } from '@/api/auth'
import PagePlaceholder from '@/components/PagePlaceholder.vue'
import { TicketStatus, labelOf, tagTypeOf } from '@/constants/enums'
import { useUserStore } from '@/stores/user'
import { formatDateTime, formatFileSize, formatRelative } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { LoginUserVO } from '@/types/auth'

const userStore = useUserStore()

/** 自检结果 */
const result = ref<{ ok: boolean; text: string; user: LoginUserVO | null } | null>(null)
/** 请求进行中 */
const loading = ref(false)

/** token 预览：只显示前 12 位，避免整串 token 出现在截图里 */
const tokenPreview = computed(() => {
  const token = userStore.token
  return token ? `${token.slice(0, 12)}…` : '—'
})

/** 主动打一次 GET /api/auth/me，观察拦截器行为 */
async function checkMe(): Promise<void> {
  loading.value = true
  result.value = null
  try {
    const user = await me()
    result.value = { ok: true, text: `已登录：${user.nickname}（${user.username}）`, user }
  } catch (error) {
    // 拦截器已经弹过提示，这里只把结果落到页面上，方便对着 Network 面板核对
    const bizError = error as BizError
    result.value = { ok: false, text: `已拦截：code=${bizError.code}，${bizError.message}`, user: null }
  } finally {
    loading.value = false
  }
}

// 进页面就打一次：未登录时应该只出现 1 个 401、1 条提示、1 次跳转
onMounted(checkMe)

/** 格式化函数的演示数据 */
const demo = (() => {
  // 后端给的是「无时区后缀的 ISO 串」，用 toISOString() 会变成 UTC 导致差 8 小时，
  // 这里按本地时间拼一个同样格式的串，才是后端真实给的样子。
  const toLocalIso = (date: Date): string => {
    const pad = (n: number): string => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
      date.getHours(),
    )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }
  return {
    now: formatDateTime(toLocalIso(new Date())),
    threeMinutesAgo: formatRelative(toLocalIso(new Date(Date.now() - 3 * 60_000))),
    fileSize: formatFileSize(1_258_291),
  }
})()
</script>

<template>
  <PagePlaceholder
    title="F7-01 工程自检"
    description="Vue3 + TypeScript + Vite + Element Plus 地基已就绪。本页只验证请求层、类型体系、枚举常量与格式化函数是否可用，不含任何业务功能。"
  >
    <template #extra>
      <el-button type="primary" :loading="loading" @click="checkMe">请求 /api/auth/me</el-button>
    </template>

    <div class="check">
      <div class="check__row">
        <span class="check__label">登录态</span>
        <el-tag :type="userStore.isLoggedIn ? 'success' : 'info'" disable-transitions>
          {{ userStore.isLoggedIn ? '已登录' : '未登录' }}
        </el-tag>
        <span class="check__value">{{ tokenPreview }}</span>
      </div>

      <div class="check__row">
        <span class="check__label">接口结果</span>
        <el-tag :type="result ? (result.ok ? 'success' : 'danger') : 'info'" disable-transitions>
          {{ result ? (result.ok ? '成功' : '失败') : '待请求' }}
        </el-tag>
        <span class="check__value">{{ result?.text ?? '—' }}</span>
      </div>

      <div class="check__row">
        <span class="check__label">当前时间</span>
        <span class="check__value">{{ demo.now }}（formatDateTime）</span>
      </div>

      <div class="check__row">
        <span class="check__label">相对时间</span>
        <span class="check__value">{{ demo.threeMinutesAgo }}（formatRelative）</span>
      </div>

      <div class="check__row">
        <span class="check__label">文件大小</span>
        <span class="check__value">{{ demo.fileSize }}（formatFileSize）</span>
      </div>

      <div class="check__row check__row--top">
        <span class="check__label">枚举常量</span>
        <div class="check__tags">
          <el-tag
            v-for="item in TicketStatus"
            :key="item.value"
            :type="tagTypeOf(TicketStatus, item.value)"
            disable-transitions
          >
            {{ labelOf(TicketStatus, item.value) }}
          </el-tag>
        </div>
      </div>
    </div>
  </PagePlaceholder>
</template>

<style scoped lang="scss">
.check {
  display: flex;
  flex-direction: column;
  gap: 14px;

  &__row {
    display: flex;
    align-items: center;
    gap: 12px;

    &--top {
      align-items: flex-start;
    }
  }

  &__label {
    flex: 0 0 72px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  &__value {
    color: var(--el-text-color-regular);
    font-size: 13px;
    word-break: break-all;
  }

  &__tags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>
