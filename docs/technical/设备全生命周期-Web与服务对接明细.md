# 设备全生命周期 Web 与服务对接明细

> 更新：2026-10-30。主分支基线：`origin/main@b57c5119bb184a54accaca142979261ffaf14969`；另列当前工作区未提交的统一审核增量。本文为逐功能对接与改造清单，不表示目标链路已全部实现或已通过真实服务验收。
>
> 承接[库存系统对接方案](库存系统对接方案.md)、[生命周期角色菜单对照](../requirements/设备生命周期-角色菜单功能对照.md)。本次明确：库存默认 SERVICE；不设置独立人工设备注册前置步骤；需要检测、安装或通信接入时自动建立业务设备关联。

## 1. 阅读规则与调用边界

本文以当前路由为兼容入口，目标是收口库存权威，不要求前后端同时替换所有 URL。

| 标识 | 含义 |
|---|---|
| **微服务** | 库存业务最终执行在 ai-shop 独立库存服务；powerbank 本地 Service 只是鉴权、映射、调用恢复及业务关联适配 |
| **本地** | 设备经营、通信、现场作业或采购商业流程；不直接维护库存余额 |
| **协作** | 本地业务与库存微服务分别完成各自事务，通过稳定操作号、领取凭证、事件和恢复任务衔接 |
| **审核** | OpsKit 统一审核；审核结果驱动执行，不替代库存服务的规则校验 |
| **M** | `origin/main` 已有接口或代码；不代表目标调用方式已完成 |
| **W** | 当前工作区已有增量，尚未提交；不能当成主分支能力 |
| **P** | 拟新增或改造；拟定路由、类名不是已发布合同 |
| **V** | 远端契约需锁定真实服务版本后确认；不能把设计草案当成已有接口 |

路径约定：`O=/api/ops`，`I=/api/inventory/v1`。表内 `O/...`、`I/...` 均表示完整 HTTP 路径；`GET/POST` 分别表示查询和动作。`/{no}` 等占位符按具体业务单号传值。类名省略包路径，源码索引见第 11 节。

```text
Web（用户令牌）
  ├─ 库存操作 → powerbank Controller
  │             → 库存接入 Service：权限/范围/映射/任务恢复
  │             → InventoryApi → HttpInventoryApi → InventoryEndpoint
  │             → ai-shop inventory-svc（系统凭证，库存单据/余额/SN）
  ├─ 设备操作 → powerbank Controller → 本地设备/工单/网关 Service
  └─ 统一审核 → OpsKit 审批 API
                → 签名 webhook → 本地审核结果处理器
                → 持久化执行任务 → 库存微服务
```

库存默认 `SERVICE` 是目标要求，主分支 `InventoryEngineRouter` 当前仍默认 `LOCAL`，必须改造并配套切流。远端不可用或超时不得自动回落 LOCAL；已发出的旧操作按其冻结执行端及原幂等键恢复。Web 不持有库存系统凭证，不直接调库存管理端。

### 1.1 权威数据归属

| 对象 | 权威归属 | 允许本地保存的内容 |
|---|---|---|
| 库存物料、仓库、仓内 SN、余额、库存单据、仓内盘点 | 库存微服务 | 业务编号映射、单据引用、必要只读投影；不得另建权威结存 |
| 仓内质检任务、证据、结论、放行 | 目标归库存微服务，契约 V | 本地设备检测执行记录和远端任务引用；主分支现状仍为本地质检，不得标为已迁移 |
| 商家、站点、点位、员工 | 本地业务 | 向库存服务同步 external-target，用于出库目的地和接收人 |
| 采购需求、采购订单、价格及交期 | 暂归本地采购业务，P | 采购合同与收货关联；当前已核库存宿主 API 不足以证明存在完整采购订单能力 |
| 库存供应商、采购供应商 | 必须确定单一主数据源，V | 主分支 `SupplierServiceImpl` 本地维护；目标可映射库存供应商或外部目标，不另造两个可独立改写的档案 |
| 通信身份、柜槽、安装、营业、借还、现场维修 | 本地设备/租赁业务 | 稳定业务设备身份，关联 inventorySerialId/SN、dispatchId、claimNo |
| 审批实例、任务、决定 | OpsKit | 本地审批关联、冻结业务版本、执行状态与错误；库存执行结果和审批状态分开展示 |

## 2. Web 页面与源码入口

下面是现有页面，不把规划菜单误写成已有路由。拟新增功能应优先扩展对应页面。

| 页面/菜单 | 路由 | 当前组件/入口 | 当前 Web API 模块 |
|---|---|---|---|
| 仓库/库存设置 | `/inventory?tab=warehouses`、`tab=settings` | `components/inventory/warehouses.tsx` | `deviceHttp` |
| 物料/供应商 | `/inventory?tab=materials`、`tab=suppliers` | `materials.tsx`、`suppliers.tsx` | `inventoryHttp` / `deviceHttp` |
| 入库单/期初登记 | `/inventory?tab=intakes` | `intakes.tsx`，期初复用其 OPENING 流程 | `deviceHttp` |
| 采购单 | 待新增；建议纳入库存管理→采购与收货 | 当前无独立采购页；不能把 `intakes` 当采购订单 | 拟新增采购 API |
| 出库/待安装设备 | `/inventory?tab=issued` | `issued-pool.tsx`；目标可拆视图，当前是同一页签 | `deviceHttp` |
| 调拨/盘点/差异 | `/inventory?tab=transfers`、`tab=counts`、`tab=asset-diffs` | `transfers-tab.tsx`、`transfer.tsx`、`stock-counts.tsx` | `deviceHttp` |
| 库存余额/物料库存/流水 | `/inventory?tab=stock`、`tab=material-stock`、`tab=ledger` | `stock-balance.tsx`、`material-stock.tsx`、`stock-ledger.tsx` | `deviceHttp` / `inventoryHttp` |
| 物料单据/处置出库 | `/inventory?tab=material-docs`、`tab=disposals` | `material-docs.tsx`、`disposals.tsx` | `inventoryHttp` / `deviceHttp` |
| 设备台账/充电宝/详情履历 | `/devices?tab=cabinets`、`tab=powerbanks`；`/devices/detail?no=...` | `app/devices/page.tsx`、设备详情及 `components/device/movements.tsx` | `deviceHttp` |
| 部署安装/待上线/维修/撤机/退出 | `/devices?tab=installs`、`tab=pending-live`、`tab=repairs`、`tab=removals`、`tab=retired` | `components/device/lifecycle-views.tsx` | `deviceHttp` / `workOrderHttp` |
| 监控/指令/日志/OTA | `/devices?tab=monitor`、`tab=commands`、`tab=logs`、`tab=ota` | `app/devices/page.tsx` 及设备操作组件 | `deviceHttp` |
| 工单处理 | `/work-orders?view=list` | `app/work-orders/page.tsx` | `workOrderHttp` |
| 统一审批 | `/flow/approvals` | flow 页面，`lib/flow/client.ts` | `flow`，真实配置默认反代基址 `/api/opskit/v1` |

## 3. 采购 收货 质检 入库

表中的“本地执行链”描述已有代码或明确标 P 的拟建接入服务；最后两列明确目标落点与差距。所有新增自动关联都不增加人工注册菜单。

| ID/节点与操作 | Web 功能/方法 | Web→Controller API | Controller→本地执行链 | 目标落点/远端 API | 状态与改造 |
|---|---|---|---|---|---|
| A01 仓库查询/维护 | 仓库；`listWarehouses/createWarehouse/updateWarehouse/archiveWarehouse/unarchiveWarehouse` | GET/POST `O/inventory/warehouses`；POST `/{warehouseNo}`、`/archive`、`/unarchive` | `InventoryController` → `WarehouseDirectory.list` / `WarehouseAdminServiceImpl` | **微服务**；GET/POST `I/warehouses`；已核远端 PATCH `I/warehouses/{locationId}`、POST `/{locationId}/disable`，宿主客户端待封装，恢复启用合同 V | M 当前本地建档、按名称映射；P 改稳定编码映射及远端权威，不能将 POST 本地仓库宣称已完成远端维护 |
| A02 物料型号准备 | 物料；`listMaterials/createMaterial/updateMaterial/archiveMaterial/unarchiveMaterial` | GET/POST `O/inventory/materials`；POST `/{materialNo}`、`/archive`、`/unarchive` | `InventoryController` → `MaterialServiceImpl` → `InventoryLinks/InventoryApi` | **微服务**；PUT `I/items/by-ref/{ref}`；查询/停用能力 V | M 现有本地主数据、部分远端同步；P 调整库存属性归属。设备型号不能只用 CABINET/POWERBANK 两个通用物料代替全部型号 |
| A03 供应商 | 供应商；`listSuppliers/createSupplier/updateSupplier/archiveSupplier/unarchiveSupplier` | GET/POST `O/inventory/suppliers`；POST `/{supplierNo}`、`/archive`、`/unarchive` | `InventoryController` → `SupplierServiceImpl` | **协作**；退货目标可 PUT `I/external-targets/SUPPLIER/{ref}`；完整供应商合同 V | M 本地；P 明确采购与库存供应商映射，不能把 external-target 当完整采购供应商能力 |
| A04 采购开单/查询/修改 | 采购单；拟 `listPurchases/createPurchase/getPurchase/updatePurchase` | **P** GET/POST `O/inventory/purchases`；GET/POST `/{purchaseNo}` | **P** `InventoryController` → 采购业务 Service（名称待实现确认） | **本地采购业务**；不增加库存，无已核完整远端采购合同 | P 仅有工作区 V213 结构草案，不能标为采购 API 已实现；不要求预建设备 |
| A05 分批收货 | 采购单→收货；拟 `receivePurchase` | **P** POST `O/inventory/purchases/{purchaseNo}/receipts` | **P** 采购 Service 校验余量 → 库存接入 Service 持久化意图 → `InventoryApi` | **协作，库存远端**；POST `I/inbounds` → POST `I/inbounds/{no}/post`，PURCHASE | P 关联采购行、来源唯一号、仓库、实收 SN；实收待检计实存/不可用的语义须与远端 V 核定 |
| A06 非采购到货登记 | 入库单；`listIntakes/getIntake/createIntake` | GET/POST `O/inventory/intakes`；GET `/{intakeNo}` | `InventoryController` → `IntakeServiceImpl.page/get/create` | **微服务**；库存 SN 建档与实收应进入远端入库协议 | M 当前登记与待检本地持有、实际入库才远调；P 改从远端 SN/入库单衔接，不要求先手工创建本地设备 |
| A07 期初登记 | 入库单 OPENING；`createIntake` | POST `O/inventory/intakes`，`kind=OPENING` | `InventoryController` → `IntakeServiceImpl.create` → `InventoryPort.inbound` | **微服务**；POST `I/inbounds` + `/post`，目标 purpose=OPENING | M 现有 RemoteInventoryPort 按 RETURN/否则 PURCHASE 选用途，需改造，不能把本地 OPENING 等同远端已正确使用 OPENING |
| A08 质检任务与检测结果 | 入库单逐件质检；`inspectIntakeItem` | POST `O/inventory/intakes/{intakeNo}/items/{itemType}/{itemNo}/qc` | `InventoryController` → `IntakeServiceImpl.inspect` → `QcServiceImpl` | **目标微服务主导**；仓内任务/模板/证据/结论接口 **V**；本地仅设备检测执行 | M 当前本地质检；P 收口。未核到的 `/qc-*` 路径不在本文伪造 |
| A09 设备详情旧质检入口 | 详情质检；`inspectCabinet/inspectPowerbank/listQcRecords` | POST `O/devices/{cabinetNo}/qc`、`O/powerbanks/{powerbankNo}/qc`；GET `O/qc-records?itemNo=` | `AssetOpsController` → `QcServiceImpl.inspectCabinet/inspectPowerbank/records` | 仓内动作改走 A08 **微服务**；现场检测继续 **本地** | M；P 按设备是否在仓/任务类型分流，禁止成为绕过仓内质检的写入口 |
| A10 入库确认/放行 | 入库单；`inboundIntake` | POST `O/inventory/intakes/{intakeNo}/inbound` | `InventoryController` → `IntakeServiceImpl.inbound` → `InventoryPort` → `RemoteInventoryPort.inbound` | **微服务**；POST `I/inbounds`、`/{no}/post`；已有协议用 gateRef | M 有 SERVICE 路径，但目前在质检后计入；P 区分“实收计存”与“质检放行”，不得两个节点重复加库存 |
| A11 不合格退货/报废 | 入库单；`disposeIntake` | POST `O/inventory/intakes/{intakeNo}/dispose` | `InventoryController` → `IntakeServiceImpl.dispose` | **协作**；已计库存的须远端 RETURN_SUPPLIER/SCRAP 出库；未实收入账拒收不扣库存 | M 本地处置分支；P 统一审核、远端单据和处理恢复，不能仅改设备退役状态 |

关键输入：采购行号、来源收货号、`warehouseNo/itemId/model/SN`、`receivedAt`、`requestKey`；目标不要求 `cabinetNo` 或 `powerbankNo` 已在本地创建。SN 与设备通信号分开存，不能假定两者总是相同。

## 4. 库内作业与库存查询

| ID/节点与操作 | Web 功能/方法 | Web→Controller API | Controller→本地执行链 | 目标落点/远端 API | 状态与改造 |
|---|---|---|---|---|---|
| B01 设备库存余额 | 库存余额；当前 `listCabinets/listPowerbanks` | GET `O/cabinets`、`O/powerbanks` | `OpsController` → `CabinetService`；`DeviceController` → `PowerbankService`；Web 汇总 | **微服务**；GET `I/balances`、`I/serials` | M 当前不是远端库存账；**P** GET `O/inventory/balances`、`O/inventory/serials` → `InventoryController` → 库存查询适配 → `InventoryApi`，页面替换数据源 |
| B02 生命周期数量/下钻 | 生命周期视图；`getStockStages/getStageItems` | GET `O/inventory/stages`、`O/inventory/stage-items` | `InventoryController` → `StockViewServiceImpl.stages/stageItems` | **协作聚合**；仓内段取远端 balances/serials，仓外段取本地设备/订单/工单 | M 当前主要本地投影；P 不用 RENTED/在线数替代仓内账，响应标明来源及同步时间 |
| B03 物料库存 | 物料库存；`listMaterialBalances` | GET `O/inventory/material-balances` | `InventoryController` → `MaterialServiceImpl.balances` → `InventoryApi.balances/serials` | **微服务**；GET `I/balances`、`I/serials` | M 已有 SERVICE 适配；保持远端权威，补范围、分页/批量能力核验 |
| B04 库存流水 | 库存流水；`listStockLedger` | GET `O/inventory/ledger` | `InventoryController` → `StockViewServiceImpl.ledger` | **微服务**；GET `I/ledger`，按 afterId 继续读取 | M 有远端路径，但当前远端窗口 500；P 完整游标分页，不得把窗口截断当完整账 |
| B05 期间汇总 | 库存报表；`getStockPeriodSummary` | GET `O/inventory/period-summary` | `InventoryController` → `StockViewServiceImpl.periodSummary` | **微服务**；GET `I/reports/period-summary` | M 已有 SERVICE；目标不采用本地履历倒推替代权威库存报表 |
| B06 安全库存/预警 | 库存设置；`listStockThresholds/saveStockThreshold/saveMaterialMinQty` | GET/POST `O/inventory/stock-thresholds`；POST `O/inventory/material-thresholds` | `InventoryController` → `StockThresholdServiceImpl` / `MaterialServiceImpl.saveMinQty`；告警经 `LocalInventoryState` | **本地预警配置＋远端库存查询**；GET `I/balances/serials` | M；目标下限可以本地业务配置，判定数量必须来自远端，不能报服务不可用为库存为零 |
| B07 调拨建单/明细 | 调拨；`list/get/saveInventoryTransfer/setTransferItems` | GET/POST `O/inventory-transfers`；GET/POST `/{transferNo}`；POST `/{transferNo}/items` | `InventoryController` → `InventoryTransferServiceImpl`；`AssetOpsController` → `TransferOpsServiceImpl.setItems` | **微服务目标**；当前宿主适配用 TRANSFER_OUT/TRANSFER_IN 组合，独立调拨单合同 V | M 本地调拨单；P 可保留流程引用，库存单据/在途不能本地独立承诺 |
| B08 调拨发货 | 调拨→发货；`shipTransfer` | POST `O/inventory-transfers/{transferNo}/ship` | `AssetOpsController` → `TransferOpsServiceImpl.ship` → `InventoryPort.transferShip` | **微服务**；POST `I/outbounds`（TRANSFER_OUT）→ `/{no}/post` | M；P 固定源仓授权、持久化步骤/远端单号/原幂等键，超时不能重新发货 |
| B09 调拨实收 | 调拨→签收；`receiveTransfer` | POST `O/inventory-transfers/{transferNo}/receive` | `AssetOpsController` → `TransferOpsServiceImpl.receive` → `InventoryPort.transferReceive` | **微服务**；POST `I/inbounds`（TRANSFER_IN）→ `/{no}/post`，仅实收 SN | M；P 目标仓授权，缺件保留在途差异。不能把“发货没有映射”直接解释为从未远端执行 |
| B10 盘点列表/详情 | 盘点；`listStockCounts/getStockCount` | GET `O/inventory/counts`、`/{countNo}` | `InventoryController` → `StockViewServiceImpl.counts/count` → `InventoryApi.count` | **微服务**；GET `I/counts/{no}`；列表现按本地远端引用索引逐单查询 | M；P 若需要跨接入来源完整列表，应核远端查询能力，不把本地索引当全部远端单据 |
| B11 开盘/扫码 | 盘点；`openStockCount/scanStockCount` | POST `O/inventory/counts`、`/{countNo}/scans` | `InventoryController` → `StockViewServiceImpl.openCount/scan` | **微服务**；POST `I/counts`、`/{no}/scans` | M；仓库权限、盘点冻结及 SN 校验由后端和远端共同保证 |
| B12 提交盘点复核 | 盘点→提交；`submitStockCount` | POST `O/inventory/counts/{countNo}/submit` | `InventoryController` → `StockViewServiceImpl.submit` → **W** `CountReviewService.submit` → OpsKit | **审核＋微服务**；远端 `/{no}/submit`，统一审批创建与回执见第 8 节 | M 原直接远端提交；W 已有冻结版本与统一审核；真实部署验收待做 |
| B13 复核决定/库存调整 | 统一审批→通过/驳回；`flow.decide` | OpsKit 审批 API；宿主 `POST /internal/opskit/webhook` | `OpsKitWebhookController` → `OpsKitWebhookService` → **W** `CountReviewService` | **审核＋微服务**；POST `I/counts/{no}/approve` 或 `/reject`，通过后 `/post` | M 原 approve/reject Web 动作仍在 HTTP 客户端；W 后端已拒绝绕过统一审核；目标删除页面直接审批按钮，不再开放手工过账作为审批旁路 |
| B14 作废/执行恢复 | 盘点→作废/重试；`cancelStockCount`，W 重试 UI | POST `O/inventory/counts/{countNo}/cancel`；W 复用 POST `O/inventory/counts/{countNo}/post` | `InventoryController` → `StockViewServiceImpl.cancel/post` → `CountReviewService` | **微服务/审核协作**；远端 `/{no}/cancel`；已批准执行恢复仍用原决定与原键 | M 作废；W 重试。原 `/post` 工作区仅兼容执行恢复，不是再次审核 |
| B15 数量物料实盘录入 | 盘点→数量录入；Web 方法 P | **P** PUT/POST `O/inventory/counts/{countNo}/lines/{itemId}`，最终方法需定稿 | **P** `InventoryController` → `StockViewServiceImpl` → 新增类型化 `InventoryApi` 方法 | **微服务**；已核 PUT `I/counts/{no}/lines/{itemId}` | P 当前宿主仅封装 SN 扫描；数量盘点不能靠重复扫描伪装，须补实际数量与版本校验 |

## 5. 商家出库 交接 安装 上线

| ID/节点与操作 | Web 功能/方法 | Web→Controller API | Controller→本地执行链 | 目标落点/远端 API | 状态与改造 |
|---|---|---|---|---|---|
| C01 可出库设备筛选 | 出库管理→先选商家，再选仓与 SN；当前 `listCabinets` | 当前 GET `O/cabinets?status=IN_STOCK`；目标复用 B01 库存 serials 查询入口 | 当前 `OpsController` → `CabinetService.page` | **微服务**；GET `I/serials`、`I/balances`，本地补型号/设备展示 | M 当前候选读本地；P 改远端可用资格，不只相信前端筛选或本地 qcStatus |
| C02 按商家出库 | 出库管理；`issueDevices` | POST `O/devices/issue` | `DevicePoolController` → `InventoryPort.dispatch` → `RemoteInventoryPort.dispatch`；`InventoryLinks.targets`；`ShipmentService` | **协作，库存远端**；PUT `I/external-targets/{type}/{ref}` → POST `I/outbounds`（ISSUE）→ `/post` | M 有链路；P 去掉本地已有设备前置依赖，稳定操作号。必选商家；站点属于该商家；指定源仓。库存只扣一次 |
| C03 运输记录查询 | 出库/调拨单→物流；`listShipments` | GET `O/inventory/shipments?docType=&docRef=&itemNo=` | `InventoryController` → `ShipmentServiceImpl.list` | **本地交付证据**，关联远端单号 | M 有运输记录；P 商家交付确认、签收附件和差异需扩展，不能称运输信息已等同完整交付验收 |
| C04 接收签收/领取 | 出库管理；`claimDevices` | POST `O/devices/claim` | `DevicePoolController` → `InventoryPort.claim` → `RemoteInventoryPort.claim` | **微服务**；GET `I/dispatch-pool` → POST `I/dispatch-pool/claims` | M 现有 claim 兼作签收；P 明确运输签收和安装占用是否同一凭证。新流程不在未安装时无条件自动安装领取 |
| C05 按商家查询待安装设备 | 待安装设备/安装开单；当前 `listCabinets/issuedSummary` | 当前 GET `O/cabinets?issuedAgentNo=&pool=`；GET `O/inventory/issued-summary` | 当前 `OpsController` → `CabinetService.page`；`DevicePoolController` → `CabinetPoolServiceImpl.summary` | **微服务＋本地安装关联**；GET `I/dispatch-pool?rootTargetRef=商家&status=...` | M 当前本地筛选；**P** GET `O/inventory/installation-candidates?agentNo=&siteNo=&itemType=&page=&size=` → `InventoryController` → 库存候选适配；远端已有多条件查询，客户端待扩展；分页语义需核定 |
| C06 领取并自动关联业务设备 | 安装→选择候选并确认；不增人工注册菜单 | **P** POST `O/devices/installation-claims`，携带 requestKey、dispatchIds、安装业务引用 | **P** `DevicePoolController` → 安装关联 Service → 持久化领取意图 → `InventoryApi.claim` → 按 SN 自动建立/复用业务身份 | **协作**；POST `I/dispatch-pool/claims`；超时查 `I/commands/{requestId}`、`I/dispatch-pool/claims/by-request/{requestId}` | P 自动关联编排；V 查回接口在 ai-shop `92342744b` 已见，但需锁部署版本。已领取的需核对原 claim/businessRef，不能再 claim 一次 |
| C07 绑定点位/批量绑定 | 待安装/部署安装；`bindDevice/bindDevices` | POST `O/devices/{deviceNo}/bind`、`O/devices/bind-batch` | `DevicePoolController` → `CabinetBindServiceImpl.bind/bindBatch` → 可选安装工单 Port | **本地**；消费有效远端交接凭证，不再次扣库存 | M 绑定校验已有；P 接 C06 自动关联后再绑定，来源商家/站点一致，SN/通信号冲突进入异常，不能靠手工创档绕过 |
| C08 安装工单开单/派工 | 部署安装/工单；`createWorkOrder/dispatchWorkOrder/acceptWorkOrder` | POST `O/work-orders`；POST `/{woNo}/dispatch`、`/accept` | `WoExtController` → `WoOpsServiceImpl.create/accept`；派工 `OpsController` → `WoOpsServiceImpl.dispatch` | **本地工单业务**（复用既有 OpsKit 集成）；不生成库存单据 | M；W INSTALL 商家/资格校验已增强；P 关联 dispatchId/claimNo/设备身份，不改变工单引擎 |
| C09 安装执行/现场验收 | 工单→处理/完工/关闭；`processWorkOrder/completeWorkOrder/closeWorkOrder/rejectWorkOrder/reworkWorkOrder` | POST `O/work-orders/{woNo}/handle`、`/complete`、`/close`、`/reject`、`/rework` | `WoExtController` → `WoOpsServiceImpl` 对应方法 | **本地**；现场位置、扫码、照片、装宝和验收，不改仓内数量 | M；P 商家交付确认不足的另外补齐，不把员工签收自动等同商家验收 |
| C10 上线门禁/试借还 | 待上线/设备详情；`goLiveGate/listTrialRents/startTrialRent` | GET `O/devices/{deviceNo}/go-live-gate`；GET/POST `/{deviceNo}/trial-rents` | `DeviceOpsController` → `CabinetLifecycleServiceImpl.goLiveGate` / `TrialRentService` | **本地**；联网、工单、点位、计费、订单和试借还；必要只读核对库存交接状态 | M；P 库存领取成功不代表门禁自动通过 |
| C11 上线营业 | 待上线→上线；`goLive` | POST `O/devices/{deviceNo}/go-live` | `DeviceOpsController` → `CabinetLifecycleServiceImpl.goLive` | **本地**；不调用库存出库 API | M；P 只允许本次安装及身份映射完整后上线，失败保持待上线 |
| C12 出库去向调整 | 出库管理→调整；`redirectIssue` | POST `O/devices/issue-redirect` | `DevicePoolController` → `InventoryPort.redirect` → `RemoteInventoryPort.redirect` | **微服务**；POST `I/dispatch-pool/{id}/redirect`；已领取补偿须受控 release | M；P 不自动释放已安装业务占用。批量 claimNo 释放整单的边界必须先修正 |
| C13 未交付出库撤销 | 出库管理→撤销；原 `returnPoolDevices` 入口需拆语义 | 原 POST `O/devices/pool-return`；**P** 单独区分撤销与实际退仓动作 | `DevicePoolController` → 受控撤销接入 Service；原实现 `RemoteInventoryPort.returnToWarehouse` 不作目标 | **微服务**；POST `I/dispatch-pool/{id}/revoke`，按远端规则冲回原仓 | M 原入口混合 release/revoke；P 仅限可撤销且业务未占用场景。实物退仓走 E03，不可选择别的仓后仅记告警 |

领取规则：同一 SN 全生命周期业务身份稳定；按单台或不可拆组合明确领取边界。`release(claimNo)` 在已核服务版本中释放整单，不能用于未经检查的单台释放。查询可以使用只读缓存，但最终领取必须由远端并发裁决。

## 6. 在线运行 监控 维护

| ID/节点与操作 | Web 功能/方法 | Web→Controller API | Controller→本地执行链 | 落点/库存 API | 状态与改造 |
|---|---|---|---|---|---|
| D01 设备台账/详情 | 台账；`listCabinets/getCabinet/listPowerbanks` | GET `O/cabinets`、`/{cabinetNo}`、`O/powerbanks` | `OpsController` → `CabinetService`；`DeviceController` → `PowerbankService` | **本地**；展示可聚合远端库存引用 | M；目标台账查询保留，不作为收货前手工注册前置，也不作为库存余额来源 |
| D02 实时监控 | 监控；`listCabinetMonitor` | GET `O/cabinet-monitor` | `DeviceController` → `MonitorService` | **本地设备/网关**；无仓储库存写入 | M 有查询链，真实心跳完整性仍需验收 |
| D03 远程指令/回执 | 设备操作/指令记录；`sendCommand/listCommandRecords` | POST `O/cabinets/{cabinetNo}/commands`；GET `O/command-records` | `OpsController` → `CabinetService.sendCommand`；`InventoryController` → `CommandLogService.page` | **本地网关业务**；无库存 API。Controller 名含 Inventory 不代表执行库存动作 | M 入口存在；不能据此宣称厂商真实控制已完成 |
| D04 日志与信号 | 日志；`listDeviceLogs/listSignalCodes` | GET `O/device-logs`、`O/device-signals` | `DeviceController` → `DeviceLogService.page`；`DeviceSignalController` 查询信号定义 | **本地**；无库存 API | M；日志真实采集与空页占位需分别验收 |
| D05 固件升级 | OTA；`list/saveOtaReleases/list/saveOtaRollouts/listOtaTasks` | GET/POST `O/ota-releases`；GET/POST `O/ota-rollouts`；POST `/{rolloutNo}`；GET `/{rolloutNo}/tasks` | `DeviceController` → `OtaService` | **本地设备/网关**；无库存 API | M 路由存在；投放、逐台回执和失败恢复另验，不因升级生成库存单 |
| D06 告警记录/认领/关闭/转工单 | 告警；`listAlarmRecords/ackAlarm/closeAlarm/raiseAlarmWorkOrder` | GET `O/alarms/records`；POST `/{alarmNo}/ack`、`/close`、`/work-order` | `AlarmController` → 告警记录/处置 Service → 工单 Port | **本地运维**；无直接库存写入 | M；配件出库另走 D10。仓外设备故障不记仓内不可用库存 |
| D07 巡检计划 | 巡检；`list/get/save/runInspectionPlan` | GET/POST `O/inspection-plans`；GET/POST `/{planNo}`；POST `/{planNo}/run` | `WoExtController` → `InspectionPlanServiceImpl` → `WoOpsService` | **本地**；无库存 API | M；现场清点差异不得直接生成仓内盘亏 |
| D08 故障与维修 | 维修；`markDeviceFault/repairDevice`；FAULT 工单 | POST `O/devices/{deviceNo}/mark-fault`、`/repair`；工单复用 C08/C09 | `DeviceOpsController` → `CabinetLifecycleServiceImpl.markFault/repair`；工单 `WoOpsServiceImpl` | **本地**；无库存 API | M repair 为状态恢复，不等于完整维修闭环；P 维修明细/费用/验收，候选 API 见既有开发清单，尚未发布 |
| D09 停用与恢复 | 详情→保护/解除；`list/apply/releaseProtection` | GET/POST `O/devices/{deviceNo}/protections`；POST `O/devices/protections/{protectionNo}/release` | `DeviceOpsController` → `ProtectionService` | **本地**；无库存 API | M 保护已有；P 独立停用/恢复优先核可否复用，不把设备解绑当暂停营业 |
| D10 配件领用/退料/报损 | 物料单据；`listMaterialDocs/getMaterialDoc/createMaterialDoc` | GET/POST `O/inventory/material-docs`；GET `/{docNo}` | `InventoryController` → `MaterialServiceImpl.createDoc/docs/doc` → `InventoryApi` | **微服务**；领用/报损 POST `I/outbounds` + `/post`；退料 POST `I/inbounds` + `/post` | M SERVICE；W 部分稳定 requestKey 恢复已补；P 关联维修工单、冻结实际数量和用途 |
| D11 充电宝借还/柜槽变化 | 运营租赁订单查看；用户端扫码借还 | 本节点复用租赁与设备协议；用户借出为 POST `/mp/trade/orders/rent`，不新增运营库存 API | `RentController` → `RentOrderServiceImpl.rent`；归还由既有设备上报链执行 | **本地租赁/设备业务**；不调库存 reserve/出入库来代替用户借还 | M 租赁路径；设备上报协议按厂商适配另列。仓内出库与消费者租赁是两类事务 |
| D12 设备履历 | 详情→履历；`listDeviceMovements` | GET `O/device-movements?itemNo=` | `AssetOpsController` → `MovementRecorder.of` | **本地业务履历＋远端库存单据引用** | M 本地履历；P 联合库存流水查询，不能以一条设备履历代替远端过账凭证 |

## 7. 撤机 退仓 维修 再投放 退出

| ID/节点与操作 | Web 功能/方法 | Web→Controller API | Controller→本地执行链 | 目标落点/远端 API | 状态与改造 |
|---|---|---|---|---|---|
| E01 下线检查/撤机工单 | 撤机与退库/工单；`undeployDevice/createWorkOrder/completeWorkOrder` | POST `O/devices/{deviceNo}/undeploy`；工单 C08/C09，type=REMOVAL | `DeviceOpsController` → `CabinetLifecycleServiceImpl.undeploy`；`WoExtController` → `WoOpsServiceImpl` → 完工事件 | **本地**；只下线/清点/解除现场关联，不增加库存 | M；P 检查未结租赁、安全撤机和保管责任；不能完工即认为回仓 |
| E02 选择留商家/退仓 | 撤机完工去向 | 当前复用 POST `O/work-orders/{woNo}/complete` 的完工数据；P 明确去向字段与凭证 | `WoOpsServiceImpl` → `RemovalCompletedListener` | **本地责任流转**；退仓仅发申请，留商家不生成库存出入库 | M 当前撤机监听可生成回仓调拨；P 区分仓间调拨和仓外设备退仓 |
| E03 退仓申请/运输 | 撤机→退仓申请；拟 `createReturnRequest` | **P** POST `O/inventory/return-requests` | **P** `InventoryController` → 退仓接入 Service，验证可退与冻结业务引用 | **协作**；远端 RETURN 草稿可用 POST `I/inbounds`，创建不加实存 | P 独立退仓申请；不复用“release+revoke”当实际退仓 |
| E04 仓库实收 | 入库单→退仓扫码实收；复用 `createIntake/inboundIntake` 或扩展 E03 | 已有 POST `O/inventory/intakes`、`/{intakeNo}/inbound`；**P** 明确关联原退仓请求和实收 SN | `InventoryController` → 退仓接入适配；当前 `IntakeServiceImpl`/`RemoteInventoryPort.inbound` | **微服务**；POST `I/inbounds`（RETURN）→ `/{no}/post` | M 部分路径；P 实收才加库存，缺件继续业务追查；必须入所选实际仓，不是原仓冲回 |
| E05 退仓业务确认 | 退仓执行状态；自动任务，无独立人工登记 | 系统任务；Web 只查操作状态 | 本地解除关联事务提交 → 持久化确认任务 → `InventoryApi.businessClearance` | **协作**；POST `I/inbounds/{no}/business-clearance` | M 当前紧接远端过账同一调用栈确认；P 必须在本地解除关联提交后发送；解除此冻结不等于质检通过 |
| E06 回仓维修/复检 | 维修工单＋入库质检 | 工单 C08/C09；质检 A08 | 本地维修 Service/`WoOpsServiceImpl`；仓内质检接入 Service | **本地维修＋库存质检微服务** | P 复用原业务设备身份；维修后先判断仓内/仓外位置，不能一律恢复 DEPLOYED |
| E07 返厂/退供应商 | 处置出库；`createDisposal` | POST `O/inventory/disposals`；列表/详情 GET 同集合及 `/{disposalNo}` | `InventoryController` → `DisposalServiceImpl.create/page/get` | **微服务＋审核**；POST `I/outbounds`（RETURN_SUPPLIER）→ `/post`；供应商 external-target | M 退供应商出库已有；P 区分永久采购退货与暂时返修，返修目的/返还关联合同 V，不直接把二者混用 |
| E08 维修返回/再次入库 | 入库单→返修收货 | 复用 A06/A08/A10，P 增加返修来源引用 | 入库接入 Service → `InventoryApi` | **微服务**；入库用途由真实服务合同 V 决定，按实收、待检和放行处理 | P 关联原返修单，避免创建第二个设备身份或误报采购新增资产 |
| E09 再次出库安装 | 出库→待安装→安装→上线 | 复用 C01–C11 | 库存接入 → 本地安装/设备服务 | **协作**；新出库/领取周期，原设备身份复用 | P 全链验收；旧 claim/旧质检凭证不能直接替代新周期交接 |
| E10 仓内报废/报损 | 处置出库 | POST `O/inventory/disposals`，kind=SCRAP | `InventoryController` → `DisposalServiceImpl` → 统一审核/远端执行 | **微服务＋审核**；POST `I/outbounds`（SCRAP、reasonCode）→ `/post` | M 当前创建即本地退役再远端调用；P 改申请/批准/执行分离，最终设备退役投影以执行结果衔接 |
| E11 仓外遗失/资产退出 | 报废与遗失；`confirm/dismissPowerbankLost/retireDevice` | POST `O/powerbanks/{powerbankNo}/confirm-lost`、`/dismiss-lost`；POST `O/devices/{deviceNo}/retire` | `DeviceController` → `PowerbankLossService`；`DeviceOpsController` → `CabinetLifecycleServiceImpl.retire` | **本地＋必要审核**；仓外设备不再扣仓内库存 | M；P 在仓设备退出必须转 E10，不能从本地 retire 旁路减少库存或只改状态不记账 |
| E12 差异追查/结案 | 资产差异；`listAssetDiffs/resolveAssetDiff` | GET `O/asset-diffs`；POST `/{diffNo}/resolve` | `AssetOpsController` → `TransferOpsServiceImpl.diffs/resolveDiff` | **本地责任差异**；若需调整库存，关联远端盘点/纠正单并统一审核 | M 本地备注结案；P 区分仓内账差与仓外责任差，不能一律写本地备注即完成库存纠正 |

## 8. 统一审核 异常恢复 事件与迁移

| ID/功能 | Web/调用者 | API 与执行链 | 目标与状态 |
|---|---|---|---|
| F01 统一审批列表/详情 | `/flow/approvals`；`flow.approvals/approval` | 默认 `/api/opskit/v1` 反代 → POST `/workspaces/{ws}/approvals/query`；GET `/workspaces/{ws}/approvals/{no}` | **OpsKit**；不是 `InventoryController` 代理，也不是本地另建审批引擎。M 已有真实/模拟切换，真实部署另验 |
| F02 审批决定/撤回 | `flow.decide/withdraw` | POST 同工作区 `/approvals/{no}/decide`（taskNo、decision、reason）；`/withdraw` | **OpsKit**；服务端判候选人与申请/审批职责分离，不能只靠按钮隐藏 |
| F03 审批结果回写 | OpsKit 系统回调 | POST `/internal/opskit/webhook` → `OpsKitWebhookController` → `OpsKitWebhookService` → `ApprovalOutcomeHandler` → W `CountReviewService` | 本地签名/去重/版本校验；先记录决定，再以原操作号调用远端。W 盘点已有；采购/报废等需按流程规则适配，不能声称已全部接入 |
| F04 执行结果查询/重试 | **P** 执行异常列表/单据“执行状态” | **P** GET `O/inventory/operations`；POST `/{operationNo}/retry` → `InventoryController` → `InventoryOperationJournal` 及恢复 Service | **本地协调＋远端查回**。W journal 基础已有，通用列表/重试尚未实现。禁止重试直接换键重新建单 |
| F05 远端命令/领取查回 | 系统恢复任务，非浏览器直连 | **P 客户端封装** GET `I/commands/{requestId}`；GET `I/dispatch-pool/claims/by-request/{requestId}` | ai-shop `92342744b` 源码已见；主分支 InventoryApi 未封装，部署版本 V。404 不直接证明原请求绝未执行，按命令协议处理并发/未知状态 |
| F06 事件拉取/投影/确认 | 系统任务 `inventory-event-pull`；可手动触发 | POST `/internal/job/inventory-event-pull/trigger` → Job → `InventoryEventPuller` → GET `I/events?afterSeq=&limit=`、POST `I/event-acks` | M inbox 去重；P FAILED 重试、资源版本防旧事件覆盖、完整库存/交接投影，失败事件不能被当 DONE 跳过 |
| F07 对账 | **P** 库存设置/执行异常→对账报告 | **P** 对账入口路径待与现有报告统一；本地映射/业务关联 vs `I/balances`、`I/serials`、`I/dispatch-pool` | **协作查询**；只报差异及生成处置任务，不自动用本地覆盖远端 |
| F08 存量迁移 | 仓库页；`getInventoryMigrationPlan/runInventoryMigration` | GET/POST `O/inventory/migration` → `InventoryController` → `InventoryMigration` → 远端入库/出库/领取 | M 迁移工具；P 核对期初用途、稳定键、非零数据、在途和未结操作，不把采购入库当全部历史迁移用途 |
| F09 服务状态/默认执行端 | 库存设置；`getInventoryEngine` | GET `O/inventory/engine` → `InventoryController` → `StockViewServiceImpl.engine`；系统参数写入复用系统设置 | M 当前可 LOCAL/SERVICE，默认 LOCAL；**P 库存默认 SERVICE**，取消普通运营随意切账。显示“未配置/不可达/待查回”，不得自动降级本地库存 |

审核和库存服务是两个外部能力：库存服务调用仍必须经 powerbank 后端；统一审核页面目前已有直接访问 OpsKit 反代的路径，不应为了画一条统一箭头而错误声称所有流程页面也经过库存 Controller。

## 9. 关键字段与执行约束

### 9.1 新增或扩展接口的字段

下面是目标契约字段，不修改现有 DTO 后即不视为已支持。完整采购、维修等商业字段继续参考[API 开发清单](运营端开发-API定义与变更清单.md)。

| 动作 | 必须冻结/校验的字段 | 返回与展示 |
|---|---|---|
| 收货/期初 | requestKey、来源类型/来源行/来源唯一号、warehouseNo、itemId/型号、SN 清单、实收数量、证据引用 | operationNo、远端入库单号、执行状态、实收/待检/可用数量；重复来源返回原操作或冲突 |
| 质检 | 远端任务号、SN、模板/内容版本、执行人、原始检测结果、证据引用 | 检测提交状态、库存侧结论、阻塞原因；本地不得直接伪造最终放行 |
| 出库 | requestKey、源仓、商家 agentNo、可选站点 siteNo、接收人、用途、SN/组合明细 | 出库单号、dispatchIds、目标快照、执行状态；商家和仓库范围由后端校验 |
| 安装候选 | agentNo、siteNo、设备类型、查询条件和分页 | SN、itemId、dispatchId、库存侧交接状态、本地安装状态、可选原因；claimNo 只在允许且有关联时展示 |
| 安装领取/自动关联 | requestKey、dispatchIds、持久化安装业务引用、目标点位、expectedVersion（适用时） | operationNo、claimNo、稳定 deviceNo、安装关联状态；不存在本地设备时自动创建，不新增人工注册步骤 |
| 退仓 | 原交接/领取引用、业务冻结证明、实际接收仓、实收 SN、缺件记录、requestKey | 退仓入库单号、库存接收状态、业务解除状态、质检状态分别展示 |
| 审核执行 | 业务单版本/摘要、审批号/实例号、决定与决定人、原请求/步骤键 | 审批状态与执行状态独立；已批准但远端未知显示待查回，不能显示已完成 |

### 9.2 库存与业务状态分别展示

库存状态只表达在仓、可用、待检、不可用、出库及交接；设备状态表达待安装、待上线、营业、故障、停用、在租和退出。仓外设备不计入仓内实存；签收、绑定、上线和租赁不得重复扣仓储库存。自动设备关联只创建本地业务身份，不新增库存余额。

### 9.3 异常处理

1. 调用远端之前提交稳定意图，保留步骤键、请求摘要、目标执行端、源业务引用和原操作人。
2. 业务拒绝与网络未知结果分开；超时不代表未执行，本地事务不能回滚远端已提交动作。
3. 查回原指令，按原键续做；远端成功而本地关联失败时恢复本地关联，不能直接释放已有安装占用。
4. 退仓业务确认在本地事务提交后发送；质检冻结和退仓业务冻结分别解除。
5. 需要审批的补偿、跨人接管等接统一审核；普通原操作重试不再次创建审批或库存单据。
6. 不自动切回 LOCAL。切账必须冻结写入、处理在途任务、核对账实并受控执行。

### 9.4 交付顺序与验收

| 批次 | 改造范围 | 验收要求 |
|---|---|---|
| T1 契约与默认路由 | F05/F09、主数据标识、仓内质检/实收语义 V 项 | 锁定库存服务源码及部署版本；确认能力/错误/幂等/查询合同；默认 SERVICE 且故障不落本地 |
| T2 查询与收货 | A01–A11、B01–B06 | Web 库存查询确实取远端；无需先创本地设备可收货；100 订购、80 实收、70 可用、10 待检、20 未收口径成立 |
| T3 出库与安装 | C01–C13 | 商家必选；候选查远端；原子领取；自动业务身份关联；禁止跨商家和重复安装；单台释放不误释放整批 |
| T4 调拨与盘点 | B07–B14、F01–F06 | 源仓/目标仓分权；在途不丢；审批不能绕过；故障后按原号恢复，失败事件可重试 |
| T5 运行与退出 | D01–D12、E01–E12 | 在线设备只走本地业务；撤机不增库、实收才退仓；返修/复检/再投放沿用原身份；仓内报废有远端单据 |
| T6 切流验收 | F07–F09 | 真实库存服务＋真实审核联调；远端提交后丢响应、宿主重启、并发领取、部分实收、旧事件等场景；对账与迁移演练通过后切流 |

这里列的是调整后的顺序；历史开发计划将补偿/事件补全列为上线后的能力补强，不能据此跳过本清单中的一致性验收。

### 9.5 角色与节点

| 角色 | 操作节点 | 权限边界 |
|---|---|---|
| 采购经办 | A03–A05、供应商退货商业信息 | 可以管理采购来源，不直接确认仓库实收或修改库存余额 |
| 仓管 | A01、A06/A07/A10、B01–B15、C01–C03、E04/E07/E10 | 按授权仓作业；发货判源仓权限，签收判目标仓权限 |
| 授权质检人员 | A08/A09、E06/E08 | 记录检测结果和证据；不能自行绕过冻结或伪造远端放行 |
| 运维/安装人员 | C05–C11、D02–D10、E01–E06 | 按受派工单、商家和站点执行；不能领取其他商家设备或代指定接收人签收 |
| 商家 | 本商家设备/交付查看与确认、报障 | 不能查看其他商家候选、改库存仓或代仓管过账；专门商家确认入口仍需补合同 |
| 审批人/财务 | B13、E07/E10/E11 中按规则需审核的业务 | 通过统一审核决定；权限与申请人分离，不能编辑冻结实盘事实或越过执行结果 |
| 平台管理员/运维管理 | F04–F09 | 恢复与切流受控，管理凭证只在运维流程使用，不作为普通页面权限 |

## 10. 远端 API 到库存服务 Controller 与 Service

下表来自 ai-shop `92342744b` 源码。所有路径均以 `I` 为前缀；同名类属于独立库存服务，不是 powerbank 本地 Service。部署是否提供相同接口须在 T1 再确认。

| API 分组 | 远端 HTTP API | 远端 Controller | 远端领域 Service | 宿主接入情况 |
|---|---|---|---|---|
| 物料 | PUT `/items/by-ref/{externalRef}`；GET `/items` | `ItemController` | `HostCatalogService` | 已封装 upsert；列表待对齐 |
| 仓库 | GET/POST `/warehouses`；PATCH `/{locationId}`；POST `/{locationId}/disable` | `WarehouseController` | `LocationService`、`WarehouseService` | GET/POST 已封装；编辑/停用未封装 |
| 外部目标 | PUT `/external-targets/{type}/{ref}`；GET `/external-targets` | `ExternalTargetController` | `ExternalTargetService` | upsert 已封装；目标商家来自本地授权业务 |
| 入库 | POST `/inbounds`；PATCH `/inbounds/{no}`；GET `/inbounds/{no}`；POST `/inbounds/{no}/post`、`/cancel`、`/business-clearance` | `InboundController` | `HostInboundService`、`DocumentQueryService` | 创建/过账/业务确认已封装；详情/追加/作废待封装 |
| 出库 | POST `/outbounds`；PATCH `/outbounds/{no}`；GET `/outbounds/{no}`；POST `/outbounds/{no}/post`、`/cancel` | `OutboundController` | `HostOutboundService`、`DocumentQueryService` | 创建/过账已封装；详情/追加/作废待封装 |
| 交接候选/在途 | GET `/dispatch-pool`；GET `/in-transit` | `DispatchController` | `DispatchService` | 仅按 SN 候选已封装；商家/状态多条件及远端在途待封装；此在途语义须与仓间在途分清 |
| 领取/查回/释放 | POST `/dispatch-pool/claims`；GET `/dispatch-pool/claims/by-request/{requestId}`；POST `/dispatch-pool/claims/{claimNo}/release` | `DispatchController` | `DispatchService` | claim/release 已封装；按请求查回待封装；release 为整单 |
| 交接调整/撤销 | POST `/dispatch-pool/{dispatchId}/redirect`、`/revoke` | `DispatchController` | `DispatchService` | 已封装，但宿主需修正补偿边界和退仓误用 |
| 盘点 | POST `/counts`；GET `/counts/{no}`；POST `/counts/{no}/scans`；PUT `/counts/{no}/lines/{itemId}`；POST `/counts/{no}/submit`、`/approve`、`/reject`、`/post`、`/cancel` | `CountController` | `HostCountService` | 除数量实盘行外主要动作已封装；approve/reject 由统一审核回执驱动，不开放页面旁路 |
| 余额 | GET `/balances` | `StockController` | `BalanceQueryService` | 已封装；设备库存页尚需改取远端 |
| 序列号/流水 | GET `/serials`；GET `/ledger` | `SerialController` | `SerialService` | 已封装；不能把本地设备号无映射地当库存 SN |
| 期间汇总 | GET `/reports/period-summary` | `ReportController` | `PeriodSummaryService` | 已封装 |
| 命令结果 | GET `/commands/{requestId}` | `CommandController` | `CommandService` | 宿主待封装，用于超时/丢响应查回；写动作通过 CommandService 记录幂等结果 |
| 事件与确认 | GET `/events`；POST `/event-acks` | `EventController` | `EventFeedService` | 已封装；宿主失败重放/版本投影待补 |

仓内质检完整接口、完整采购订单、SN 仓间调拨专用合同及供应商暂时返修仍为 V，不将商城内部 Controller 或数据库直连当作独立服务公开 API。

## 11. 源码与版本依据

### 11.1 powerbank 主分支与工作区

- Web 路由：[库存页](../../ops-web/app/inventory/page.tsx)、[设备页](../../ops-web/app/devices/page.tsx)、[安装等生命周期组件](../../ops-web/components/device/lifecycle-views.tsx)。
- Web HTTP：[device.ts](../../ops-web/lib/api/https/device.ts)、[inventory.ts](../../ops-web/lib/api/https/inventory.ts)、[workorder.ts](../../ops-web/lib/api/https/workorder.ts)、[alarm.ts](../../ops-web/lib/api/https/alarm.ts)。
- 库存 Controller：[InventoryController](../../backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/InventoryController.java)、[AssetOpsController](../../backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/AssetOpsController.java)、[DevicePoolController](../../backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DevicePoolController.java)。
- 本地业务 Controller：[OpsController](../../backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/platform/OpsController.java)、[DeviceController](../../backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DeviceController.java)、[DeviceOpsController](../../backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DeviceOpsController.java)、[WoExtController](../../backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/ops/WoExtController.java)。
- 调用接口：[InventoryPort](../../backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/port/InventoryPort.java)、[InventoryApi](../../backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/remote/InventoryApi.java)、[HttpInventoryApi](../../backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/remote/HttpInventoryApi.java)、[RemoteInventoryPort](../../backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/remote/RemoteInventoryPort.java)、[InventoryEngineRouter](../../backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/port/InventoryEngineRouter.java)。
- 统一审核：[flow client](../../ops-web/lib/flow/client.ts)、[flow HTTP](../../ops-web/lib/flow/http.ts)、[OpsKitWebhookController](../../backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/platform/OpsKitWebhookController.java)、[工作区 CountReviewService](../../backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/CountReviewService.java)。

链接指向当前工作区；M 表示主分支已有同类入口，不代表该链接所有行都已在主分支。统一审批相关 W 增量必须经独立提交与真实联调才能升级状态。

### 11.2 ai-shop 远端合同证据

已查到的独立服务代码位于本地 ai-shop-codex Git 对象 `92342744b`，并非其当前检出目录，也不代表当前部署版本。复核命令为：

```sh
git -C /Users/robin/work/ai/ai-shop-codex show 92342744b:backend/inventory/inventory-svc/src/main/java/ai/neargo/inventory/svc/api/CommandController.java
git -C /Users/robin/work/ai/ai-shop-codex show 92342744b:backend/inventory/inventory-svc/src/main/java/ai/neargo/inventory/svc/api/DispatchController.java
git -C /Users/robin/work/ai/ai-shop-codex show 92342744b:backend/inventory/inventory-svc/src/main/java/ai/neargo/inventory/svc/api/InboundController.java
```

其中 CommandController 提供按 requestId 查回；DispatchController 提供按目标/状态查询及按请求查领取；InboundController 提供草稿、过账、作废、详情和退仓业务确认。未列出的仓内质检完整合同、采购订单、SN 调拨及返修用途须单独确认，不能用旧 44/44 联调记录代替新流程验证。
