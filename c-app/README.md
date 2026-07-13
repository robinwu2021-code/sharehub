# Powerbank C 端 App（uni-app）

MENA 终端消费者充电宝租借 App。**App 优先（P0）**、微信小程序次要（P1），一套 uni-app（Vue3+TS+Vite）代码，条件编译隔离端差异。

> 方案：[../docs/technical/TDD-c-app.md](../docs/technical/TDD-c-app.md) · 需求：[../docs/requirements/C端功能清单.md](../docs/requirements/C端功能清单.md)

## 技术栈
uni-app（Vue3+TS+Vite）· wot-design-uni · UnoCSS(preset-wind via unocss-applet) · Pinia · vue-i18n（ar/en + RTL）

## 运行
```bash
npm install
npm run dev:h5          # H5 预览（默认 http://localhost:5173）
npm run build:h5        # H5 生产构建
npm run dev:mp-weixin   # 微信小程序（P1）
npm run type-check      # vue-tsc 类型检查
```

## mock ↔ 真实后端一键切换（核心地基）
页面统一 `import { api } from "@/api"` 调 `api.xxx()`，不感知 mock/真实（复刻 ops-web）。
切换靠 `.env` 一处开关，页面零改：
```bash
VITE_USE_MOCK=1                        # 默认：走内存 mock，UI 脱离后端跑通
# VITE_USE_MOCK=0                      # 切真实后端 /mp/**
# VITE_API_BASE=http://localhost:8080  # 跨源本地开发；同源反代留空
```

## 目录（M0 已落地）
```
src/
├── api/          唯一契约 McpApi + mock/http/一键切换（index.ts）
├── types/        契约镜像（OrderStatus 等，同源 ops-web）
├── mock/db.ts    全域 mock 数据集（迪拜/AED）+ helper
├── stores/       Pinia：user(登录态) / app(语言·RTL)
├── ports/        端能力抽象 + 条件编译：auth/payment/scan/map/push
├── i18n/         ar/en 双语，驱动 RTL
├── design/       设计 token（跨端 SSOT）
├── shared/       常量(零硬编码) + 格式化(AED/距离)
└── pages/        home(找柜) / orders / me(语言RTL) / login(wd-button)
```

## 端差异（条件编译 `#ifdef APP-PLUS / MP-WEIXIN`）
登录 / 支付 / 免押 / 扫码 / 地图 / 推送 6 项在 `ports/*` 隔离，业务逻辑与 UI 共用。
支付委托 nearpay，MVP 全走后端 Stub，端侧不写死任何 PSP。

## 里程碑
- **M0 工程地基** ✅：脚手架 + 请求层一键切换 + i18n/RTL + stores/ports/design + tabBar/登录（H5 实测通过）
- M1 借还闭环（全程 mock）→ M2 支付Stub/售后/触达 + 翻转真实后端 → M3 钱包/会员/券 → M3.5 发票/消息中心/小程序 → M4 蓝牙/裂变/nearpay 真付
