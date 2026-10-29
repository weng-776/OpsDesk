import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import '@/styles/index.scss'

// ⚠️ 不要 app.use(ElementPlus)：Element Plus 走 unplugin 按需引入，
// 组件在模板里用到才会被打进包；全量引入会让首屏体积翻几倍。
const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
