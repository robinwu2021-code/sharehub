# 业务系统（ShareHub）对接独立进销存 · 需求、方案与开发计划

> 2026-10-29 · 读者：powerbank 后端 / 前端 / 测试 · 状态：草稿 · 待评审
>
> **上游**：ai-shop `handoff/sharehub-进销存/进销存-独立服务改造方案.md`（库存服务侧，下称「库存方案」）· [B-租户身份与权限方案](B-业务系统对接-租户身份与权限方案.md) · [评审](../评审-独立进销存方案.md) §六 用户裁决 R1 ~ R4d
> **基线**：powerbank `docs/operator-req-architecture`（PR #1，出库池链路 V161 ~ V176）· 迁移号从 **V185** 起（取号前 `ls`，并通知并行会话；S0 已用 V182）
> 2026-10-29 补：按 [设备库存-运营端功能清单与矩阵](../../requirements/设备库存-运营端功能清单与矩阵.md)（参照 ai-shop B 端 14 屏补齐）回写 —— PB-B8 ~ B12、§2.6 接口、§2.7 菜单；页面逐项细节以该清单 §六、§七 为准

## 〇、一页结论

库存服务管**仓内**（入库、在仓、出库、盘点、流水），**交给商家（签收）为止**；之后的一切归 ShareHub。ShareHub 要做的是四件事：

1. **对接层**：客户端、持久化命令、事件消费、主数据同步、对账 —— 照搬已有的 OpsKit 接入模式（`integration/opskit/`）。
2. **仓外与质检**：到仓待检、业务质检出放行凭证、设备履历、撤机回仓复检 —— 库存服务不做的，ShareHub 补齐。
3. **统一运营端**：仓储作业页面全在 ops-web，经 BFF 调库存服务；商家门户只读看「出给我的设备」；运营端出**统一库存视图与报表**（在仓 + 在途 + 已交付 + 投放中）。
4. **平滑切换**：开关 `sharehub.inventory.engine = LOCAL | SERVICE`。库存服务就绪前，现有出库池链路（LOCAL）照常运行；就绪后迁移数据、切开关，本地 `inv` 模块退场。

| 环节 | 归属 | ShareHub 做什么 |
|---|---|---|
| 到货 / 回仓 → 质检 | **ShareHub** | 到仓待检登记、质检、出放行凭证 |
| 入库、在仓、盘点、仓内余额 | 库存服务 | 页面 + BFF 调用 |
| 发货出库（商家 + 站点 + 接收人）、调整、撤回 | 库存服务 | 页面 + BFF 调用 |
| 签收（领取） | 交接点 | 接收人签收 → 调领取 → 本地写归属与履历 |
| 绑定、装机、上线、解绑、换站点、整站换商家、撤机 | **ShareHub** | 现有链路，不再调库存服务 |
| 退货回仓 | 两边 | ShareHub 复检 → 库存服务退货入库 → ShareHub 业务确认解冻 |
| 统一库存视图、库存报表 | **ShareHub** | 汇总库存服务（仓内）+ 本地（仓外） |

---

# 第一部分 · 需求

编号 `PB-xx`。优先级：P0 一期必须 · P1 一期应做 · P2 后置。

## 1.1 对接基础

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-A1 | **客户端与凭证**：引用 `inventory-client`；系统凭证从密钥配置读取，不进仓库、不下发浏览器；支持轮换 | P0 | 凭证错误时启动即报明确错误 |
| PB-A2 | **持久化命令**：所有写库存服务的动作，在业务事务里写发件箱命令；执行器调用，幂等键 = 发件箱事件号；可恢复错误退避重试，耗尽转死信并告警 | P0 | 远端成功、本地崩溃后重启，不重复执行、不丢 |
| PB-A3 | **事件消费**：按订阅游标拉取库存事件，`eventId` 去重，`resourceVersion` 丢弃旧版本；处理失败不推进游标 | P0 | 重复投递只处理一次 |
| PB-A4 | **主数据同步**：商家、站点（上级 = 商家）、运维人员、设备型号单向同步为库存服务的外部目标 / 物料；带版本号；每日对账只报差异 | P0 | 商家停用后，库存服务拒绝新发货到该商家 |
| PB-A5 | **引擎开关**：`LOCAL`（现有实现）/ `SERVICE`（库存服务）；同一时刻只有一个写权威 | P0 | 切到 SERVICE 后本地 `inv` 写入口全部拒绝 |
| PB-A6 | **对账**：每日比对 SN 身份、出库池与本地归属、退货与本地回仓投影、未完成命令；只报不改，生成处理待办 | P1 | 人为制造一处不一致能被报出 |

## 1.2 运营端仓储作业（经 BFF）

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-B1 | **仓库**：列表、新建、编辑、停用（区域必填） | P0 | 停用条件不满足时逐项显示原因 |
| PB-B2 | **入库**：采购到货、期初登记、退货入库；只能选「质检已通过」的设备（带放行凭证） | P0 | 未质检设备不出现在可选列表 |
| PB-B3 | **发货出库**：选来源仓、**商家**（可选站点）、**内部接收人**、设备（扫 SN；充电宝填数量）→ 预览 → 确认出库 | P0 | 站点不属于商家时前端就拦 |
| PB-B4 | **出库调整 / 撤回**：待签收的记录可改商家 / 站点 / 接收人，或撤回回仓；原因必填 | P0 | 已签收的记录按钮置灰并说明原因 |
| PB-B5 | **待我签收**：接收人看到发给自己的设备，逐台或整单签收 | P0 | 非接收人看不到签收按钮 |
| PB-B6 | **盘点**：开盘（显示冻结范围与阻塞项）、扫码、提交、复核、过账、作废 | P1 | 盘点中该仓发货提示「盘点中（单号）」 |
| PB-B7 | **查询**：仓内余额、在库 SN、流水、出库池（按商家 / 站点 / 接收人 / 状态） | P0 | 运维按商家筛选能看到出到该商家站点的记录 |
| PB-B8 | **供应商档案**（轻量）：新建、改名（历史单据保留当时名称）、停用 / 启用；入库时输入新名称可就地建档 | P1 | 改名后旧入库单仍显示旧名 |
| PB-B9 | **安全库存与缺货**：型号 × 仓设安全库存，低于进「要处理」；0 = 不预警 | P1 | 设 5、在仓 4 → 出现在「要处理」 |
| PB-B10 | **统一单据与跨仓视图**：入库 / 出库 / 盘点 / 调拨一张表，就地展开明细；一型号一行看各仓合计；流水点一行看那张单 | P1 | 从流水能一步到单据、从单据一步到设备 |
| PB-B11 | **发货物流信息**：承运方、运单号（选填，发出后可补）；出库池按运单号查 | P1 | 按运单号能查到出库池记录 |
| PB-B12 | **库存设置**：型号跟踪方式与是否必须质检、默认回仓仓、到仓待检超时天数、盘点超时小时、安全库存默认值 | P1 | 改超时参数后提醒按新值生效 |

## 1.3 业务质检与到仓待检（R4d）

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-C1 | **到仓待检**：新到货、撤机回仓的设备先登记为「到仓待检」（所在仓、来源单、到达时间）；此时**不在库存账上** | P0 | 统一库存视图单列「到仓待检」 |
| PB-C2 | **质检**：沿用现有机柜 / 充电宝质检（`QcServiceImpl`、`dev_qc_record`）；每条质检记录号作为**放行凭证**；记录检查项、阈值快照、操作人 | P0 | 阈值改了不影响历史记录 |
| PB-C3 | **通过 → 入库**：质检通过的设备可一键发起入库（带凭证）；**不通过** → 退供应商或报废（业务侧处置，不进库存） | P0 | 不通过的设备入库按钮不可用 |
| PB-C4 | **复检**：回仓设备必须重新质检，旧的质检通过不能直接复用 | P0 | 回仓设备沿用旧凭证入库被拒 |
| PB-C5 | 到仓待检超过 N 天未处理告警（参数，默认 7 天） | P1 | — |

## 1.4 出库池与设备归属

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-D1 | **签收即领取**：签收成功后本地写 `issued_agent_no` = 商家、记录出库池号 / 领取号、写履历 | P0 | 签收失败或超时显示「处理中」，不允许换请求重提 |
| PB-D2 | **绑定约束**：发到站点的设备只能绑该站点的点位；发到商家（无站点）的可绑该商家任一站点 | P0 | 违反时 409 并提示「先在出库模块调整目的地」 |
| PB-D3 | 解绑、撤机回出库池、换站点、整站换商家**只在本地处理**，不调库存服务 | P0 | — |
| PB-D4 | 「已发货待签收」的设备在台账、生命周期条、上线门禁中有明确展示，不能参与绑定 | P0 | 未签收的设备绑定 409 |

## 1.5 撤机与退货

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-E1 | 撤机去向「回仓库」：撤机完工 → 设备进入「到仓待检」（指定仓；没有仓时生成待办，不静默跳过） | P0 | — |
| PB-E2 | 复检通过 → 发起退货入库（关联原出库池记录）→ 库存服务开新周期并冻结 → ShareHub 解除本地关联（清商家归属、点位已在撤机时解除）→ 调「退货业务确认」解冻 | P0 | 确认前该设备不可再发货 |
| PB-E3 | 撤机去向「回出库池」：纯本地动作，库存服务无感 | P0 | — |
| PB-E4 | 撤机清点缺件：按实收的件退货入库，未回件由业务资产差异追查，不生成仓内盘亏 | P1 | — |

## 1.6 设备履历（仓外，R4b）

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-F1 | 新表 `dev_movement_log`（只追加）：签收、绑定、解绑、装机、撤机、换站点、换商家、到仓待检、报废、遗失 | P0 | 每次位置 / 归属变化恰好一行 |
| PB-F2 | 设备详情时间轴：本地履历 + 库存服务流水（仓内段）+ 状态日志，按时间合并 | P1 | — |

## 1.7 统一库存视图与报表（R4a）

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-G1 | **统一库存视图**：按仓 / 商家 / 型号 × 阶段（到仓待检 · 在仓 · 已发货待签收 · 已交付待绑定 · 已绑定待上线 · 投放中 · 故障维修 · 已离开） | P0 | 同一台设备只出现在一个阶段 |
| PB-G2 | **库存报表**（AC-08）：期初、本期入（采购 / 期初 / 退货）、本期出（发货 / 报废 / 遗失）、期末；仓内段取库存服务期间汇总，仓外段取本地履历 | P0 | 期初 + 入 − 出 = 期末，可追到单据 |
| PB-G3 | 导出走导出中心 | P1 | — |

## 1.8 商家门户

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-H1 | 「我的资产」新页签「待交付设备」：出给本商家及其站点的设备，状态 待交付 / 已交付 / 退回中 | P1 | 后端强制本商家，前端参数无效 |
| PB-H2 | 字段裁剪：不含仓库内部信息、接收人工号（显示「平台运维」）、成本 | P1 | — |

## 1.9 权限与数据范围

按 [B 文档](B-业务系统对接-租户身份与权限方案.md) §三、§四：权限只在 ShareHub 判定；新权限码 `device:inventory:warehouse / :inbound / :receive / :adjust / :stocktake-review` **先登记功能权限清单**；数据范围 `ALL / REGION / AGENT / SELF` 由 BFF 覆盖查询参数。

## 1.10 迁移与切换

| 编号 | 需求 | 优先级 | 验收 |
|---|---|---|---|
| PB-J1 | 迁移前 dry-run：仓内设备、出库池设备、已投放设备、无仓号、重复 SN、待检、在途调拨逐类报告 | P0 | 报告可人工核对 |
| PB-J2 | 切换：冻结本地写 → 最后增量导入 → 逐项核对 → 切开关 → 验证本地写已拒绝 → 真机走一单 | P0 | 有书面切换记录 |
| PB-J3 | 回滚：切换后发现问题，先修复或回滚应用版本；**不重新打开本地写**（避免双写） | P0 | 手册评审通过 |

## 1.11 端到端验收

| 编号 | 场景 | 期望 |
|---|---|---|
| E2E-01 | 到货 → 待检 → 质检通过 → 入库 | 统一视图：待检 −1、在仓 +1；库存服务在库周期 OPEN |
| E2E-02 | 发货到商家站点 → 签收 | 在仓 → 已发货待签收 → 已交付待绑定；本地 `issued_agent_no` 写入；履历两行 |
| E2E-03 | 发货后调整站点、撤回 | 调整留快照；撤回后回到在仓 |
| E2E-04 | 签收请求成功、回包丢失、本地重启 | 恢复后归属正确，不重复领取 |
| E2E-05 | 绑定到非目的站点 | 409 |
| E2E-06 | 装机上线 → 撤机回仓 → 复检 → 退货入库 → 业务确认 | 各阶段准确；确认前不可再发货 |
| E2E-07 | 盘点中发货 | 409，提示盘点单号 |
| E2E-08 | 期间库存报表 | 期初 + 入 − 出 = 期末 |
| E2E-09 | 商家门户 | 只看到本商家，字段已裁剪 |
| E2E-10 | 库存服务不可用 | 查询提示不可用；写入转命令排队，恢复后自动完成 |

---

# 第二部分 · 方案

## 2.1 架构

```mermaid
flowchart LR
  subgraph WEB[ops-web]
    OP[运营端 仓储与出库] --> BFF
    MP[商家门户 待交付设备] --> MBFF
  end
  subgraph PB[ShareHub 后端]
    BFF[InventoryOpsController<br/>@perm + 数据范围] --> PORT
    MBFF[MerchantDispatchController<br/>强制本商家] --> PORT
    PORT{{InventoryPort}}
    PORT --> L[LocalInventoryPort<br/>现有出库池链路]
    PORT --> R[RemoteInventoryPort]
    R --> OB[(outbox)] --> EX[InventoryCommandExecutor]
    PULL[InventoryEventPuller] --> IN[(inventory_inbox)] --> H[事件处理：投影 / 归属 / 履历]
    SYNC[InventoryDirectorySyncJob]
  end
  EX -- 凭证 + 幂等键 --> INV[(库存服务)]
  PULL -- 拉取事件 --> INV
  SYNC -- 外部目标 / 物料 --> INV
```

包落点照搬 OpsKit：`sharehub-app/.../integration/inventory/`

| 类 | 职责 | 对照 OpsKit |
|---|---|---|
| `InventoryClient` | 封装 `inventory-client`：凭证、超时、错误码映射 | `OpsKitClient` |
| `InventoryCommandExecutor` | 实现 `OutboxConsumer`，执行写命令；只有「目标已是期望终态」当成功，409 并发 / 处理中一律抛出重试 | `OpsKitTicketCommandExecutor` |
| `InventoryEventPuller` | 定时拉取事件 → 写 `inventory_inbox` → 分发处理 | `OpsKitWebhookService`（推改拉） |
| `InventoryDirectorySyncJob` | 外部目标与物料同步、每日对账 | `OpsKitDirectorySyncJob` |
| `RemoteInventoryPort` / `LocalInventoryPort` | 引擎开关的两个实现 | `LocalAlarmTicketPort` 模式 |

**为什么照搬**：OpsKit 已经在生产路径上验证过「同事务写意图 → 执行器调用 → 幂等键取事件号 → 退避重试 → 死信」，包括 409 不能当完成的教训；库存服务的命令语义（同键同参返回原结果）与它一致。

## 2.2 设备阶段（展示态）

不新增设备存储状态（沿用评审 C3 的建议）；阶段由现有列 + 投影 + 本地单据派生，前端 `lib/device-lifecycle.ts` 与后端同一口径：

| 阶段 | 判定 | 数据来源 |
|---|---|---|
| 到仓待检 | 有未结的到仓待检记录 | 本地 `dev_intake` |
| 在仓 | 投影 `inv_stage = IN_STOCK`（带仓号） | 库存事件投影 |
| 已发货待签收 | 投影 `inv_stage = DISPATCHED` | 同上 |
| 已交付待绑定 | `issued_agent_no` 非空 ∧ `location_no` 为空 | 本地（签收后写） |
| 已绑定待上线 / 投放中 / 故障维修 | 现有规则 | 本地 |
| 已离开 | 报废 / 遗失 / 售出 | 本地 |

**防住什么**：「已发货待签收」与「已交付待绑定」若共用 `IN_STOCK + issued_agent_no`，未签收的设备就能被绑定，库存服务里的出库池还停在待签收 —— 两边对不上。

## 2.3 本地数据变更（V181 起）

| 表 | 变更 | 说明 |
|---|---|---|
| ◇ `dev_cabinet` / `dev_powerbank` | 加 `inv_stage`（`IN_STOCK / DISPATCHED / NONE`）、`inv_serial_id`、`inv_dispatch_item_id`、`inv_claim_no`、`inv_version` | 库存投影，**只由事件处理与签收回写**；`warehouse_no` 在 SERVICE 模式下也变为投影 |
| ★ `dev_intake` / `dev_intake_item` | 到仓待检单：`intake_no`、`kind`（`ARRIVAL / RETURN`）、`warehouse_no`、`source_ref`、明细（设备号 / 宝数量、质检记录号、结论、处置） | 待检不在库存账上，这里是它唯一的记录 |
| ◇ `dev_qc_record` | 加 `rule_snapshot`（阈值快照）、`intake_no`、`consumed_by`（被哪张入库单用掉） | 质检记录号即放行凭证；一份凭证只能用一次 |
| ★ `dev_movement_log` | 只追加：`item_type / item_no / event / from / to / agent_before / agent_after / reason / source / operator / occurred_at / idem_key` | 仓外履历；Mapper 只暴露插入 |
| ★ `inventory_inbox` | `event_id` 唯一、`type`、`resource_version`、`status`、`processed_at`、`error` | 事件去重与重试 |
| ★ `inventory_sync_state` | 外部目标 / 物料的最后同步版本、订阅游标 | 断点续传 |
| `inv_*`（本地旧表） | SERVICE 切换后**冻结只读**，两个版本后归档 | 业务差异（撤机清点）保留在 `inv_asset_diff` |

## 2.4 关键流程

**① 到货入库**
到仓登记（`dev_intake`，ARRIVAL）→ 逐台质检（`dev_qc_record`）→ 通过的设备勾选「入库」→ BFF 写命令「入库（采购 / 期初）」带 `qcRef` → 执行器调库存服务建单并过账 → 事件 `InboundPosted` → 投影 `inv_stage = IN_STOCK`、待检单行结项。

**② 发货出库**
运营选来源仓、商家（可选站点）、接收人、设备 → BFF 校验权限与数据范围 → 命令「建出库单并过账」→ 事件 `DispatchCreated` → 投影 `inv_stage = DISPATCHED`、记 `inv_dispatch_item_id` → 接收人「待我签收」出现。

**③ 签收**
接收人签收 → BFF 校验「本人 = 接收人」→ 命令「领取」（幂等键固定）→ 执行器调用成功 → 同一处理里写 `issued_agent_no`、`inv_claim_no`、`inv_stage = NONE`、履历 `CLAIM` → 设备进入「已交付待绑定」。超时：页面显示处理中，执行器按幂等键重试或查结果，**不让用户换请求重提**。

**④ 调整 / 撤回**
待签收时直接命令库存服务（`redirect / revoke`）；撤回事件到达后投影回 `IN_STOCK`。已签收的：先在本地确认「未绑定、无业务占用」→ 命令「释放」→ 再调整。

**⑤ 撤机回仓**
撤机完工（去向回仓）→ 解绑点位（现有）→ 建 `dev_intake`（RETURN）→ 复检 → 通过 → 命令「退货入库」（关联 `inv_dispatch_item_id`，带新 `qcRef`）→ 事件 `ReturnReceived` → 本地清 `issued_agent_no`、写履历 → 命令「退货业务确认」→ 事件 `ReturnCleared` → 设备可再发货。

**⑥ 盘点**
页面经 BFF 直调库存服务（开盘、扫码、提交、复核、过账）；本地不落数据，只消费 `CountPosted` 刷新投影。

## 2.5 业务质检（R4d）

沿用 `QcServiceImpl`，补三点：

| 补充 | 做法 | 防住什么 |
|---|---|---|
| 阈值快照 | 质检时把 `device.qc.min_battery / max_cycles` 等当时值写进 `rule_snapshot` | 改参数后历史结论无法解释 |
| 凭证一次性 | `consumed_by` 记录用掉它的入库单；再次使用拒绝 | 一份「通过」被多台设备或多轮入库复用 |
| 回仓必须复检 | 退货入库只接受 `intake_no` 属于本次回仓的质检记录 | 撤机回来没检就重新发出去 |

机器自动检测（开机、槽位、通信）等设备网关接入后再接，一期质检为人工录入 + 规则判定。

## 2.6 BFF 接口（前缀 `/api/ops/`，契约约定：`Result / PageResult`、camelCase、无 `delete*`）

| 能力 | 端点 | 权限码 |
|---|---|---|
| 仓库 | `GET /inventory/warehouses` · `POST /inventory/warehouses` · `POST /inventory/warehouses/{no}/disable` | 读 `:read`；写 `:warehouse` |
| 到仓待检 | `GET /inventory/intakes` · `POST /inventory/intakes` · `GET /inventory/intakes/{no}` | `:inbound` |
| 质检 | 沿用 `POST /devices/{no}/qc`、`/powerbanks/{no}/qc`、`GET /qc-records` | 现有 |
| 入库 | `POST /inventory/inbounds`（带质检记录号）· `GET /inventory/inbounds` | `:inbound` |
| 发货 | `POST /inventory/outbounds` · `GET /inventory/outbounds` | `:transfer` |
| 出库池 | `GET /inventory/dispatch-pool` · `POST /inventory/dispatch-pool/{id}/redirect` · `/revoke` · `/release` | 读 `:read`；改 `:adjust` |
| 签收 | `GET /inventory/my-receipts` · `POST /inventory/dispatch-pool/claims` | `:receive` |
| 盘点 | `…/inventory/counts…`（透传库存服务） | `:stocktake` / `:stocktake-review` |
| 供应商 | `GET /inventory/suppliers` · `POST /inventory/suppliers` · `POST /inventory/suppliers/{id}/archive` · `/unarchive` | 读 `:read`；写 `:inbound` |
| 安全库存 | `PUT /inventory/balances/safety-stock` | `:warehouse` |
| 单据 | `GET /inventory/documents`（类型、单号、仓、期间）· `GET /inventory/documents/{kind}/{no}` | `:read` |
| 库存设置 | `GET / POST /inventory/settings` | 读 `:read`；写 `:warehouse` |
| 查询 | `GET /inventory/balances`（`shortageOnly`、`groupBy=item` 跨仓）· `/serials` · `/ledger` · `/overview`（统一视图）· `/report`（期间报表） | `:read` |
| 商家门户 | `GET /merchant/devices/dispatch` | `device:cabinet:read`（商家） |

现有 `/devices/issue`、`/devices/pool-return`、`/inventory-transfers/*`、`/asset-diffs/resolve` 在 SERVICE 模式下返回 409「已迁移到库存服务」，LOCAL 模式下照旧。

## 2.7 运营端与商家门户

**菜单（设备管理）**：新增「入库与质检」组，与「仓储与出库」共 11 叶；完整表与逐页功能见 [设备库存-运营端功能清单与矩阵](../../requirements/设备库存-运营端功能清单与矩阵.md) §一、§六。按项目菜单约定落迁移 + nav 四件套。

| 组 | 叶 | 路径 | 说明 |
|---|---|---|---|
| 入库与质检 | 到仓待检 | `/devices?tab=intakes` | 新；含质检与「去入库」 |
| | 入库单 | `/devices?tab=inbounds` | 新；采购 / 期初 / 退货；期初导入从台账迁来 |
| | 供应商 | `/devices?tab=suppliers` | 新；轻量档案（PB-B8） |
| 仓储与出库 | 库存总览 | `/devices?tab=inventory-overview` | 新；统一视图（S6） |
| | 仓库 | `/devices?tab=warehouses` | 新；S0 只读 → S4 可管理 + 库存设置区（PB-B12） |
| | 库存余额 | `/devices?tab=stock` | 改造；要处理 · 按仓 · 跨仓 · 在仓明细 · 流水 · 单据六个视图（PB-B9、B10） |
| | 发货出库 | `/devices?tab=outbounds` | 原「库存调拨」改名改造；含物流信息（PB-B11）与处置出库 |
| | 出库池 | `/devices?tab=issued` | 改造：四态 + 调整 / 撤回 / 释放；按运单号查 |
| | 待我签收 | `/devices?tab=my-receipts` | 新；连续扫码核对 |
| | 盘点 | `/devices?tab=stocktakes` | 新（P1） |
| | 资产差异 | `/devices?tab=asset-diffs` | 保留，只放业务差异（撤机清点） |

另：设备档案组新增「设备履历」；撤机与回仓改为生成到仓待检；数据报表新增「库存报表」（含台账平衡校验与压库）；切换期一次性页「迁移对齐」（不进常驻菜单）。

另：设备详情加库存时间轴；报表 L1 加「库存报表」；台账与生命周期条按 §2.2 阶段派生。新页面一开始就用 `<ReadOnlyNotice>`、`<StatusBadge>`、解释性空态（设计规范棘轮）。

**商家门户**：「我的资产」加「待交付设备」页签（PB-H1）。

**前端契约与 mock**：新增 `lib/api/contracts/inventory.ts`；mock 实现一个**库存服务的 Fake**（`lib/mock/db/inventory/*`），状态机、冻结、幂等在 mock 层强制，重开可读回；`backend-ready.ts` 用 `featureReady("inventory.service")` 控制，未就绪时 HTTP 方法 501。

## 2.8 引擎开关与切换

| 阶段 | 开关 | 行为 |
|---|---|---|
| 库存服务就绪前 | `LOCAL` | 现有出库池链路 + 本地 `inv`；新页面在 mock 上开发 |
| 联调 | 测试环境 `SERVICE` | 对库存服务测试环境 |
| 切换 | 生产 `LOCAL → SERVICE` | 按 PB-J2 步骤；本地写入口拒绝 |
| 回滚 | 不回 `LOCAL` 写 | 修复或回滚应用版本；必要时冻结双方写、导出增量人工处理 |

## 2.9 与上线门禁的关系

门禁 10 项不变；`QC` 项改为读「最近一次通过的质检记录且未被后续回仓作废」；`LOCATION` 项要求设备已签收（`inv_stage = NONE` ∧ `issued_agent_no` 非空）。合同 / 勘测仍按 V174 一期跳过。

## 2.10 过渡期（库存服务上线前）

生产目前一个仓都没有，「退回仓库」「撤机回仓」选不到仓。过渡方案：

- 迁移种入运营方实际的仓（名称、区域由运营提供）+ 只读仓库列表；
- 本地 `inv` 模块**冻结新功能**，只修 bug；
- 新页面全部按库存服务契约在 mock 上开发，不在本地 `inv` 上再造一遍。

---

# 第三部分 · 开发计划

## 3.1 与库存服务里程碑的依赖

| ShareHub 阶段 | 依赖库存服务 | 内容 |
|---|---|---|
| **S0 过渡** | 无 | 种仓 + 只读仓库列表；本地 `inv` 冻结新功能 |
| **S1 契约与 Fake** | M0（OpenAPI v1） | 引入客户端；Java Fake（测试用）+ ops-web mock |
| **S2 业务侧基础** | 无 | 履历表与统一写口、到仓待检、质检补强、阶段派生、`InventoryPort` 抽象（LOCAL 实现包住现有链路） |
| **S3 对接层** | M1 | 客户端、命令执行器、事件拉取与 inbox、主数据同步、对账 |
| **S4 运营端与商家门户** | M1（页面先跑 mock） | 菜单（两组 11 叶）、商家门户页签、设备时间轴 |
| **S5 联调** | M2 | 测试环境全链路 E2E-01 ~ 07、09、10 |
| **S6 统一视图与报表** | M3 | 统一库存视图、期间报表（E2E-08） |
| **S7 迁移切换** | M4 | dry-run、切换、回滚演练、旧表归档 |

## 3.2 任务

规模：S ≤ 2 天 · M 3 ~ 5 天 · L 1 ~ 2 周（单人）。每项：先写失败测试 → 实现 → 回归 → 显式路径提交。

| # | 任务 | 验收 | 规模 |
|---|---|---|---|
| S0.1 | 迁移种仓 + `GET /inventory/warehouses` 只读 + 出库池「退回仓库」改为下拉选仓 | 退回仓库可选到仓 | S |
| S1.1 | 引入 `inventory-client`；`InventoryClient` 封装与错误码映射 | 单测覆盖错误映射 | S |
| S1.2 | Java Fake（内存实现库存服务契约，场景测试用） | 契约测试对 Fake 通过 | M |
| S1.3 | ops-web `contracts/inventory.ts` + mock Fake（状态机、幂等、冻结） | mock 测试全绿 | M |
| S2.1 | `dev_movement_log` + 统一写口；接入签收、绑定、解绑、装机、撤机、换站点、整站换商家、报废 | 每个动作恰好一行（I8）；ArchUnit 守写入点 | L |
| S2.2 | 到仓待检 `dev_intake` + 页面接口 | PB-C1 | M |
| S2.3 | 质检补强：阈值快照、凭证一次性、回仓必须复检 | PB-C2 ~ C4 | M |
| S2.4 | 设备阶段派生（前后端同口径）+ 绑定约束（PB-D2、D4） | E2E-05 | M |
| S2.5 | `InventoryPort` 抽象；`LocalInventoryPort` 包住现有 `issue / pool-return / bind` | 现有测试全绿 | M |
| S3.1 | `InventoryCommandExecutor`（OutboxConsumer）+ `RemoteInventoryPort` | E2E-04（对 Fake） | M |
| S3.2 | `InventoryEventPuller` + `inventory_inbox` + 投影处理 | 重复事件只处理一次 | M |
| S3.3 | `InventoryDirectorySyncJob`：商家、站点、运维、型号同步与对账 | PB-A4、A6 | M |
| S3.4 | 新权限码登记真源表 + 角色种子 + BFF 数据范围覆盖 | 权限契约测试 | M |
| S4.1 | 菜单迁移（V18x）+ nav 四件套；仓库、到仓待检、发货出库、待我签收 | 菜单测试全绿 | M |
| S4.2 | 出库池页改造（四态 + 调整 / 撤回）、库存余额改读 BFF | — | M |
| S4.3 | 盘点页 | — | M |
| S4.4 | 商家门户「待交付设备」 | E2E-09 | S |
| S4.5 | 设备详情库存时间轴 | — | M |
| S4.6 | 供应商档案、安全库存与缺货、统一单据与跨仓视图、发货物流信息、库存设置（PB-B8 ~ B12）；「挑设备」共用件与连续扫码 | 清单 §六 6.3、6.5 ~ 6.7 | L |
| S5.1 | 测试环境联调全链路 | E2E-01 ~ 07、10 | L |
| S6.1 | 统一库存视图 `/inventory/overview` + 页面 | PB-G1 | M |
| S6.2 | 期间库存报表（仓内取期间汇总 + 仓外取履历）+ 导出 | E2E-08 | M |
| S7.1 | 迁移 dry-run 脚本与差异报告（配合库存服务 T4.1） | PB-J1 | M |
| S7.2 | 切换手册、回滚演练、旧 `inv` 写入口拒绝与归档 | PB-J2、J3 | M |

单人粗估：S0 0.5 周 · S1 1.5 周 · S2 4 周 · S3 3 周 · S4 4.5 周 · S5 1.5 周 · S6 1.5 周 · S7 1.5 周，合计约 18 周；S2 / S4 与库存服务 M1 ~ M2 并行，**关键路径由库存服务决定**（约 13 周后可联调）。两人并行约 9 ~ 10 周。

## 3.3 测试

| 层 | 要点 |
|---|---|
| 后端场景测试 | 对 Java Fake 跑全部流程；E2E-04 用 Fake 模拟「远端成功、本地崩溃」 |
| 契约 | `contract.test.ts` 方法锚点与总数；`drift.test.ts` 缺口棘轮保持 0；`api-align.py --strict` |
| 菜单 | `nav-seed`、`nav`、`nav-visibility` 快照、后端 `MenuTreeTest` / `MenuPermissionContractTest` |
| 前端 mock | 状态机、幂等、冻结在 mock 层的用例 |
| 联调 | 测试环境 E2E 全表，证据含两边 traceId |

## 3.4 风险

| 风险 | 对策 |
|---|---|
| 库存服务延期，ShareHub 页面空等 | S1 Fake + mock 先行；S0 过渡方案让仓库先可用 |
| 签收后本地写失败，两边不一致 | 命令执行与本地写在同一处理里；失败重试；每日对账兜底 |
| 漏接履历写入点 | S2.1 一次接完 + ArchUnit 守门 |
| 切换期间双写 | 开关单一写权威；切换后本地写入口拒绝；回滚不重开本地写 |
| 并行会话改到同一批文件（设备、菜单、迁移号） | 开工前 `git status --porcelain` 目标文件；迁移号 `ls` 后通知 |

## 3.5 待确认

| # | 事项 | 建议 |
|---|---|---|
| Q1 | 签收时机 | 内部接收人签收即领取 |
| Q2 | 充电宝在库存服务按 SN 还是数量 | 一期按数量 |
| Q6 | 发到站点的设备能否绑同商家其它站点 | 不能，先调整目的地 |
| P1 | 仓管是否单独建角色 | 单独建 |
| P3 | 仓库区域是否必填 | 必填 |
| D1 | 是否需要仓间调拨 | 二期；一期只有发货、退货 |
| D2 | 到仓待检超时天数 | 默认 7 天，参数可改 |
