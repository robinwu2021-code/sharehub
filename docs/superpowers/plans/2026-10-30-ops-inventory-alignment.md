# 运营端功能补齐与库存管理对齐开发方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐运营端真实业务链路，优先保证库存单据、设备位置与独立库存服务账一致。

**Architecture:** 建议沿用现有 Spring 服务、InventoryPort 和 LOCAL／SERVICE 引擎。powerbank 管理宿主主数据、业务单据与设备生命周期；SERVICE 模式的库存账由独立库存服务承载。所有业务动作通过唯一应用服务执行，远程过账采用持久化操作记录、稳定幂等键和可恢复步骤。

**Tech Stack:** Java 21、Spring、MyBatis、MySQL、Next.js、React Query、Vitest。

**Spec:** 库存边界以 `docs/technical/设计规范-库存管理菜单.md` 为准；目标业务参考 `docs/requirements/运营端菜单功能矩阵.md`。本文为待评审方案，未实施代码修改。

## 全局约束

- 库存模块保留 `/inventory` 和既有 `device:inventory:*` 权限；菜单修改同步 Flyway 与导航重放测试。
- LOCAL 不新增第二套物料账；物料单据、物料余额和盘点继续明确依赖 SERVICE。
- 普通本地数据库事务不能回滚远程 HTTP 过账；不得把 `@Transactional` 描述为跨系统原子事务。
- 历史过账通过反向单据纠错；同一请求重试必须复用原业务单号和远程幂等键。
- 不把类存在、端点存在、类级 Mapper 依赖当作功能完成证据。

## 一 运营端开发顺序

| 批次 | 交付 | 涉及实现 | 验收重点 |
|---|---|---|---|
| 1 | 消除虚假成功 | CabinetServiceImpl、AlarmServiceImpl、VendorServiceImpl | 设备命令真实下发后返回可查询回执；通知按实际通道结果区分 STUB／失败／成功；占位探测不声称连通 |
| 2 | 接通业务动作 | ComplaintServiceImpl、RefundServiceImpl、MemberServiceImpl | 投诉退款关联真实退款申请；投诉转工单具备 source_ref 幂等；发卡接支付／权益链路前禁用入口并说明条件 |
| 3 | 补齐真实查询 | DeviceLogServiceImpl、AgentPerformanceServiceImpl | 日志有写入链路再提供查询；绩效按全量聚合排名后分页，不能页内排名 |
| 4 | 修正文档与检查器 | feature-align.py、API 提取器、功能矩阵 | 识别组合权限；动态用途授权与公开接口有显式例外依据；删除已修复的票据可修改缺陷描述 |

退款审批目前停在 APPROVED，并未实际出款；后续支付执行需有独立状态、权限和幂等处理。审批即冲回分成和触发贷项的现有行为，需要和最终退款成功／失败的财务口径一起复核，不能仅补一个执行按钮。

## 二 库存十四个页签的代码对齐

此表记录静态链路覆盖，不能替代运行验收。页面入口为 `ops-web/app/inventory/page.tsx`，API 主入口为 `InventoryController`；设备出库与调拨动作另有设备／调拨应用服务。

| 页签 | Web 组件 | 服务端落点或链路 | 当前结论与验收重点 |
|---|---|---|---|
| materials | materials.tsx | MaterialServiceImpl；inv_material | 档案链路存在；跟踪方式锁定、归档时远程余额与引擎切换需联调 |
| warehouses | warehouses.tsx | WarehouseAdminService | CRUD／归档接口存在；默认仓与有库存仓不可错误停用 |
| suppliers | suppliers.tsx | SupplierServiceImpl | CRUD 与绩效接口存在；供应商归档后历史单仍可查 |
| intakes | intakes.tsx | IntakeServiceImpl → QcService → InventoryPort | 质检凭证与本单绑定并一次性消费；批量远程部分成功需恢复测试 |
| material-docs | material-docs.tsx | MaterialServiceImpl → InventoryApi | 用途判权与 M1–M5 校验存在；跨系统恢复和重试幂等为优先缺口 |
| issued | issued-pool.tsx | InventoryPort → 柜机出库／领用链路 | 页面为已出库待绑定池视图；需明确是否满足单头、明细、物流、部分收货的业务单据要求 |
| disposals | disposals.tsx | DisposalServiceImpl → remoteOutbound | 本地处置与远程出库并存；远程成功后本地失败无法靠本地回滚补偿 |
| transfers | transfers-tab.tsx／transfer.tsx | InventoryTransferServiceImpl + TransferOpsServiceImpl | 专用 ship／receive 有记账；通用 save 可改状态而绕过副作用，须收口 |
| counts | stock-counts.tsx | /inventory/counts 系列 → 独立库存服务 | SERVICE 限制已有界面提示；审核、重复过账及库存事件回写需真实联调 |
| asset-diffs | transfer.tsx | TransferOpsServiceImpl | 缺件／多件签收会生成差异；差异结案是否应驱动账务纠正须明确 |
| stock | stock-balance.tsx | StockViewServiceImpl／InventoryPort | 设备表与远程余额的口径必须按引擎区分，核对在库／在途／待检／已出库 |
| material-stock | material-stock.tsx | MaterialServiceImpl.balances／saveMinQty | SERVICE 限制已有提示；远程故障不得呈现为零库存 |
| ledger | stock-ledger.tsx | StockViewServiceImpl 与履历／库存服务 | 逐件设备与数量物料的流水覆盖、来源编号和查询时间口径需联调 |
| settings | warehouses.tsx | sys-params／InventoryEngineRouter | 引擎可运行时切换；需增加切换前对账与进行中操作约束 |

## 三 已确认的库存问题

### INV 01 调拨状态存在绕过记账的入口

`InventoryTransferServiceImpl.save()` 接受目标状态并更新 IN_TRANSIT／DONE，但未调用生命周期与 `InventoryPort.transferShip/transferReceive`；相对地，`TransferOpsServiceImpl.ship/receive` 有条件状态更新、设备状态处理、差异单与记账调用。当前 Web 表单没有传 status，因此这是 API 写入面漏洞，不能描述成当前表单必然触发。

建议让 save 仅处理 DRAFT 单头编辑，拒绝通过它修改状态。专用动作保留唯一执行链路；不能只给 save 再补一套库存更新代码。

### INV 02 物料远程过账成功后缺少持久化恢复点

`MaterialServiceImpl.createDoc()` 每次生成新 MD 单号，先远程过账，再插入本地单头与明细。调拨入库失败时出库已经过账，代码仅记录 ERROR 后抛异常。本地事务回滚后，界面查不到待恢复单；重新提交会生成另一单号及幂等键。现有菜单规范已记录该限制，不应视为已解决。

### INV 03 本地事务与远程库存不具备原子性

`DisposalServiceImpl` 和批量入库也在本地事务中调用远程服务。远程成功后，本地提交失败或响应丢失的场景，需要用故障注入验证并纳入恢复协议；目前只能确认代码存在这种事务边界，尚未复现真实数据不一致。

### INV 04 引擎切换缺少操作级固定

`InventoryEngineRouter.current()` 每次调用读取系统参数。一个批量入库循环多次调用 InventoryPort 时，若中途切换引擎，存在同一业务操作被路由到不同引擎的风险。建议操作创建时持久化 engine，恢复与重试沿用该值；切换前检查进行中操作和账实差异。

## 四 库存实施任务

### 任务 1 收口调拨状态写入

文件：修改 `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/InventoryTransferServiceImpl.java`；复用 `TransferOpsServiceImpl.java`；新增场景回归测试 `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/TransferWriteBoundaryTest.java`。

- [ ] 先测试直接 save(status=IN_TRANSIT／DONE) 被拒绝，且单据、设备、库存均不变；DRAFT 单头编辑正常。
- [ ] save 拒绝状态变更；检查其他调用方和 mock 同步移除该写入方式。
- [ ] 测试专用发货／签收、重复请求、并发发货、缺件／多件签收、撤机回仓须复检。
- [ ] 验证失败场景不会留下只有状态变更、没有库存副作用的提交；独立提交本任务。

### 任务 2 建立库存操作恢复协议

拟新增：`inv/service/InventoryOperationService.java`、`inv/entity/InvOperation.java`、对应 Mapper、操作表 Flyway 迁移（版本号在实施时取下一个可用号）。修改 MaterialServiceImpl、DisposalServiceImpl 及远程库存适配层。

拟定契约：`begin(operationType, requestKey, payloadHash, engine)` 返回稳定 operationNo；`retry(operationNo)` 按已持久化阶段续做。阶段至少包括 PREPARED、OUT_POSTED、IN_POSTED、COMPLETED、NEEDS_REVIEW，普通出入库按适用阶段推进。requestKey 相同而 payloadHash 不同必须拒绝。

- [ ] 测试响应丢失后的重试复用原单号；远程出库成功、入库失败后操作记录仍可查询。
- [ ] 在远程调用之前提交操作意图；阶段持久化与业务事务分开设计，不能让外层事务回滚删除恢复记录。
- [ ] 保存远程单号和错误信息；重试从未完成阶段继续，禁止重复出库；不确定结果先查远端再重试。
- [ ] 测试远程成功本地失败、服务重启、多次重试、同键不同请求、并发恢复。
- [ ] Web 展示处理中／需处理／完成和受控重试入口；错误恢复后刷新余额、流水与业务单据。

### 任务 3 固定引擎并核对余额口径

修改 InventoryEngineRouter、库存操作编排、StockViewServiceImpl 与库存设置页面。

- [ ] 测试批量执行中切换配置不会让同一操作跨引擎；已有操作恢复时继续使用原引擎。
- [ ] 对入库、出库、调拨、处置、盘点建立期初 + 入 − 出 = 期末校验；待检、在途、已出库待绑定分别核对。
- [ ] SERVICE 不可达时显示错误与重试，不用空列表或零余额掩盖故障。
- [ ] 明确出库池视图和真正出库单之间的产品边界，再决定是否新增单据模型；不重复建设现有发货记录。

### 任务 4 联调和文档回写

- [ ] 使用库存服务测试租户运行 `backend/scripts/inventory-local/e2e.py`；运行前阅读脚本的创建、过账与清理行为。
- [ ] 补 LOCAL／SERVICE 两套场景：质检入库、商家出库、调拨差异、退回待检、处置、数量／序列物料、盘点及补偿恢复。
- [ ] 回写运营端目标矩阵：库存已独立为 14 叶模块，供应商／入库／盘点／流水已有代码，不再继续按旧设备菜单统计。
- [ ] 清理失效的“后端未实现”注释；为每项保留 Web → API → 方法 → 库存事件的证据与测试状态。

## 五 验证记录

2026-10-30：前端定向运行 `npm test -- lib/inventory-routes.test.ts lib/mock/db/inventory-transfer.test.ts lib/mock/db/material.test.ts lib/mock/db/intake.test.ts lib/mock/db/inventory-admin.test.ts`，5 文件、35 测试通过。这些测试主要验证路由与 mock，不能证明远程库存集成正确。

后端尝试：`mvn -o -pl sharehub-app -am -Dtest=MaterialServiceTest,IntakeFlowTest,IntakeReceiptTest,DisposalTest,InventoryServiceEngineTest -Dsurefire.failIfNoSpecifiedTests=false test`，被 Maven Enforcer 的 JDK 21 要求拦住，测试未执行。真实库存服务故障注入与端到端验收尚未执行。

## Review Focus

1. 网络超时发生在远端提交之后：重试不会重复扣库。
2. 调拨只有出库成功：业务单可见、可恢复，不伪装完成。
3. 重复请求与并发操作：状态与数量只变化一次。
4. 回仓旧质检凭证：不能绕过本次复检。
5. 引擎切换与远程故障：不混账、不将未知余额当零。
