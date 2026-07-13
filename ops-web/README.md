# powerbank-ops-web

powerbank 运营端（平台超管 + 租户后台）。技术栈对齐 `ai-boss/ops-web`：**Next.js 16 + React 19 + Tailwind 4 + shadcn 风格组件 + TanStack Query**（见 [ADR-008](../docs/technical/ADR/ADR-008-前端技术栈.md)）。

## 运行

```bash
pnpm install        # 或 npm install
cp .env.local.example .env.local   # 默认 NEXT_PUBLIC_USE_MOCK=1，无后端即可跑
pnpm dev            # http://localhost:3000
```

登录页选任意角色即可进入（MVP mock 登录）。接后端时把 `.env.local` 的 `NEXT_PUBLIC_USE_MOCK=0`、`NEXT_PUBLIC_API_BASE=/api`。

## 已实现模块

| 模块 | 路径 | 说明 |
|------|------|------|
| 登录 | `/login` | mock 登录 + 角色（RBAC 演示）|
| 工作台 | `/` | KPI + 7 日趋势（recharts）|
| 设备管理 | `/devices`, `/devices/detail` | 列表/筛选/分页 + 详情仓位 + 远程弹出/重启指令 |
| 点位商户 | `/locations` | 点位 / 场地方 / 合同（tabs）|
| 订单 | `/orders` | 列表/筛选 + 详情抽屉 + 客服干预（退款/强制归还/补偿）|
| 工单 | `/work-orders` | 列表/筛选 + 派单抽屉 |
| 用户 | `/users` | C 端用户 + 拉黑/解除 |
| 营销 | `/marketing` | 优惠券 |
| 财务分润 | `/finance` | 分润规则 / 结算单 / 提现审核（tabs）|
| 租户管理 | `/tenants` | 平台超管；列表 + 租户配置抽屉 |
| 员工权限 | `/employees` | 员工 / 角色 / 操作审计（tabs）|
| 供应商接入 | `/system/vendors` | 供应商列表 + 接入配置抽屉 |

## API 架构（一键切换 mock ↔ 真实后端）

**单一契约 + 两实现 + 一开关**，页面只依赖 `api`，不含任何 `if(USE_MOCK)`：

```
lib/api/
  contract.ts     interface Api { ... }        ← 唯一契约（页面依赖它）
  mock.ts         mockApi: Api                  ← 走 lib/mock/db 内存数据
  http.ts         httpApi: Api                  ← 走真实后端（端点对齐 docs/api）
  http-client.ts  fetch 封装（Result<T> 拆包 + AuthHeaders）
  index.ts        export const api = USE_MOCK ? mockApi : httpApi   ← 唯一开关
lib/mock/db.ts    完整 mock 数据集（全域）+ 通用 paginate/CRUD helper
```

切真实后端：`.env.local` 设 `NEXT_PUBLIC_USE_MOCK=0` + `NEXT_PUBLIC_API_BASE=/api`。页面零改动。
Header 右上角 `Mock 数据` 徽章指示当前模式。

## 结构（对齐 ai-boss/ops-web）

```
app/            Next App Router 页面（静态导出）
components/ui/  shadcn 风格组件（button/card/table/data-table/drawer/tabs/…）
components/layout/  app-shell / sidebar / header
lib/api/        API 契约 + mock/http 实现 + 开关
lib/mock/db.ts  完整 mock 数据集
lib/{auth,permissions,nav,types,utils}.ts
```

契约（`lib/types.ts` + `lib/api/contract.ts`）镜像 `docs/api` 与 `powerbank-common-api`；后端就绪可由 openapi 生成替换。
