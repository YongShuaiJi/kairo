# Runtime Mock Platform Web

Runtime Mock 的独立中央管理前端。它使用 Next.js 15、React 19、TypeScript、Tailwind CSS、
shadcn/ui 风格源码组件、Lucide 图标、TanStack Query 和 Monaco Editor。

## 本地运行

```bash
cp .env.example .env.local
npm install
npm run dev
```

访问 `http://127.0.0.1:3000`。

真实模式默认连接 `http://127.0.0.1:18280`。登录时输入 Platform API 签发的 opaque Bearer
Token；Token 会被 Next.js 服务端加密后写入 HttpOnly Cookie，浏览器脚本无法读取。

## Demo 模式

当 Platform API 尚未启动或需要单独验收前端时：

```bash
RUNTIME_MOCK_WEB_DEMO_MODE=true \
RUNTIME_MOCK_WEB_SESSION_KEY=runtime-mock-demo-session-key-32 \
npm run dev
```

使用 Token `runtime-mock-demo` 登录。页面会持续显示 Demo 标识，所有写操作只返回演示结果。

## 质量检查

```bash
npm run api:generate
npm run typecheck
npm run lint
npm test
npm run build
npm run test:e2e
```

`docs/api/platform-openapi.yaml` 是 API 类型来源。真实模式已接入 `/auth/me`、脚本校验与
试运行、聚合仪表盘、统一分页/详情查询、目标搜索、录制批次与事件等 Platform API，不使用
前端假数据掩盖后端错误。Demo 模式仅用于隔离的前端开发和 Playwright 验收。
