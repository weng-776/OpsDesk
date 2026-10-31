<script setup lang="ts">
/**
 * 应用根组件：只放路由出口 + 全局 locale 配置。
 *
 * ⚠️ `el-config-provider` 不能省：Element Plus 默认 locale 是**英文**，
 * 不配的话分页器会显示 `Total 3` / `10/page`、日期面板是英文 ——
 * 中文后台里冒出英文，用户第一眼就觉得没做完。
 *
 * 按需引入模式下没有 `app.use(ElementPlus, { locale })` 这条路，
 * 只能用 ConfigProvider 包一层。
 */
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
