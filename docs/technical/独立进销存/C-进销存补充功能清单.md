# 进销存补充功能清单（相对 ai-shop `shop-inventory`）

> 2026-10-29 · 依据：[A-库存服务需求方案与开发计划](A-ai-shop库存服务-需求方案与开发计划.md)、[B-租户身份与权限方案](B-业务系统对接-租户身份与权限方案.md) 与「平台给商家发货」结论（方案 A：商家只是收货方，进销存管到交给商家为止）。
> 编号 `IS-xx` 对应 A 文档 §1.3。规模：S ≤ 2 天 · M 3 ~ 5 天 · L 1 ~ 2 周（单人）。里程碑见 A §3.1。

## 〇、总览

| 类别 | 项数 | 说明 |
|---|---|---|
| **直接复用** | 8 | 余额、流水、单据骨架、盘点骨架、调拨、Outbox、独立数据源、冻结守卫思路 |
| **需要改造** | 11 | 在现有表 / 服务上加字段、加规则、改语义 |
| **全新开发** | 14 | 独立服务化、SN、出库池、外部目标、幂等、事件拉取、期间汇总等 |
| **明确不做** | 6 | 质检、商家库存、出库后跟踪等 |

---

## 一、直接复用（不改语义，只迁入新模块）

| 现有能力 | 现有实现 | 用在哪 |
|---|---|---|
| 库存余额（在手、预留） | `inv_stock_balance`、`StockPostingServiceImpl` | 仓内数量账 |
| 只追加流水 | `inv_ledger`（实体无 `updatedAt`） | 出入库、盘点的流水 |
| 入 / 出库单「草稿 → 过账 → 作废」骨架 | `InboundServiceImpl`、`OutboundServiceImpl` | 所有出入库单据 |
| 盘点单与逐次操作、一个库位一张 | `StockCountServiceImpl`、`inv_stock_count_operation` | 盘点 |
| 仓间调拨（一出一入、在途、承运商与运单号） | `TransferServiceImpl`、V4 | 仓间调拨（P2） |
| Outbox 同事务写事件 | `inv_outbox`、`InvOutboxDispatchJob` | 事件来源 |
| 独立数据源、独立迁移历史 | `InventoryDataSourceConfig` | 独立库 |
| 盘点与过账互斥 | `InventoryMutationGuard` | 冻结守卫的基础 |

## 二、需要改造

| # | 功能 | 现状 | 要补什么 | IS | 优先级 | 规模 |
|---|---|---|---|---|---|---|
| R1 | **业主（租户）** | `InvOwner`，由商城首次使用时按商家主体自动创建 | 改为显式开通：`externalRef = 运营方租户号`；与系统凭证绑定 | A1 | P0 | S |
| R2 | **系统凭证** | `InvOpenCredential`（开放 API 读余额、流水、数量同步） | 升级为服务凭证：`systemCode`、可访问业主、动作范围；写接口全部走凭证 | F1 | P0 | M |
| R3 | **物料** | `InvItem`：数量物料、批次字段留位 | 加 `trackingMode = SERIAL / QUANTITY`、`qcPolicy`（是否必须带放行凭证）；有流水后不可改 | A2 | P0 | S |
| R4 | **仓库** | `InvLocation(kind = WAREHOUSE)` | 加所属区域、停用守卫（有余额 / 未完成单 / 冻结时不可停用，逐项列原因） | A3 | P0 | S |
| R5 | **入库单** | 用途：采购 / 退货 / 调拨入 / 盘盈 / 其他 | 加用途 `OPENING`（期初，带依据与附件）、`CORRECTION`（撤回纠正）；行加 SN 明细与 `qcRef`；**缺放行凭证拒绝过账** | B1 ~ B5 | P0 | M |
| R6 | **出库单** | 用途：销售 / 调拨出 / 报损 / 盘亏 / 领用 / 其他；去向：供应商、门店 | 加用途 `ISSUE`（发货给商家）与 `RETURN_SUPPLIER`；ISSUE 必填**商家 + 内部接收人**，可选站点（须属于该商家）；行加 SN 明细 | C1、C2 | P0 | M |
| R7 | **作废规则** | 草稿、已过账都可作废（反冲） | **已过账的 ISSUE 禁止作废**，纠错只走调整、撤回、退货入库 | C10 | P0 | S |
| R8 | **余额口径** | `available = 在手 − 预留` | 加「不可用」数量：`available = 在手 − 预留 − 不可用`；每件至多计一次 | E1 | P0 | S |
| R9 | **盘点** | 数量盘点、一个库位一张 | 加 SN 扫码（重扫不重计、未知 SN 列异常）、提交 → 复核（复核人 ≠ 录入人）→ 过账、内容版本；冻结范围扩到所有新命令（出库池、退货确认等） | D1 ~ D4 | P1 | L |
| R10 | **事件** | Outbox 主动推送给商城 | 改为宿主**按订阅游标拉取 + 确认**，可重放；新增出库池、退货等事件类型 | F3 | P0 | M |
| R11 | **错误与契约** | 商城错误码、商城权限注解 | 改用本服务错误码（三语文案键）、OpenAPI 契约随代码发布 | F5 | P0 | M |

## 三、全新开发

| # | 功能 | 说明 | IS | 优先级 | 规模 |
|---|---|---|---|---|---|
| N1 | **独立服务化** | 拆 api / core / infra / server / client 五个模块；去掉对商城 `BizContext`、`shop-base` 的依赖；可单独启动 | — | P0 | L |
| N2 | **命令幂等** | 所有写请求带幂等键；同键同参返回原结果、同键异参冲突；可按键查结果 | F2 | P0 | M |
| N3 | **操作人与审计** | 宿主透传操作人（`STAFF:工号` / `AGENT:商家号` / `SYSTEM:任务`），记入审计表与事件信封 | F1、F4 | P0 | S |
| N4 | **SN 档案与在库周期** | 每个 SN 一份档案；入库开周期、出库关周期；同一 SN 同时只能在一个仓；回仓开新周期，旧周期不重开 | B3 | P0 | L |
| N5 | **外部目标（收货方档案）** | 宿主同步**商家、站点（上级 = 商家）、运维人员**；带名称、状态、版本号；停用后不能再作收货方 | A4 | P0 | M |
| N6 | **出库池** | 发货过账即生成（柜一台一条、宝一行一条），带商家、站点、接收人快照；状态：待签收 → 已签收 / 已撤回 / 已退回 | C3 | P0 | L |
| N7 | **出库池查询** | 按商家（含其站点）、站点、接收人、状态、SN、出库单、日期；**支持多值过滤**（供业务系统套数据范围） | C4 | P0 | S |
| N8 | **发货调整** | 待签收的记录改商家 / 站点 / 接收人，原因必填，留调整前后快照 | C5 | P0 | M |
| N9 | **发货撤回** | 待签收的记录撤回回仓：同事务生成纠正入库、开新周期、恢复库存 | C6 | P0 | M |
| N10 | **签收（领取）与释放** | 接收人签收即领取，批量全成全败；原业务系统可受控释放 | C7、C8 | P0 | M |
| N11 | **退货入库关联发货** | 退货入库可引用原出库池记录 → 标「已退回」；原记录已签收的，加「退货待业务确认」冻结，业务确认后解冻 | B4 | P0 | M |
| N12 | **多原因冻结** | 盘点、退货待确认、人工三类冻结并存，按原因单独解除 | — | P0 | M |
| N13 | **期间汇总** | 给定期间与仓 / 物料：期初、按用途分类的入与出、期末（供运营端统一库存报表） | E4 | P0 | M |
| N14 | **在途（按商家）** | 已发货未签收的数量按商家汇总 | E5 | P1 | S |
| — | 数据迁移工具 | 从 powerbank 旧仓库与设备表导入仓内设备；先出差异报告，确认后再导入，可重复执行 | — | P0（上线前） | L |

## 四、明确不做

| 不做 | 原因 |
|---|---|
| 质检（模板、任务、执行器、放行判定） | 质检在业务系统，通过后才入库；进销存只校验放行凭证 |
| 商家库存 / 商家虚拟库位 | 方案 A：商家只是收货方，「商家手里还有几台」由业务系统统计 |
| 商家作为租户（跨租户转移） | 设备不归商家所有；只有商家买断时才需要，另行设计 |
| 出库后的位置跟踪（绑定、装机、换站点、撤机） | 业务系统负责，进销存签收后不再跟踪 |
| 运营界面 | 统一在 ops-web，经业务系统后端调用 |
| 人员账号与角色 | 人的权限只在业务系统判定，进销存只认系统凭证 |

商城专用能力（预留、销售提交、退款回补、估值、每日快照、供应商）**保留不动**，供 ai-shop 将来切流用，powerbank 不调用。

## 五、开发顺序（与 A §3.1 对应）

| 里程碑 | 本清单的项 |
|---|---|
| M0 | N1、R11（契约先行） |
| M1 | R1 ~ R5、R8、N2 ~ N5 |
| M2 | R6、R7、R10、N6 ~ N12 |
| M3 | R9、N13、N14 |
| M4 | 数据迁移工具 |

合计：改造 11 项（约 S×5、M×5、L×1），新增 14 项（约 S×3、M×8、L×3，另加迁移工具 L），与 A 文档单人约 13 周的估算一致。

---

## 附：N1「独立服务化」的含义与依赖清单

> 2026-10-29 核对 ai-shop `0e686ab8f`。

### 一、ai-shop 说的「可以独立部署」指什么

ai-shop 文档 `TDD-进销存模块化与独立部署.md` §2.2 ~ 2.3 原意：**同一个 `shop-app` 包，多开一个 profile、多起一个进程**，只把「冷路径」放到这个进程（流水查询、盘点、进货、调拨、报表、开放 API）；「热路径」（下单锁库存、扣减）按设计**永远与下单同进程**。

现状核实：

| 形态 | 现状 | 能给 powerbank 用吗 |
|---|---|---|
| `shop-app` 以 `api` profile 运行（商家端 6 个库存控制器） | ✅ 运行中 | ❌ 要商城商家登录（`BizContext`），不是服务间接口 |
| `shop-app` 以 `ops` profile 运行（运营端 2 个控制器） | ✅ | ❌ 同上，商城运营身份 |
| `shop-app` 以 `openapi` profile **单独起进程**（`OpenInventoryController`） | ✅ 已实现 | ❌ 只有 3 个接口：查物料、查流水、数量同步；没有出入库单、SN、出库池；仍需平台主库、Redis 与整套商城装配 |
| 独立的库存服务（自己的启动类、只连库存库、服务凭证） | ❌ 不存在（全仓 `@SpringBootApplication` 只有 pay / job / food / commerce / shop 五个） | 这是 N1 要做的 |

所以「可以独立部署」= **同一个商城程序换个 profile 多起一份**；powerbank 需要的是「**不带商城、只连库存库、有服务间写接口**」的独立服务。两者差的就是下面这张依赖清单。

### 二、`shop-inventory` 的依赖清单

**1. Maven 直接依赖**

| 依赖 | 作用 | 独立化处理 |
|---|---|---|
| `shop-base` | 异常、错误码、编号、SPI 接口、内部 HTTP 协议、事件基类 | **去掉**（见第 2 项逐类处理） |
| `shop-base-auth` | 商城登录上下文、权限、密码哈希 | **去掉**，换服务凭证 |
| `shop-store-mybatis` | MyBatis-Plus 装配、任务调度、统一 Outbox | **去掉**，直接依赖 MyBatis-Plus，任务与 Outbox 自带 |
| `flyway-core` / `flyway-mysql` | 迁移 | 保留 |
| `h2`（测试） | 单测库 | 保留 |

**2. 代码里引用的外部类（共 28 个顶层类、123 处 import；shop-base 21 · shop-base-auth 5 · shop-store-mybatis 2）**

| 外部类 | 所在模块 | 引用文件数 | 独立化处理 |
|---|---|---|---|
| `common.BizException` | shop-base | 23 | 换本服务 `InventoryException` |
| `common.ErrorCode` | shop-base | 22 | 换本服务错误码（三语文案键） |
| `common.BizKey` | shop-base | 1 | 本服务编号生成 |
| `common.pricing.PricingSnapshotHash` | shop-base | 5 | 复制为本服务工具类（纯函数） |
| `auth.BizContext` | shop-base-auth | 6 | 换 `InventoryPrincipal`（系统 + 租户 + 操作人） |
| `auth.BizPerms` / `auth.Perms` | shop-base-auth | 6 / 2 | 换凭证动作范围校验 |
| `auth.SecurityUtils` | shop-base-auth | 5 | 同上 |
| `auth.PasswordHasher` | shop-base-auth | 1 | 凭证哈希用本服务实现 |
| `spi.inventory.MaterialCatalogQueryPort` / `Facts` / `InternalApi` | shop-base | 5 / 4 / 1 | **反向依赖**（库存向商城要物料资料）→ 改为宿主同步物料与外部目标 |
| `spi.inventory.InventoryDemandPlan*` | shop-base | 3 + 4 | 餐饮按需计划：接口定义移入 `inventory-api`，商城侧实现适配 |
| `spi.inventory.InventoryProduction*` | shop-base | 2 + 2 | 餐饮生产扣料：同上 |
| `spi.inventory.InventoryReservation*` | shop-base | 1 + 1 | 商城预留：同上（M5） |
| `spi.report.ReportContracts` / `ReportSourceFactsPort` | shop-base | 1 + 1 | 报表事实适配器**移到 shop-app**，库存服务只提供期间汇总接口 |
| `svc.InternalHttp` / `InternalServiceResponses` | shop-base | 4 / 4 | 商城内部服务协议 → 本服务统一 API 与错误模型 |
| `job.JobSupport` | shop-store-mybatis | 3 | 本服务内任务调度（锁表放库存库） |
| `event.outbox.runtime.UnifiedOutboxFacade` | shop-store-mybatis | 2 | 用本模块已有的 `InvOutbox`，加订阅游标 |
| `event.DomainEvent` / `event.outbox.RetryPolicy` | shop-base | 1 / 1 | 本服务事件信封与重试策略 |

**3. 放错位置的代码**

| 文件 | 处理 |
|---|---|
| `shop-inventory` 里的 `ai/neargo/shop/portal/internal/InternalMaterialCatalogController` | 属于商城门户，移到 shop-app |
| `port/InventoryReportFactsPortAdapter`、`port/MaterialCatalogQueryPortImpl` 等 4 个 Port 实现 | 商城侧适配，移到 shop-app |
| `api/biz`（6）、`api/ops`（2）、`api/internal`（3）、`api/open`（1）控制器 | 留在 shop-app 作为商城自己的 BFF，M5 后改调客户端；独立服务另出 `/api/inventory/v1` |

**4. 反向依赖（谁在用 `shop-inventory`）**

| 调用方 | 文件数 | 处理 |
|---|---|---|
| `shop-app` 的 `invbridge` | 32 | M5 前不动（嵌入模式继续），M5 改调客户端 |
| `shop-app` 的 `portal` 等 | 6 | 同上 |
| powerbank | 0 | 只依赖新服务的 `inventory-client` |

**5. 运行时依赖对比**

| 资源 | 现在（`openapi` profile 起 shop-app） | 独立服务 |
|---|---|---|
| 平台主库（商城） | 必需 | **不需要** |
| 库存库 | 必需 | 必需（唯一的库） |
| Redis | 必需（商城装配） | 不需要（如需限流再加） |
| 商城登录 / JWT 配置 | 必需 | 不需要；改为服务凭证密钥 |
| 程序包 | shop-app 整包 | `inventory-server` 单独一个包 |

### 三、拆的顺序（对应 A 文档 T0.1 ~ T0.3）

1. 建 5 个子模块，把上表「复用」的领域代码迁入 `inventory-core`，ArchUnit 规则先写上（core 不得依赖 `ai.neargo.shop.*`），跑红。
2. 按上表逐类替换：异常与错误码 → 鉴权 → Outbox 与任务 → SPI 反转 → 移走放错位置的代码，直到规则跑绿。
3. `inventory-server` 在空库上启动、迁移幂等（`StandaloneContextTest`）。
4. 原 `shop-inventory` 嵌入模块在过渡期保持可用，只修 bug，新能力只进独立服务。
