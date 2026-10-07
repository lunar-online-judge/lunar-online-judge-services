import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发期经 Vite 代理访问网关（同源，避免 CORS）；
// 生产建议由 OpenResty 同源托管静态资源并转发 /api。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
