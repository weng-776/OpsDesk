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
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { me } from '@/api/auth'
import PagePlaceholder from '@/components/PagePlaceholder.vue'
import { Role, TicketStatus, labelOf, tagTypeOf } from '@/constants/enums'
import { useUserStore } from '@/stores/user'
import { formatDateTime, formatFileSize, formatRelative } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { LoginUserVO } from '@/types/auth'

const router = useRouter()
const userStore = useUserStore()

/** 登出请求进行中 */
const logoutLoading = ref(false)

/**
 * 登出：调接口把 token 拉黑 → 清本地 → 回登录页。
 *
 * `store.logout()` 内部用 finally 清本地，所以即使后端挂了 / token 已过期，
 * 也不会把用户卡在「点了登出还在登录态」的怪状态。
 */
async function handleLogout(): Promise<void> {
  logoutLoading.value = true
  try {
    await userStore.logout()
  } finally {
    logoutLoading.value = false
    await router.replace('/login')
  }
}

/** 主角色（顶栏只展示第一个，完整角色列表留给 F7-03 的用户菜单） */
const primaryRole = computed<string>(() => userStore.roles[0] ?? '')

/**
 * 自检结果。
 *
 * ⚠️ 初始值直接取 store 里已回源的用户，**不再 onMounted 自己打一次** ——
 * 路由守卫进页面前已经调过 `/me`（`ensureProfile`），这里再打一次就是重复请求。
 */
const result = ref<{ ok: boolean; text: string; user: LoginUserVO | null } | null>(
  userStore.user
    ? {
        ok: true,
        text: `已登录：${userStore.user.nickname}（${userStore.user.username}）`,
        user: userStore.user,
      }
    : null,
)
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

/**
 * 格式化函数的演示数据
 */
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
  <div class="home">
    <!--
      极简顶栏：F7-02 要证明「登录成功 → 顶栏显示昵称」。
      真正的布局（侧边栏 + 菜单 + 用户下拉）是 F7-03，这里只是临时壳子。
    -->
    <header class="topbar">
      <span class="topbar__brand">OpsDesk</span>
      <div class="topbar__right">
        <span class="topbar__user">{{ userStore.user?.nickname ?? '未登录' }}</span>
        <el-tag v-if="primaryRole" :type="tagTypeOf(Role, primaryRole)" size="small" disable-transitions>
          {{ labelOf(Role, primaryRole) }}
        </el-tag>
        <el-button link type="primary" :loading="logoutLoading" @click="handleLogout">
          退出登录
        </el-button>
      </div>
    </header>

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
  </div>
</template>

<style scoped lang="scss">
.home {
  min-height: 100vh;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  background-color: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-lighter);

  &__brand {
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 0.5px;
  }

  &__right {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__user {
    color: var(--el-text-color-regular);
    font-size: 13px;
  }
}

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
