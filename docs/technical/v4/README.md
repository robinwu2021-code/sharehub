# ShareHub v4 详细规划

> 状态：**待确认**（2026-09-23）· 总体方案：[技术架构方案-v4](../技术架构方案-v4.md) · 决策：[ADR-020](../ADR/ADR-020-单体优先-支付与协议对接独立部署.md)（部署与共享）· [ADR-021](../ADR/ADR-021-通用设备命名与使用形态.md)（通用命名与使用形态）
>
> 起点是充电宝，将扩展到**充电桩、按摩椅等共享设备**。本目录把架构方案细化到可以直接开工的粒度：
> **通用命名 → 服务与模块 → 数据库 → 接口 → 网关 → 认证与权限 → 定时任务 → 落地路线**。

## 阅读顺序

| # | 篇 | 回答什么 |
|---|---|---|
| 01 | [通用设备与命名](./01-通用设备与命名.md) | 四类设备怎么放进一个模型；什么能叫通用名、什么只能叫充电宝的名字；改名表 |
| 02 | [服务与模块](./02-服务与模块.md) | 4 个进程；Maven 模块；包与模块归属；跨进程契约；依赖规则 |
| 03 | [数据库](./03-数据库.md) | 6 个库；目标表清单；关键表结构；迁移步骤 |
| 04 | [接口](./04-接口.md) | 接入面与命名规则；错误码；改名清单；C 端 / 运营端 / 内部 / 南向接口 |
| 05 | [网关](./05-网关.md) | 边缘网关（nginx）与设备网关（协议对接服务）：路由、安全、指令 / 事件目录、OCPP、MQTT |
| 06 | [认证与权限](./06-认证与权限.md) | 基于 neargo + ai-shop：后端会话与权限、ops-web、c-app 的完整方案；必须先修的安全问题 |
| 07 | [定时任务](./07-定时任务.md) | 共享 ai-shop 任务服务；ShareHub 接入；任务目录 |
| 08 | [落地路线](./08-落地路线.md) | S0–S8 阶段、依赖、卡口 |
| 09 | [后端代码架构](./09-后端代码架构.md) | 后端代码现状实测、目标模块与包结构（参照并复用 ai-shop）、三层能否共用 ai-shop、边界规则、重构步骤、度量基线（前端代码架构另行整理） |
| 10 | [共享构件与 Nexus](./10-共享构件与Nexus.md) | 共享构件发布到自建 Nexus：发布源为 `neargo-framework`（框架 + 组件）与 ai-shop（两个共用服务的客户端与可执行 jar）、版本策略、CI 发布、部署、步骤 N0–N5 |
| 11 | [共用组件方案](./11-共用组件方案.md) | 先分库与服务：框架（`neargo-framework` 2.x 五级分层）· 组件（框架内 `components/`：通知、媒体）· 共用服务（支付含通道与微信、任务；暂由 ai-shop 托管）· 本项目私有；各组件边界与 SPI；ai-shop 拆分表；实施顺序与治理 |
| 12 | [代码库与工程结构](./12-代码库与工程结构.md) | 四个仓库的分工与规则；框架 / 共用服务 / ai-shop / **本项目的 Java 目录结构（按层级分 `foundation · contracts · domains · gateway · apps · support`，持久化按场景选 MP 或 JdbcClient，每域登记表的隔离归属）**；依赖方案（继承链、模块依赖矩阵、第三方版本、enforcer 校验、示例 POM） |
| 13 | [neargo-framework 独立仓库](./13-neargo-framework独立仓库.md) | **F1 已执行**（本地仓库 `/Users/robin/work/ai/neargo-framework`，20 个提交的历史，11 个模块构建通过）；边界、执行记录、2.x 要点（五级分层、多 ORM、JdbcClient 自动隔离 PoC）、消费方切换。2.x 详细设计在框架仓库 `docs/design/01` |
| **14** | [**执行任务清单**](./14-执行任务清单.md) | **接下来做什么**：按「谁来做」分五条线（你拍板 / 本项目后端 B1–B8 / 框架 K1–K7 / ai-shop X1–X8 / 前端 W1–W3）+ Nexus N0–N5；含关键路径与建议的第一批 |
| **15** | [**多系统多平台改造需求**](./15-多系统多平台改造需求.md) | **交给 ai-shop 的规格**：支付与任务服务改造为多系统（`system` 维度）· 多平台（市场 / 通道 / 端 / 目标）；MJ1–MJ8（任务）· MP1–MP12（支付，含独立库、回调进 pay-svc、凭据进库）· 安全隔离 · H1–H5（ShareHub 对接）· 验收清单 · M0–M4 批次 |

## 一页结论

**进程**（[02](./02-服务与模块.md)）

| 进程 | 来源 | 端口 | 库 |
|---|---|---|---|
| `sharehub-app` 业务单体 | 本仓库 | 8082 | `sharehub_core` · `sharehub_auth` · `sharehub_pii` |
| `sharehub-gateway` 协议对接 | 本仓库 | 8091 | `sharehub_gw` |
| `ai-shop-pay`（共用实例） | ai-shop `backend/pay/`（含通道与微信）；**本项目不另起实例** | 8083（已在跑） | 服务自有库，按 `system` |
| `ai-shop-job`（共用实例） | ai-shop `backend/job/`；**本项目不另起实例** | 无（已在跑） | 服务自有库，按 `system` |

**通用模型**（[01](./01-通用设备与命名.md)）：站点 → 点位 → **设备** → **槽位** → 物品（仅借还型）；
使用形态 `RENTAL`（充电宝）/ `SESSION`（充电桩、按摩椅、储物柜）；付费模式 `PREAUTH` / `DEPOSIT` / `PREPAY` / `CREDIT`；
订单 = 通用主表 `ord_order` + 类型扩展表；新增一种设备 = 登记类型 + 类型模块 + （可复用的）扩展表与指令族 + 驱动。

**命名速查**：`device` 不叫 cabinet · `slot` · `order` 不叫 rent · `EV_CHARGER` 不叫 EV_PILE · `start` / `end` / `stop` 不叫 eject / return ·
权限码 `device:asset:*` · 库名 `sharehub_*` · MQTT 前缀 `sh/`。类型专属清单外出现设备名 = 违规（命名卡口）。

**接入面**（[04](./04-接口.md)）：`/api`（运营端，6 前缀不增）· `/mp`（C 端）· `/internal`（进程间，外网封死）· `/gw` `/ocpp`（设备）· `/pay/callback`（通道）。

**认证**（[06](./06-认证与权限.md)）：三个 Realm（`ctk_` / `stk_` / `atk_`）各一张会话表；不透明令牌只存哈希；身份与权限每次请求现算；
功能 → 功能点 → 角色 → 成员；前端权限只来自 `/api/auth/me`；**先修 5 个安全问题**（任意用户可登录超管、固定验证码通过等）。

**定时任务**（[07](./07-定时任务.md)）：一个调度器、三个目标（app / gateway / pay-svc）；业务只写 `JobHandler`；禁止 `@Scheduled`。

## 待拍板（汇总）

| # | 事项 | 推荐 | 出处 |
|---|---|---|---|
| 1 | 共享模块放哪（pay / job / auth-store） | ✅ **已定**（2026-09-23）：框架件（含 auth-store）与组件（通知、媒体）→ `neargo-framework`；pay（含通道与微信）/ job 是共用服务，**暂留 ai-shop**，改动由 ai-shop 统一改 | ADR-020 · ADR-024 |
| 2 | pay-svc、调度器是否与 ai-shop 共用实例 | ✅ **已定：共用一套**，两个服务改造为多系统，按 `system` 隔离（2026-09-23 用户定「直接用 ai-shop 现成的服务」） | ADR-025 · 15 |
| 3 | 库名改为 `sharehub_*` | 改（与命名通用化同一窗口前后） | ADR-021 |
| 4 | 是否保留旧接口路径别名 | 不保留（无外部调用方） | ADR-021 |
| 5 | 运营端会话时长 | 7 天滑动（ai-shop 30 天） | 06 §2.2 |
| 6 | 跨进程事件传输 | Outbox + 内网 HTTP，量大再换 MQ | ADR-020 |
| 7 | 首个 AE 支付通道 / 首家按摩椅厂商 | 取决于签约 | ADR-020 / 08 S7 |
| 8 | 钱包储值合规（CBUAE SVF） | 上线前确认；不行则按次付费 | ADR-020 |
| 9 | 平台代号与 C 端品牌 | ✅ **已定：保留 `sharehub`**（2026-09-23；不用 RentOps，`share` 易与 shared 混淆）；「Ops」只给运营后台；**C 端品牌仍待选定**（需商标 / 应用商店 / 阿语检查） | ADR-023 |
| 10 | 控制器下沉到各业务域 `api` 包（照 ai-shop） | 下沉 | 09 §四 |
| 11 | `common` / `auth` / `store` 的中性部分抽到 neargo（需改 ai-shop 仓库） | 抽，进 `neargo-framework` 2.0；抽离前 ShareHub 先原样移植 | 09 §2.4 · 11 |
| 12 | Nexus | ✅ **已有**：`nexus.neargo.ai`（NXRM 2.14.20-02，2026-09-23 实测）。待办：关公网匿名读、建只读 / 部署账号、轮换 admin 口令、评估升 3.x。首个版本号 → **`2.0.0`**（`ai.neargo:neargo-framework` 的 1.0.x 已被 minipos / qrpay 那条线占用） | ADR-022 · 10 · 13 |
| 13 | 进一步拆 ai-shop | ✅ **已定**：`shop-notify` → `neargo-notify`、媒体 → `neargo-media`（框架组件）；微信网关并入支付服务；`payclient` 在 ai-shop 内整理为 `pay-client`；架构测试 → `neargo-archrules` | 11 · ADR-024 |
| 14 | neargo 框架抽为独立仓库 `neargo-framework`；能力组件另建 `neargo-components` 还是同仓；抽取基线 | ✅ 仓库已建（按「直接从 `chore/2026h2-stabilize` 抽」执行，可重做）；组件并入框架、共用服务暂留 ai-shop（ADR-024）；远端待用户在 Codeup 建 | 13 §六 |
| 15 | 框架 2.x：版本策略 · 包名 / artifactId · `LocalDateTime` + `Integer deleted` · JDBC 隔离复用 MP 改写器 · 未登记表拒绝 · Spring Data JDBC 预留 | 版本 ✅ **已定：从 `2.0.0` 起**（1.0.x 被 minipos / qrpay 那条线占用，实测）；其余仍按推荐，**框架线已冻结**（ADR-025） | 框架 design/01 §九 · 13 |
| 17 | 支付与任务：是否共用 ai-shop 在跑的实例、两服务改造为多系统多平台；框架是否暂缓 | ✅ **已定**（2026-09-23 用户定）：共用实例 + 多系统改造（[15](./15-多系统多平台改造需求.md)）；框架暂缓，等本地跑通并测试通过再建 | ADR-025 · 14 |
| 16 | 本项目目录按层级重排（`foundation/ contracts/ domains/ gateway/ apps/ support/`），artifactId 不变；每域 `XxxIsolation` 登记表归属 | 重排；不改名 | 12 §四 |

## 图

[`10-target-architecture.svg`](../diagrams/10-target-architecture.svg) 总体 ·
[`11-gateway-service.svg`](../diagrams/11-gateway-service.svg) 协议对接 ·
[`12-pay-service.svg`](../diagrams/12-pay-service.svg) 支付共享 ·
[`13-device-model.svg`](../diagrams/13-device-model.svg) 通用设备模型 ·
[`14-backend-code.svg`](../diagrams/14-backend-code.svg) 后端代码架构
