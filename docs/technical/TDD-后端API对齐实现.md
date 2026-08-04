# TDD-后端API对齐实现（逐 API 清零缺口）

状态：**B0–B6 已实现 · 余 3 个记账拍板项 + 前端侧裁决**
关联需求：[运营端功能清单](../requirements/运营端功能清单.md) · [C端功能清单](../requirements/C端功能清单.md) · [功能权限清单](../requirements/功能权限清单.md)（RBAC SSOT）
逐 API 工作清单（SSOT，勿在本文重复）：[后端实现对齐清单](../api/后端实现对齐清单.md)（`gen-impl-align.py` 生成，随实现重跑）
创建日期：2026-08-04

## 1. 需求摘要

前端契约（[运营端接口清单](../api/运营端接口清单.md) 267 端点 + c-app `/mp` 调用）是后端要实现的接口全集。
当前后端 353 端点的实现深度已由脚本逐条判定：**304 ✅ 四件套落库 · 47 🟡 压在 6 个 SeedData
骨架控制器 · 18 ⬜ 前端在调后端缺失（A 类）· 26 ⚠️ 出参字段错位（B 类）· 19 个 `/api` 端点缺权限码（P 类）**。

**验收标准（总）**：ops-web 与 c-app 切 `USE_MOCK=0` 后全站零 404（A=0）、零字段空白（B=0）、
零 SeedData 骨架（骨架=0）、`/api/**` 业务端点全部有权限码（P=0）；`api-align.py` 与
`gen-impl-align.py` 的计数进卡口只降不升。

## 2. 当前架构分析

- **分层已就绪**：9 个 Maven 模块，svc-{platform,core,ops,finance,gateway} 已有完整
  Service/Mapper/Entity（Flyway V1–V30 累计 141 表）；HTTP 门面集中在 `sharehub-app/portal/**`。
  绝大多数缺口**不需要新建领域层**，是门面层的「改接线 / 补端点 / 补字段」。
- **骨架残留**：`OpsController(21)` `PricingController(10)` `TradeController(7)`
  `PlatformController(4)` `UserController(3)` `VendorController(2)` 仍读 `SeedData`
  （即[未完成清单](./未完成清单.md) A1/A8/A9 的同一件事）。对应领域 Service 几乎全部已存在。
- **缺表勘误**：[领域对象-端点-库表对齐](../api/领域对象-端点-库表对齐.md) §3.1 的 D-1~D-6
  在代码侧已大部分落地——V30 `ord_intervention`、V27 `usr_credit_change`/`mbr_benefit`/
  `usr_coupon_issue`、V28 `loc_lead_follow`/`loc_contract_attach` 均已建表建实体。
  **仅 D-3 `mkt_referral_rule` 仍无表**（`referral-rules` 端点现状待查证数据来源）。
- **已知缺陷**：`MpMetaController` 类级 `@RequestMapping("/mp")` 把方法级绝对路径拼成
  `/mp/api/platform/problems/{no}/archive`，与 `SysConfigController` 的同语义端点重复且前端不调。

## 3. 方案设计

### 方案选型

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A：按缺口类型分批（骨架退役 → A 类 → B 类 → 权限码 → C/D 裁决），批内按域逐 API | 每批验收口径单一、可脚本卡口；骨架退役先行为 A/B 扫清宿主 | 同一域可能被多批触碰 | ✅ 采用 |
| B：按域一次做完（设备域全部缺口→订单域→…） | 域内上下文集中 | 验收口径混杂，A 类 404 要等到排到该域才消 | ❌ 404 是最高优先级运行期故障 |

### 批次与逐 API 任务（勾选清单 = 实现进度）

> 每个端点的「完成定义」：契约对齐（路径/入参/出参字段与前端一致）+ `@PreAuthorize` 权限码
> （对照功能权限清单存在）+ 走 Service/Mapper 落库 + 场景测试断言 + 重跑生成脚本计数下降。

**B0 · 机制先行（半天）**
- [x] `gen-impl-align.py` 计数接入卡口（`impl-align-baseline.json` 棘轮，反向对照已验证能抓回退）（骨架/A/P 三数只降不升；参照 arch-guard 的反向对照自检）
- [x] 修 `MpMetaController` 路径拼接缺陷（两个 `/mp/api/platform/problems/*` 重复端点已删）

**B1 · 骨架控制器退役（47 端点，6 个类，改接线为主）**
逐类退役，类内逐端点：改读 `SeedData` 为既有 Service，补权限码，类内清零后删 SeedData 注入：
- [x] `TradeController`(7)：orders×3 **已走 `RentOrderService`，只补权限码**；share-rules/ledger/settlements/price-plans 4 个只读端点 → svc-finance `ShareService`/`LedgerService`/`SettlementService`、svc-core `PricePlanService`
- [x] `PricingController`(10)（勘误：该类只是注释提及 SeedData，被脚本误判；实际工作 = GET price-plans 迁入）：→ `PricePlanService`/`PricingDiffService`/`PricingScheduleService`
- [x] `OpsController`(21)：cabinets×6 → `CabinetService`；sites/locations×8 → `LocService`；venues/contracts×3 → `LocService`；work-orders×2 → `WorkOrderService`；dashboard×1 → `ReportService` 全域聚合；commands×1 → 网关 Port（保持占位回执，注明）
- [x] `UserController`(3)：users → svc-core `user.core`；coupons → `CouponTplService`；credit/blacklist → `UserBlacklistService`
- [x] `PlatformController`(4)：employees/roles/audit-logs → `EmployeeService`/`IamAdmin`/`AuditLogService`
- [x] `VendorController`(2)（新建 gw `VendorService`，落 `gw_vendor`/`gw_vendor_config`，密钥掩码占位不回写）：→ svc-gateway `GwVendor*Mapper` 落库
- [x] 收尾：控制器层零 SeedData/Dto 引用（`SeedData` 本体保留 —— 它现在只属 seeder 装配层，新增 `AuditSeeder`/`VendorSeeder` 补真表种子）

**B2 · A 类缺失端点 · ops-web 侧（7 个，其中 3 个是「路径裁决」不是新功能）**
- [x] `GET /api/trade/order-interventions`（表/实体/Mapper 已有，补门面）
- [x] `POST /api/trade/ledger/vouchers`（建凭证=写多行分录，`LedgerService`）
- [x] ⚖️(已按推荐执行：对外门面转同一实现) `POST /api/trade/settlements/generate`——后端已有 `/internal/trade/settlements/generate`：**裁决**运营端触发算内部还是对外（建议：对外补 `/api/trade` 门面转内部实现，权限码 `finance:settlement:generate`）
- [x] `POST /api/platform/roles/{no}/archive` + `unarchive`（内置角色拒绝归档）
- [x] `POST /internal/gw/vendors/{code}/test`（连通探测，svc-gateway driver）
- [x] `GET /api/user/users/{no}/profile`（聚合出参：档案+钱包+会员+信用）

**B3 · A 类缺失端点 · c-app `/mp` 侧（11 个）**
- [x] `GET /mp/nearby/cabinets`（按坐标/关键词找柜；`dev_cabinet`⋈`loc_site`）
- [x] `GET /mp/nearby/cabinets/{cabinetNo}/availability`（可借/可还仓位）
- [x] `GET /mp/sites/{siteNo}`（站点详情）
- [x] `POST /mp/auth/register` · `POST /mp/auth/password/reset`（与登录策略/OTP 复用）
- [x] `POST /mp/user/profile`（资料编辑）
- [x] `GET /mp/user/membership`（会员权益视图）
- [x] `GET /mp/trade/orders/ongoing`（进行中订单快捷入口）
- [x] `POST /mp/trade/orders/{orderNo}/buyout`（押金买断，复用 `DepositService.buyout`）
- [x] `POST /mp/trade/deposit/free` · `POST /mp/trade/pay`（走 `StubPaymentPort`，ADR-005 委托 nearpay 不真扣款）

**B4 · B 类字段错位（26 个结构，逐结构裁决方向）**
明细见[前后端对齐缺口](../api/前后端对齐缺口.md) §B。方向规则：
- 前端有后端无（页面空白）→ **后端补出参**（列已在表的补 VO 映射；确无列的补列/查询，如 `WorkOrder` 审计处置 14 字段多数在 `wo_dispatch`/`wo_handle`/`wo_sla`，是**出参装配缺失**不是缺列）
- 后端有前端无 → 逐个判「该展示没展示」（提任务给前端）或「后端赘余」（从 VO 删除）
- [ ] ⚖️ 依赖产品口径的 3 个记账语义先拍板（未完成清单 B1：`share_record.period` / `ShareSummary.gmv` 反推 / `acct_account.balance` 方向）——挡住 `ShareRecord`/`Settlement`/`LedgerEntry` 三个结构
- [x] D-3：`mkt_referral_rule` 已建（V31+V33），`pageRules` 切到真规则表出 `ReferralRuleVO`（不再翻邀请记录）

**B5 · P 类权限码补齐（19 个 `/api` 端点）**
- [x] 全部落在 B1 的骨架控制器上（P=0；随手补了 SSOT 缺口：AGENT+`workorder:wo:read`、VIEWER+`agent:agent:read`，IamSeeder 改为内置角色权限**增量对齐**只增不删），随 B1 逐端点补；码必须先在[功能权限清单](../requirements/功能权限清单.md)存在（历史教训：用过表里不存在的码）——缺码的先补权限清单（需产品确认的单列出来一次性过）

**B6 · C/D 类裁决（后端有前端没调 134 · 后端出参无前端类型 75）**
- [x] 初判裁决表已出：[前端未接线裁决表](../api/前端未接线裁决表.md)（131 条五桶：内部面14/C端16/框架6/写端点85/读端点10），人工复核栏待回填（前端接线任务移交 ops-web/c-app 会话；确认废弃的删端点）。**不混在 B1–B5 里做**，避免「未接线永远像待办」。

### 核心接口 / 配置项

- 无新增横切组件；全部复用既有 `AbstractCrudService`/`Pages`/`@perm`/状态机/Port 装配。
- 新增配置：无。新增 Flyway：仅 D-3 裁决后可能的 `V31__mkt_referral_rule.sql`。

## 4. 测试策略

- 底线：既有 21 个测试类（109 断言）全绿保持。
- B1 每退役一个控制器：对应域场景测试补「读走真表」断言（列表非空来自 Flyway 种子或 seeder、
  重启后仍在、AGENT 数据范围仍生效——`DataScopeCoverageTest` 已锁注册表覆盖面）。
- B2/B3 每个新端点：至少一条 200 契约形状断言（复用 `ApiTestSupport`）+ 属主/权限负例
  （`/mp` 补 IDOR 负例，`/api` 补 403 负例）。
- B4：以 `api-align.py` 的 `shapeMismatch` 计数为验收（26 → 0），每批重跑。
- 卡口反向对照：给 B0 的计数卡口注入一个真违规确认能抓到（「永远绿的卡口比没有卡口更危险」）。

## 5. 风险与注意事项

1. **并行会话在途改动极多**（backend 全树 untracked/D/M）：每次动文件前
   `git status --porcelain -- <file>` 确认；提交只用显式路径。
2. 三个 ⚖️ 裁决点（settlements/generate 前缀、记账语义×3、D-3 referral 表）需要用户/产品拍板，
   已内嵌在批次里，到点会单独请示，不阻塞其他 API。
3. `OpsController#dashboard` 退役后改实表聚合，数字会从种子值变为真实计算值——ops-web
  工作台页面的断言/快照如有硬编码需同步。
4. 报表域跨模块 JOIN 是 G1 白名单唯一豁免（未完成清单 A10），B1 不扩大豁免面。
5. C 类裁决删端点属「修改已测功能」性质（P6）：删除前确认无测试引用、无 c-app/ops-web 调用。

## 6. 实现任务（批次汇总）

- [x] B0 机制卡口 + MpMeta 缺陷修复
- [x] B1 骨架退役（实测骨架端点=47 计数含误判，真手术面=TradeController 4 读 + OpsController dashboard + User 3 + Platform 4 + Vendor 2 + 权限码 19）
- [x] B2 ops-web 缺失 7 端点
- [x] B3 c-app `/mp` 缺失 11 端点（pay/deposit=stub；password/reset 诚实 400——凭据库 pb_auth 未建）
- [x] B4 字段错位（26→19；余 16 个为「后端多字段」属前端裁决 + DashboardStats 内联类型误报 + ⚖️ShareRecord.period 拍板项）
- [x] B5 权限码（19→0）
- [x] B6 裁决表初判已出（人工复核与前端接线另行排期）

### 交付快照（2026-08-05 回填）

- `mvn -o test`：**109 测试全绿**；`gen-impl-align` 卡口绿（骨架 0 / A缺失 0 / P缺码 0，基线 `backend/scripts/impl-align-baseline.json`）。
- 后端端点 353 → **368**（+15：干预审计、凭证、出账门面、角色归档、探测、用户档案、/mp 找柜借还会员资料等）。
- 新增装配：`UserQueryService`（用户档案读模型+掩码）、`RoleQueryService`、gw `VendorService`、
  `ReportService.dashboard()`（工作台实算，含待办/告警/排行三个新事实 mapper）、`AuditSeeder`/`VendorSeeder`。
- **B4 已落（2026-08-05 续）**：11 个「前端有后端无」结构全部补齐 ——
  `WorkOrder` 富行（wo_order 扩展列 ⋈ wo_dispatch/wo_handle 时间轴，`pageRich`；派单改经 ext 编排落时间轴，
  dispatch 回包按契约改为工单行）· `Invoice`(8) · `Reconcile`(5，/resolve 补真处置留痕——action/handleNote
  不再被静默丢弃) · `OrderException`(4) · `RentOrder`(4，补偿/弹出由 ord_intervention 派生) ·
  `Settlement`(4，confirm 落确认人+recordCount 聚合) · `InspectionPlan`(3，/run 真产巡检工单：
  路线拆站 → CabinetQueryPort 定位机柜 → 一站一单+周期幂等) · `AlarmNotice`(2，/resend 真落新行指回源行) ·
  `Contract.attachments` · `PricingDiff.siteNo/dimension/matchRef` · `Region.parentId`。
  DDL：V31（wo/recon/ord_exception/stl/inspection/notice 留痕列 + `mkt_referral_rule` 表 +
  `loc_site` lat/lng/open_hours）+ V32（`wo_order.source_ref`——**被引用两年从未建过的列**，
  转工单幂等路径此前必 500，本轮撞出并补 UNIQUE）。
  余量：16 个「后端多字段」结构（前端该展示没展示，裁决属前端）；`DashboardStats` 为对齐脚本
  对内联匿名类型的误报（JSON 形状已一致）；⚖️ `ShareRecord.period` 等 3 项仍等记账口径拍板。
- **新识别缺口**：`loc_site` 无 lat/lng/openHours 列 → `/mp/nearby` 距离与营业时段如实出 0/空（待 V31）；
  `AbstractCrudService.unarchive` 疑似清不掉 archived_at（MP NOT_NULL 策略），已发独立核实任务。

---
确认记录：2026-08-04 用户确认开工（裁决点按推荐方案执行，记账语义 3 项保守搁置单列）
