import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),

    // 自动导入 Vue / Router / Pinia 的组合式 API（ref、onMounted、useRouter …），
    // 同时把 ElMessage / ElMessageBox / ElNotification 三个「函数式组件」纳入自动导入，
    // 这样它们才会带上 Element Plus 的样式 —— 这是按需引入最常见的漏配。
    AutoImport({
      imports: [
        'vue',
        'vue-router',
        'pinia',
        { 'element-plus': ['ElMessage', 'ElMessageBox', 'ElNotification'] },
      ],
      resolvers: [ElementPlusResolver()],
      dts: 'src/auto-imports.d.ts',
    }),

    // 组件按需引入：模板里写 <el-button> 才会被打进包里。
    // 禁止在 main.ts 里 app.use(ElementPlus) —— 那是全量引入。
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts',
    }),
  ],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    port: 5173,
    // 后端没配 CORS，所有 /api 请求必须靠 Vite proxy 转发（不要去改后端加 CORS）
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
