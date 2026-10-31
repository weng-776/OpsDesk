import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import { perm } from './directives/perm'
import router from './router'
import { setupRouterGuard } from './router/guard'
import '@/styles/index.scss'

// ⚠️ 不要 app.use(ElementPlus)：Element Plus 走 unplugin 按需引入，
// 组件在模板里用到才会被打进包；全量引入会让首屏体积翻几倍。
const app = createApp(App)

app.use(createPinia())

// 按钮级权限指令：v-perm="'ticket:assign'"
// 指令只能全局注册（组件里 import 不到指令名）
app.directive('perm', perm)

// 守卫必须在 app.use(router) 之前装 —— router 安装时会立刻发起首次导航，
// 装晚了首次导航就已经跑完，守卫拦不住（等于「未登录可进所有页面」）。
setupRouterGuard(router)
app.use(router)

app.mount('#app')
