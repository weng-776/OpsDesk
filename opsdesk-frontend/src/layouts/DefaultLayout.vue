<script setup lang="ts">
/**
 * 全局布局（F7-03）：左侧动态菜单 + 顶栏 + 内容区。
 *
 * 菜单**不写死**：来自 `usePermission().menus`，即「菜单树 ∩ 当前用户 permissions」。
 * 角色差异完全由后端下发的权限码决定，这里没有任何 `if (role === ...)`。
 *
 * 权限码来源：`GET /api/auth/me` 的 `permissions`（F7-02 已保证每次刷新回源）。
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Bell, Expand, Fold } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { usePermission } from '@/composables/usePermission'
import { Role, labelOf, tagTypeOf } from '@/constants/enums'
import { MENU_ICONS, MENU_TREE } from '@/router/menu'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { menus } = usePermission()

/** 侧边栏折叠状态 */
const collapsed = ref(false)
/** 登出请求进行中 */
const logoutLoading = ref(false)

/**
 * 当前高亮的菜单项。
 *
 * 详情页（`/tickets/123`）不在菜单里，直接拿 `route.path` 匹配会一个都不高亮，
 * 所以退化成「最长前缀匹配」：`/tickets/123` → 高亮 `/tickets`。
 */
const activeMenu = computed<string>(() => {
  const leaves: string[] = []
  const walk = (nodes: typeof MENU_TREE): void => {
    for (const node of nodes) {
      if (node.children) walk(node.children)
      else leaves.push(node.path)
    }
  }
  walk(MENU_TREE)

  if (leaves.includes(route.path)) return route.path

  const prefix = leaves
    .filter((path) => route.path.startsWith(`${path}/`))
    .sort((a, b) => b.length - a.length)[0]

  return prefix ?? route.path
})

/** 主角色（顶栏只展示第一个） */
const primaryRole = computed<string>(() => userStore.roles[0] ?? '')

/**
 * 面包屑：分组名 + 当前页名。
 *
 * 用 `activeMenu`（已做过前缀匹配）去找分组，而不是 `route.path` ——
 * 详情页 `/tickets/123` 不在菜单里，直接比 `route.path` 会找不到分组，只剩一个孤零零的页名。
 */
const breadcrumbs = computed<string[]>(() => {
  const list: string[] = []
  const group = MENU_TREE.find((node) =>
    node.children?.some((child) => child.path === activeMenu.value),
  )
  if (group) list.push(group.title)
  if (route.meta.title) list.push(route.meta.title)
  return list
})

/** 顶栏下拉：个人信息暂未实现，先给明确反馈而不是静默无反应 */
function onCommand(command: string): void {
  if (command === 'profile') {
    ElMessage.info('个人信息页尚未实现，将在后续工单交付')
    return
  }
  if (command === 'logout') void handleLogout()
}

/** 登出：调接口拉黑 token → 清本地 → 回登录页（失败也清，见 store.logout 的 finally） */
async function handleLogout(): Promise<void> {
  logoutLoading.value = true
  try {
    await userStore.logout()
  } finally {
    logoutLoading.value = false
    await router.replace('/login')
  }
}
</script>

<template>
  <div class="layout" :class="{ 'layout--collapsed': collapsed }">
    <!-- ========== 侧边栏 ========== -->
    <aside class="layout__aside">
      <div class="layout__logo">
        <span class="layout__logo-text">{{ collapsed ? 'OD' : 'OpsDesk' }}</span>
      </div>

      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="layout__menu"
      >
        <template v-for="node in menus" :key="node.path">
          <!-- 分组：index 用权限码，避免与子项的路径 index 冲突 -->
          <el-sub-menu v-if="node.children" :index="node.perm">
            <template #title>
              <el-icon><component :is="MENU_ICONS[node.icon]" /></el-icon>
              <span>{{ node.title }}</span>
            </template>
            <el-menu-item v-for="child in node.children" :key="child.path" :index="child.path">
              <el-icon><component :is="MENU_ICONS[child.icon]" /></el-icon>
              <template #title>{{ child.title }}</template>
            </el-menu-item>
          </el-sub-menu>

          <el-menu-item v-else :index="node.path">
            <el-icon><component :is="MENU_ICONS[node.icon]" /></el-icon>
            <template #title>{{ node.title }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </aside>

    <!-- ========== 右侧主体 ========== -->
    <div class="layout__body">
      <header class="layout__header">
        <div class="layout__header-left">
          <el-button link class="layout__collapse" @click="collapsed = !collapsed">
            <el-icon :size="18">
              <Fold v-if="!collapsed" />
              <Expand v-else />
            </el-icon>
          </el-button>

          <el-breadcrumb separator="/" class="layout__breadcrumb">
            <el-breadcrumb-item v-for="(item, index) in breadcrumbs" :key="index">
              {{ item }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="layout__header-right">
          <!-- 通知入口占位：TODO(D6-03) 通知模块交付后接 /api/notifications/unread-count -->
          <el-badge :value="0" class="layout__badge">
            <el-icon :size="18" class="layout__bell"><Bell /></el-icon>
          </el-badge>

          <el-dropdown trigger="click" @command="onCommand">
            <span class="layout__user">
              <span class="layout__nickname">{{ userStore.user?.nickname ?? '—' }}</span>
              <el-tag
                v-if="primaryRole"
                :type="tagTypeOf(Role, primaryRole)"
                size="small"
                disable-transitions
              >
                {{ labelOf(Role, primaryRole) }}
              </el-tag>
              <span class="layout__dept">{{ userStore.user?.departmentName ?? '—' }}</span>
              <el-icon :size="12"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人信息</el-dropdown-item>
                <el-dropdown-item command="logout" divided :disabled="logoutLoading">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="layout__main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
$aside-width: 220px;
$aside-width-collapsed: 64px;

.layout {
  display: flex;
  height: 100vh;
  overflow: hidden;

  &__aside {
    display: flex;
    flex-direction: column;
    flex: 0 0 $aside-width;
    width: $aside-width;
    background-color: var(--el-bg-color);
    border-right: 1px solid var(--el-border-color-lighter);
    transition: width 0.2s, flex-basis 0.2s;
  }

  &--collapsed &__aside {
    flex-basis: $aside-width-collapsed;
    width: $aside-width-collapsed;
  }

  &__logo {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 56px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  &__logo-text {
    font-size: 16px;
    font-weight: 700;
    letter-spacing: 0.5px;
    color: var(--el-color-primary);
  }

  &__menu {
    flex: 1;
    overflow-x: hidden;
    overflow-y: auto;
    border-right: none;

    // 折叠后 el-menu 自己会收窄，这里只保证不出现横向滚动
    &:not(.el-menu--collapse) {
      width: $aside-width;
    }
  }

  &__body {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
    overflow: hidden;
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex: 0 0 56px;
    height: 56px;
    padding: 0 20px;
    background-color: var(--el-bg-color);
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  &__header-left {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
  }

  &__header-right {
    display: flex;
    align-items: center;
    gap: 20px;
  }

  &__collapse {
    padding: 4px;
  }

  &__breadcrumb {
    font-size: 13px;
  }

  &__bell {
    color: var(--el-text-color-regular);
    cursor: pointer;
  }

  &__badge {
    display: flex;
    align-items: center;

    // ⚠️ 不要在这里覆盖 transform —— el-badge 的默认 transform 负责把角标
    // 定位到右上角（translate(50%, -50%)），覆盖掉就会压住图标。
    // 需要微调位置用 el-badge 的 :offset="[x, y]"。
  }

  &__user {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--el-text-color-regular);
    font-size: 13px;
    cursor: pointer;
    outline: none;
  }

  &__nickname {
    font-weight: 500;
    color: var(--el-text-color-primary);
  }

  &__dept {
    color: var(--el-text-color-secondary);
  }

  &__main {
    flex: 1;
    overflow: auto;
    background-color: var(--el-bg-color-page);
  }
}
</style>
