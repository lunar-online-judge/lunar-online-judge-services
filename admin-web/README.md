# Lunar OJ 管理后台（admin-web）

面向老师/管理员的管理控制台。技术栈：React 19 + React Router 7 + HeroUI 2.7 + Zustand 5 + TanStack Query 5 / Table 8 + Tailwind CSS 3.4，构建工具 Vite 6。

## 快速开始

```bash
npm install
npm run dev        # http://localhost:5173（/api 代理到网关 http://localhost:8080）
npm run build      # 生产构建（tsc + vite build）
```

前提：网关（8080）与对应后端服务已启动；登录账号需 ADMIN/TEACHER 角色（开发种子 `admin`/`admin123`）。

## 功能

- **登录**：JWT 登录（Auth-service），角色守卫（ADMIN/TEACHER），401 自动跳登录
- **考试管理**：分页列表/新建（时间窗口）/提交审核/发布/归档；详情页含**学生名单**（添加/重置密码/移出）与**公告**（发布、送达/确认状态）
- **题目管理**：分页列表/新建（测试点编辑器）/版本管理/发布/测试点查看（仅管理端，考生 API 永不返回测试点）
- **风险事件**：分页过滤（等级/审核状态）、15 秒轮询、人工审核（OPEN→ACKNOWLEDGED→CONFIRMED/DISMISSED）

## 结构

```
src/
├── lib/api.ts        # 统一 API 封装（ApiResponse 信封解包、Bearer 注入、401 处理）
├── stores/auth.ts    # Zustand：令牌与角色（localStorage 持久化）
├── stores/toast.ts   # 轻量 Toast
├── router.tsx        # React Router 7：登录页 / 403 / 受保护的管理路由
├── layouts/AdminLayout.tsx
└── pages/            # 概览 / 考试 / 题目 / 风险事件
```

## 约定

- API 响应按后端统一信封解包；错误以稳定 `code` + `message` 呈现
- 生产部署建议：OpenResty 同源托管静态资源并转发 `/api`，无需 CORS
