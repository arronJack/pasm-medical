import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 代理到业务层。前端**绝不直连认知服务**（那边的令牌是管理令牌）
    proxy: { '/api': { target: 'http://127.0.0.1:8081', changeOrigin: true } },
  },
})
