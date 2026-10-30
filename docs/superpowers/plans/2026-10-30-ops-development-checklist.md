# 运营端开发清单 Web API 数据库与代码

> 2026-10-30；基于40项DEV任务拆解。本文为开发设计清单，不代表接口已发布、DDL已执行或业务已实现。

**Goal:** 每个DEV任务明确Web落位、API变更、数据库工作包与代码修改范围，按依赖实现并验收。

**Architecture:** 复用现有Web路由、领域服务、库存端口与外部服务协议；新采购及操作恢复按业务边界新增；不重复建设已有支付退款、设备保护、物流及票据模型。

**Tech Stack:** Java21、Spring、MyBatis／MySQL、Next.js、React、Vitest。

**Spec:** [40项开发任务](2026-10-30-ops-matrix-development-backlog.md)；[功能矩阵](../../requirements/运营端功能矩阵-多级菜单与实现状态.md)；[生命周期](../../requirements/设备生命周期-角色菜单功能对照.md)。

## 一 配套清单

- [API定义与变更清单](../../technical/运营端开发-API定义与变更清单.md)：现有接口真实路径、DTO与权限，以及20组拟新增／扩展契约。
- [数据库变更清单](../../technical/运营端开发-数据库变更清单.md)：17个数据库工作包、字段、唯一约束和迁移要求。

## 二 40项任务开发总表

“分别为独立tab”的简写不是实际复合URL；请分别使用对应tab值。API编号N为拟定，E为现有契约复用清单。所有新增页面／接口／表都须先过对应CHK确认。

| 任务 | Web页面／组件落位 | API定义与变更 | 数据库包 |
|---|---|---|---|
| DEV-01 恢复可复现构建与真实服务验证基线 | 无业务页面；构建与验证环境 | 无预设业务API；按CHK结果或复用外部协议；不预设新路径 | 默认不改表；若CHK确认缺口再提迁移 |
| DEV-02 固化角色动作与数据范围并补越权回归 | /employees?tab=roles；全模块按钮／范围 | E：13个既有端点（见API附表）；不预设新路径 | DB-01 |
| DEV-03 收口调拨状态写入并保证库存副作用唯一 | /inventory?tab=transfers | E：7个既有端点（见API附表）；不预设新路径 | DB-10 |
| DEV-04 建立库存跨系统幂等与操作恢复机制 | /inventory?tab=material-docs/disposals/settings；拟增加异常恢复视图 | E：9个既有端点（见API附表）；API-N01、API-N02 | DB-02 |
| DEV-05 完善按商家出库与交付凭证 | /inventory?tab=issued（出库管理） | E：5个既有端点（见API附表）；API-N06、API-N07 | DB-06 |
| DEV-06 统一按商家选择待安装设备及安装API校验 | /devices?tab=installs；/work-orders 的INSTALL表单 | E：27个既有端点（见API附表）；API-N08、API-N09 | 默认不改表；若CHK确认缺口再提迁移 |
| DEV-07 完善安装验收和上线门禁的关联 | /devices?tab=pending-live；安装工单完工 | E：21个既有端点（见API附表）；API-N07、API-N10 | DB-06 |
| DEV-08 补齐采购台账与采购行 | 拟新增采购页签；复用 /inventory?tab=suppliers | E：6个既有端点（见API附表）；API-N03、API-N04 | DB-03 |
| DEV-09 补齐分批收货、采购余额与来源防重 | /inventory?tab=intakes；拟关联采购行 | E：6个既有端点（见API附表）；API-N05 | DB-03、DB-04 |
| DEV-10 补齐缺票期初资产的依据和价值记录 | /inventory?tab=intakes 的OPENING；设备导入 | E：7个既有端点（见API附表）；不预设新路径 | DB-05 |
| DEV-11 统一实物、待检和可用口径及复检凭证 | /inventory?tab=intakes 的质检／放行 | E：7个既有端点（见API附表）；不预设新路径 | DB-07 |
| DEV-12 完善物料主数据、领退料和余额保护 | /inventory?tab=materials/material-docs/material-stock（分别为独立tab） | E：10个既有端点（见API附表）；不预设新路径 | DB-07 |
| DEV-13 闭合盘点、差异复核和库存调整 | /inventory?tab=counts/asset-diffs（分别为独立tab） | E：11个既有端点（见API附表）；不预设新路径 | DB-07 |
| DEV-14 设计并实现现场停用与恢复 | 设备运行页内拟增加停用／恢复；复用现有保护视图 | E：3个既有端点（见API附表）；API-N11、API-N12 | DB-08 |
| DEV-15 拆分维修路径并加入维修验收 | /devices?tab=repairs；维修工单；拟增加返厂跟踪 | E：18个既有端点（见API附表）；API-N13、API-N14 | DB-09 |
| DEV-16 闭合撤机、交接、退库和再次部署 | /devices?tab=removals；/inventory?tab=intakes/transfers | E：14个既有端点（见API附表）；不预设新路径 | DB-06、DB-10 |
| DEV-17 完善采购退货、报废和遗失处理 | /inventory?tab=disposals；/devices?tab=retired | E：4个既有端点（见API附表）；不预设新路径 | DB-10 |
| DEV-18 统一设备履历与历史商家归属 | /devices/detail 履历；/agents?tab=assign | E：6个既有端点（见API附表）；不预设新路径 | DB-10 |
| DEV-19 补齐君盛库存与资产报表 | /reports?tab=inventory；库存余额与流水 | E：4个既有端点（见API附表）；不预设新路径 | DB-17 |
| DEV-20 接通真实设备指令与可查询回执 | /devices?tab=commands/vendors（分别为独立tab） | E：5个既有端点（见API附表）；不预设新路径 | DB-11 |
| DEV-21 实现设备日志读写闭环 | /devices?tab=logs | E：1个既有端点（见API附表）；不预设新路径 | DB-11 |
| DEV-22 接通告警处置与真实通知结果 | /alarms；/alarms?tab=notices；/system?tab=notify-log | E：36个既有端点（见API附表）；不预设新路径 | DB-11 |
| DEV-23 接通投诉退款和转工单 | /orders?tab=complaints；/cs | E：13个既有端点（见API附表）；不预设新路径 | DB-12 |
| DEV-24 实现退款执行与财务后续一致性 | /orders?tab=refunds；财务退款执行区 | E：3个既有端点（见API附表）；API-N15、API-N16 | DB-12 |
| DEV-25 统一商家分润规则、生效与版本 | /agents?tab=commission；/settlement?tab=rules/records/summary | E：9个既有端点（见API附表）；不预设新路径 | DB-13 |
| DEV-26 实现商家绩效并修正经营视图 | /agents?tab=performance/business（分别为独立tab） | E：1个既有端点（见API附表）；不预设新路径 | DB-17 |
| DEV-27 完善结算付款与跨期应付 | /settlement?tab=settlements/withdrawals/payout-accounts；跨期应付 | E：24个既有端点（见API附表）；不预设新路径 | DB-13 |
| DEV-28 完善凭证、调整与对账处理 | /finance?tab=ledger/adjustments/reconcile（分别为独立tab） | E：10个既有端点（见API附表）；不预设新路径 | DB-13 |
| DEV-29 完善租赁票据、结算票据和更正闭环 | /finance?tab=invoices 各票据视图；/users?tab=invoices；目标发票模块 | E：14个既有端点（见API附表）；不预设新路径 | DB-14 |
| DEV-30 界定会员发卡的支付和权益生效流程 | /orders?tab=plans/members（分别为独立tab） | E：11个既有端点（见API附表）；不预设新路径 | DB-15 |
| DEV-31 补应用版本发布动作 | /operation/app-versions | E：4个既有端点（见API附表）；API-N17 | DB-15 |
| DEV-32 完成OpsKit待办审批真实接入与仓管任务 | /flow/*；角色待办入口 | 无预设业务API；按CHK结果或复用外部协议；不预设新路径 | DB-17 |
| DEV-33 建设统一导出任务及权限审计 | 拟新增导出任务视图；现有下载按钮 | 无预设业务API；按CHK结果或复用外部协议；API-N18、API-N19 | DB-16 |
| DEV-34 建设集成状态与受控恢复入口 | 系统管理拟新增集成状态页内视图 | 无预设业务API；按CHK结果或复用外部协议；API-N20 | DB-17 |
| DEV-35 完善商家门户只读经营与资产视图 | 商家门户现有经营／资产／报修／profile | E：5个既有端点（见API附表）；不预设新路径 | DB-17 |
| DEV-36 完善角色工作台及实时运营提醒 | /；角色看板卡片和待办 | E：1个既有端点（见API附表）；不预设新路径 | DB-17 |
| DEV-37 落地新版菜单及全局命名 | 全局导航、面包屑、旧路由重定向 | 无预设业务API；按CHK结果或复用外部协议；不预设新路径 | DB-01 |
| DEV-38 闭合设备全生命周期的分角色UAT | 全生命周期Web端到端，不新增业务页 | 无预设业务API；按CHK结果或复用外部协议；不预设新路径 | DB-17 |
| DEV-39 核对所有报表口径及非库存运营功能 | 各模块已有页面；按CHK核实后补缺口 | 无预设业务API；按CHK结果或复用外部协议；不预设新路径 | DB-17 |
| DEV-40 发布前回归、数据迁移与状态回填 | 发布验收页面，不新增业务页 | 无预设业务API；按CHK结果或复用外部协议；不预设新路径 | DB-17 |

## 三 逐任务代码清单及交付检查

本节直接复用任务表已定位的源码；精确路径列为现有文件，拟新增路径单列。范围说明中的尚未定位类名不视为已存在；开发前CHK必须补齐。Mapper和迁移按最终表设计成组新增，不给未知迁移硬编号。

### DEV-01 恢复可复现构建与真实服务验证基线

**Web：** 无业务页面；构建与验证环境。

**现有代码修改范围：** backend/pom.xml；ops-web/package.json；backend/scripts/inventory-local/README.md。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**必须验收：** JDK21构建通过；定位并修复或重建.next生成类型，保留typecheck前后结果；建立隔离库存服务测试租户，记录LOCAL／SERVICE配置，不接生产账。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-02 固化角色动作与数据范围并补越权回归

**Web：** /employees?tab=roles；全模块按钮／范围。

**现有代码修改范围：** `backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/platform/iam/RolePerms.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DevicePoolController.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/InventoryController.java`；ops-web/lib/permissions.ts；ops-web/lib/perm-map.ts。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev02AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 逐项确定八类角色的查看／执行／复核／导出和仓库／商家范围；服务端拒绝跨商家、跨仓和自审；WAREHOUSE主数据维护与质检独立授权。现有宽通配收敛先给出迁移影响，再实施。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-03 收口调拨状态写入并保证库存副作用唯一

**Web：** /inventory?tab=transfers。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/InventoryTransferServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/TransferOpsServiceImpl.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/TransferOperatorTest.java`；ops-web/components/inventory/transfer.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev03AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** save只编辑合法草稿单头，不接受IN_TRANSIT／DONE状态迁移；专用ship／receive同时完成条件更新、设备与库存动作；空单、并发发货和缺件多件签收回归通过。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-04 建立库存跨系统幂等与操作恢复机制

**Web：** /inventory?tab=material-docs/disposals/settings；拟增加异常恢复视图。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/MaterialServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/DisposalServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/port/InventoryEngineRouter.java`；inv/remote；新增库存操作记录、迁移与恢复服务。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增代码（设计名，尚未创建）：**

- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/InventoryOperationService.java`
- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/InventoryOperationServiceImpl.java`
- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/entity/InvOperation.java`
- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/entity/InvOperationStep.java`
- `backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/InventoryOperationController.java`

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev04AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 远程调用前持久化稳定操作号、请求键及载荷摘要；状态记录不会随失败业务事务丢失。覆盖出库成功入库失败、远端成功响应丢失、本地提交失败和重启续做；同键异请求拒绝；恢复固定原引擎。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-05 完善按商家出库与交付凭证

**Web：** /inventory?tab=issued（出库管理）。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetPoolServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DevicePoolController.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/dto/PoolDtos.java`；ops-web/components/inventory/issued-pool.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/DevicePoolTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev05AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 出库商家必选、目的站点归属一致；设备在仓、未绑定、质检放行；员工签收与商家安装交付分别记证据；当面交付标明方式。重复提交不重复扣库。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-06 统一按商家选择待安装设备及安装API校验

**Web：** /devices?tab=installs；/work-orders 的INSTALL表单。

**现有代码修改范围：** ops-web/app/work-orders/page.tsx；ops-web/components/device/lifecycle-views.tsx；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetBindServiceImpl.java`；`backend/sharehub-svc-ops/src/main/java/ai/neargo/sharehub/wo/ext/service/impl/WoOpsServiceImpl.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/DeviceBindTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev06AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 先选商家，按商家服务端分页筛已出库设备；换商家清空设备站点点位；待签收不可绑定，已绑定待安装单列。覆盖出库页、设备页、通用INSTALL和派生安装；直接API、混商家批次与并发选择必须拒绝非法设备。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-07 完善安装验收和上线门禁的关联

**Web：** /devices?tab=pending-live；安装工单完工。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/port/InstallCompletedListener.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetLifecycleServiceImpl.java`；`backend/sharehub-svc-ops/src/main/java/ai/neargo/sharehub/wo/ext/service/impl/WoOpsServiceImpl.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/DeviceLifecycleTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev07AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 安装完工照片、扫码点位和商家一致；门禁记录关联本次安装／绑定，合同勘测豁免可追溯。完工不等于上线；事件失败可定位并恢复；上线不重复出库，旧安装单不能替代新部署验收。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-08 补齐采购台账与采购行

**Web：** 拟新增采购页签；复用 /inventory?tab=suppliers。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/SupplierServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/InventoryController.java`；新增采购单头／行模型、API、迁移和Web页面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增代码（设计名，尚未创建）：**

- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/dto/PurchaseDtos.java`
- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/PurchaseService.java`
- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/PurchaseServiceImpl.java`
- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/entity/InvPurchase.java`
- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/entity/InvPurchaseLine.java`
- `ops-web/components/inventory/purchases.tsx`

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev08AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 采购日期、供应商发票号、型号、订购数量、单价、税基／VAT、币种、附件和状态结构化；建立订购未收，不增加实物。复用已有供应商，不建设完整采购ERP；采购经办授权和单据修改边界明确。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-09 补齐分批收货、采购余额与来源防重

**Web：** /inventory?tab=intakes；拟关联采购行。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/dto/IntakeDtos.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/entity/InvIntake.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/entity/InvIntakeItem.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/IntakeServiceImpl.java`；ops-web/components/inventory/intakes.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/IntakeReceiptTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev09AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 订100收80：实收80未收20；重复来源收货不增库，后续收20累计100。明确超收、拒收、采购退货后的余额和关闭规则；外部来源键预留，Zoho本期不接。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-10 补齐缺票期初资产的依据和价值记录

**Web：** /inventory?tab=intakes 的OPENING；设备导入。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/IntakeServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/dto/IntakeDtos.java`；ops-web/components/inventory/intakes.tsx；设备导入接口与文件绑定服务。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev10AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 缺票设备可导入，保留来源、数量、可靠价值、币种及佐证；财务核值，仓管核数量；重复导入不建重复资产，OPENING不会再被当成采购到货入账。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-11 统一实物、待检和可用口径及复检凭证

**Web：** /inventory?tab=intakes 的质检／放行。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/IntakeServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/QcServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/port/LocalInventoryPort.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/remote/RemoteInventoryPort.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/IntakeFlowTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev11AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 80实收70合格10待检分别显示；实收后质检放行不重复增加实物；新采购不得无依据免检。退库用本单新凭证，重复消费、并发入库和不合格放行均拒绝。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-12 完善物料主数据、领退料和余额保护

**Web：** /inventory?tab=materials/material-docs/material-stock（分别为独立tab）。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/MaterialServiceImpl.java`；ops-web/components/inventory/material-docs.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/MaterialServiceTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev12AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 数量与SN物料分别验证；同单重复行按合计校验，负数／超库存／错仓序列拒绝；内置机柜宝不走物料单据。有流水锁单位跟踪方式，有库存不得归档；领退料关联工单与来源。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-13 闭合盘点、差异复核和库存调整

**Web：** /inventory?tab=counts/asset-diffs（分别为独立tab）。

**现有代码修改范围：** `backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/InventoryController.java` counts系列；inv/remote；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/TransferOpsServiceImpl.java`；ops-web/components/inventory/stock-counts.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev13AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** SERVICE真实盘点；盘点期间变动策略明确，录入与复核不同人；盘盈盘亏通过调整记流水，原始实盘不可覆盖；运输短少、多件与仓外核查不冒充仓内盘点。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-14 设计并实现现场停用与恢复

**Web：** 设备运行页内拟增加停用／恢复；复用现有保护视图。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/CabinetLifecycleService.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetLifecycleServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/DeviceOpsController.java`；设备运行页。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev14AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 先明确新借、归还、已有租赁和计费行为，再独立实现停用／恢复；保留现场绑定，不用undeploy代替停用。恢复失败须显示原因和责任待办。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-15 拆分维修路径并加入维修验收

**Web：** /devices?tab=repairs；维修工单；拟增加返厂跟踪。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/CabinetStateMachine.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetLifecycleServiceImpl.java`；`backend/sharehub-svc-ops/src/main/java/ai/neargo/sharehub/wo/ext/service/impl/WoOpsServiceImpl.java`；维修工单与库存物料单据。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增代码（设计名，尚未创建）：**

- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/RepairWorkflowService.java`
- `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/RepairWorkflowServiceImpl.java`

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev15AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 分现场、回仓和返厂维修；记录故障、处理人、供应商、保修、配件、工时费用、测试与验收。不能通用FAULT→DEPLOYED；修复后按所在位置复检、恢复或重新投放。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-16 闭合撤机、交接、退库和再次部署

**Web：** /devices?tab=removals；/inventory?tab=intakes/transfers。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/port/RemovalCompletedListener.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/TransferOpsServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/IntakeServiceImpl.java`；ops-web/components/device/lifecycle-views.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev16AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 撤机照片、清点、去向留痕；留商家待安装不虚构仓内动作；退库先发货再实收待检，差异可追责。事件处理失败可恢复；旧出库与签收凭证不能用于下一次交付。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-17 完善采购退货、报废和遗失处理

**Web：** /inventory?tab=disposals；/devices?tab=retired。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/DisposalServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetLifecycleServiceImpl.java`；ops-web/components/inventory/disposals.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/DisposalTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev17AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 区分仓内与仓外处置；供应商、原因、证据、复核、损失和反向单关联；仓外遗失不再次扣仓内库存；退役与库存账、财务价值一致。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-18 统一设备履历与历史商家归属

**Web：** /devices/detail 履历；/agents?tab=assign。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/movement/MovementRecorder.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetBindServiceImpl.java`；商家归属变更服务；ops-web/components/device/movements.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev18AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 采购、质检、交付、安装、维修、迁点、撤机与处置可从单设备串单；日期、前后位置、员工、原因齐全。HotelA→MallB后旧订单／票据／分润仍归A，新业务归B。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-19 补齐君盛库存与资产报表

**Web：** /reports?tab=inventory；库存余额与流水。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/dto/InventoryReportDtos.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/InventoryReportServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/inv/service/impl/StockViewServiceImpl.java`；ops-web/components/report/inventory-report.tsx；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/InventoryReportTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev19AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 逐项覆盖君盛11类统计及6类筛选；采购未收、调拨在途、待检、可用、待安装、在场、维修分列。仓内期初+入−出±调整=期末；价税按币种核算与字段权限显示。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-20 接通真实设备指令与可查询回执

**Web：** /devices?tab=commands/vendors（分别为独立tab）。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/CabinetServiceImpl.java`；`backend/sharehub-svc-gateway/src/main/java/ai/neargo/sharehub/gw/service/impl/VendorServiceImpl.java`；gateway端口及适配；ops-web/components/device/vendors-tab.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev20AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 定义厂商能力并接真实下发，重复请求复用commandId；超时不伪造成功，查询真实执行结果。连接探测真实或明确标不支持，危险命令独立授权。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-21 实现设备日志读写闭环

**Web：** /devices?tab=logs。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/dev/service/impl/DeviceLogServiceImpl.java`；gateway日志写入链路；ops-web/lib/api/https/device.ts。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev21AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 先落指令／上报日志，再提供带时间范围、分页和来源引用的查询；重复事件幂等，未知不当成功，不再固定返回空页。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-22 接通告警处置与真实通知结果

**Web：** /alarms；/alarms?tab=notices；/system?tab=notify-log。

**现有代码修改范围：** `backend/sharehub-svc-ops/src/main/java/ai/neargo/sharehub/alarm/service/impl/AlarmServiceImpl.java`；`backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/platform/notify/service/impl/NotifySendServiceImpl.java`；通知通道与告警判定器。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev22AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 重发保留源记录与幂等；区分真实成功、失败、排队、STUB，不直接写SENT。逾期租赁和重试耗尽告警按明确规则验证；短信沿现有一期mock决定标STUB，不擅自要求采购短信通道。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-23 接通投诉退款和转工单

**Web：** /orders?tab=complaints；/cs。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/trade/order/service/impl/ComplaintServiceImpl.java`；退款／工单Port；ops-web/lib/api/https/order.ts。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev23AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 投诉REFUND处置创建可追踪退款申请，不把记录解决当退款执行；转工单复用source_ref防重，失败保持待处理，不抛通用不支持。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-24 实现退款执行与财务后续一致性

**Web：** /orders?tab=refunds；财务退款执行区。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/trade/order/service/impl/RefundServiceImpl.java`；支付退款端口；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/RefundIdempotencyTest.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/RefundShareReversalTest.java`；`ops-web/components/finance/finance-workspace.tsx`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev24AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 申请、审批、执行、成功／失败分离；重复回调／重试不重复出款；复核分润冲回和贷项的触发时点，执行失败不能留下无解释的财务结果；未授权执行拒绝。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-25 统一商家分润规则、生效与版本

**Web：** /agents?tab=commission；/settlement?tab=rules/records/summary。

**现有代码修改范围：** `backend/sharehub-svc-finance/src/main/java/ai/neargo/sharehub/finance/service/impl/ShareGeneratorImpl.java`；`backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/agent/port/LocalAgentDirectory.java`；AgentCommissionService；规则与分润Web页面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev25AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 区分现有固定额链路和比例来源，确定唯一规则版本／生效时间；固定额和比例真实算数，历史快照不变，试算与生成一致。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-26 实现商家绩效并修正经营视图

**Web：** /agents?tab=performance/business（分别为独立tab）。

**现有代码修改范围：** `backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/agent/ext/service/impl/AgentPerformanceServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/platform/AgentExtController.java`；商家经营／绩效页面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev26AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 跨域统计按全量聚合排名后分页，日期区间和商家范围正确；不再固定空页，经营视图不能把缺失绩效当真实0。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-27 完善结算付款与跨期应付

**Web：** /settlement?tab=settlements/withdrawals/payout-accounts；跨期应付。

**现有代码修改范围：** `backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/finance/FinanceController.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/finance/PayoutTaskController.java`；`ops-web/components/finance/finance-workspace.tsx`；`ops-web/components/finance/payables-panel.tsx`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev27AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 复用已有PayablesPanel与付款任务；确认结算、部分多次付款、外部单号唯一、双人复核、账户变更权限、未结余额可核对。先核实付款登记现有码，再补缺口。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-28 完善凭证、调整与对账处理

**Web：** /finance?tab=ledger/adjustments/reconcile（分别为独立tab）。

**现有代码修改范围：** 财务凭证／调整／对账服务；`ops-web/components/finance/finance-workspace.tsx`；审批适配。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev28AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 手工凭证／调整审批、借贷平衡、跨期冲回与不可覆盖历史；对账差异有来源和处置。网关手续费与到账先核真实数据能力，不伪造清算数据。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-29 完善租赁票据、结算票据和更正闭环

**Web：** /finance?tab=invoices 各票据视图；/users?tab=invoices；目标发票模块。

**现有代码修改范围：** TaxInvoicePanel；`backend/sharehub-svc-finance/src/main/java/ai/neargo/sharehub/finance/service/impl/InvoiceServiceImpl.java`；`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/user/core/service/impl/UserInvoiceServiceImpl.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/InvoiceIssuedLockTest.java`；`backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/B2cTaxInvoiceTest.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev29AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 复用TaxInvoicePanel与票据API；开票关联订单、金额税率和主体快照，已开票不可修改；失败重试、贷项关联原票、PDF批量下载及VAT台账验收。不得重复新建已有票据模块。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-30 界定会员发卡的支付和权益生效流程

**Web：** /orders?tab=plans/members（分别为独立tab）。

**现有代码修改范围：** `backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/user/member/service/impl/MemberServiceImpl.java`；`backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/UserOpsController.java`；ops-web/components/user/membership.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev30AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 先确认收费／赠送、支付结果和权益生效规则；未接通前Web明确禁用发卡；接通后幂等发卡、失败不生效，实例查询与权益一致。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-31 补应用版本发布动作

**Web：** /operation/app-versions。

**现有代码修改范围：** ops-web/lib/backend-ready.ts；应用版本服务／Controller；ops-web/app/operation/app-versions/page.tsx。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev31AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 草稿、发布、生效、强更与撤回边界明确，服务端提供受控发布；PARTIAL_READY只在真实链路验证后置true，C端只取已发布版本。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-32 完成OpsKit待办审批真实接入与仓管任务

**Web：** /flow/*；角色待办入口。

**现有代码修改范围：** ops-web/lib/flow；ops-web/app/flow；流程回调适配；`backend/sharehub-svc-platform/src/main/java/ai/neargo/sharehub/platform/iam/RolePerms.java`。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev32AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 真实接入、业务回写、领取超时和幂等；仓管能看到授权收发／盘点任务；流程任务与领域工单来源清楚，未接通明确不可用；禁止申请人自审。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-33 建设统一导出任务及权限审计

**Web：** 拟新增导出任务视图；现有下载按钮。

**现有代码修改范围：** ops-web/lib/api/download.ts；报表／票据导出服务；拟新增导出任务模型与界面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增代码（设计名，尚未创建）：**

- `ops-web/components/report/export-tasks.tsx`

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev33AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 先复用既有导出，补异步进度、失败重试、限时下载和审计；查询范围沿用，只有读权限不能越权导出敏感字段；Excel／PDF实际打开验收。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-34 建设集成状态与受控恢复入口

**Web：** 系统管理拟新增集成状态页内视图。

**现有代码修改范围：** 集成适配及错误记录；系统管理页面；库存恢复服务。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev34AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 支付、库存、设备、通知、OpsKit的错误及处理状态可追溯，密钥脱敏；恢复调用原业务幂等服务，不能新增绕过领域权限的通用重放。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-35 完善商家门户只读经营与资产视图

**Web：** 商家门户现有经营／资产／报修／profile。

**现有代码修改范围：** 商家门户NAV／profile；商家专用Controller；复用资产／订单／财务页面。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev35AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 本商家数据范围、收益、结算、付款记录、票据、待安装／已安装设备和报修；一期不开放商家申请提现或后台经营配置；深链和API不能切换其他agentNo。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-36 完善角色工作台及实时运营提醒

**Web：** /；角色看板卡片和待办。

**现有代码修改范围：** 经营看板服务；ops-web工作台；合同提醒规则。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**拟新增／归并回归：** `backend/sharehub-app/src/test/java/ai/neargo/sharehub/scenario/Dev36AcceptanceTest.java`；若现有同域测试已能覆盖则扩展原文件，避免镜像实现的重复测试。

**必须验收：** 八角色卡片按职责和范围展示；仓管收发待办、运维故障、客服在租异常、财务待付；支付状态与归还分开，卡片能下钻一致口径；合同提醒不硬编码为唯一期限。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-37 落地新版菜单及全局命名

**Web：** 全局导航、面包屑、旧路由重定向。

**现有代码修改范围：** ops-web/lib/nav.ts；`ops-web/lib/i18n/nav-labels.ts`；nav-seed.test.ts；Flyway新迁移；路由重定向。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**必须验收：** 按新版模块分组实施菜单迁移与默认页；V211出库管理在静态NAV与数据库一致；保留旧深链跳转、三语和分期接入提示，不能改历史迁移。语义相同复用页内视图。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-38 闭合设备全生命周期的分角色UAT

**Web：** 全生命周期Web端到端，不新增业务页。

**现有代码修改范围：** 设备生命周期-角色菜单功能对照.md；backend/scripts/inventory-local/e2e.py；后端scenario与Web端到端测试。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**必须验收：** D01—D25逐阶段执行，含LOCAL／SERVICE差异及充电宝／物料；八角色正反权限、100/80/70案例、跨商家安装、返修复检再投放、并发和超时恢复；保留数据、截图／日志及对账结果。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-39 核对所有报表口径及非库存运营功能

**Web：** 各模块已有页面；按CHK核实后补缺口。

**现有代码修改范围：** 报表、商家／网点、销售、用户等各模块现有实现；矩阵中的逐项核查记录。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**必须验收：** 依据各模块核查任务补齐已确认缺口；报表与订单／支付／分润／票据／资产来源一致。统一筛选、历史时点和角色范围，不从缺少菜单推断接口缺失。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

### DEV-40 发布前回归、数据迁移与状态回填

**Web：** 发布验收页面，不新增业务页。

**现有代码修改范围：** 新版矩阵；生命周期表；Flyway；部署与恢复脚本；验收记录。路径简称须先在仓库定位；新增文件和表须在该任务接口设计时明确，不复用历史迁移号。

**必须验收：** 备份恢复演练、迁移核对、角色菜单与数据范围、真实依赖及失败恢复全部留证；阻断资金重复、库存数量错误和历史归属覆盖；只对具备证据的范围回填已验证，预留项不算失败。

- [ ] 确认上述现有代码和契约可复用范围，评审必要新增DTO／表及兼容方式。
- [ ] 完成Web状态、查询键／分页／错误展示、权限与API类型同步；不得只接mock。
- [ ] 完成服务端条件校验、幂等、事务／远程恢复和数据库约束；不以UI隐藏替代授权。
- [ ] 执行对应回归及真实依赖测试，保存证据并回填DEV、功能矩阵和生命周期状态。

## 四 交付一致性检查

- 每个新增字段同时核对DDL、Entity、Mapper、DTO、Web类型、http与mock适配、表单和报表。
- 每个新增动作同时核对权限真源、RolePerms、UI映射、服务端鉴权、数据范围与审计。
- 每个菜单改动同步NAV、翻译、Flyway与菜单重放测试；每个路由迁移保留深链重定向。
- 现有contract.json是静态快照，编码后重新提取并做漂移检查；组合权限和方法内用途判权不能丢失。
- DB-02/04/06与支付退款等幂等设计必须明确同键同请求、同键不同请求、远端已成功但本地未知三种结果。
- 本清单新增提案不等于新增40个页面或17张表；17是数据库工作包，其中多个明确要求复用／核查。

## 五 验证与实施范围

本轮仅整理清单。已检查40项DEV均有Web／API／DB／代码范围映射，拟新增代码路径与现存文件不冲突，现有API只引用contract.json中实际存在的端点。现有DTO必填校验、完整schema与外部协议仍须按CHK核实；未执行本清单DDL或新API开发。

## 实际执行顺序

以[数据库先行与模块交付顺序](2026-10-30-ops-execution-order.md)为准：数据库兼容扩展先完成，再按模块修改持久层、业务、API和Web；回填与强约束在代码切换后收口。原DEV任务依赖保持有效。
