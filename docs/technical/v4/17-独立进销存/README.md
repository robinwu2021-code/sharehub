# 基于 ai-shop 的独立进销存改造文档集

> 状态：开发设计草案（未实施） · 2026-10-29
> 已确认边界：仓内库存、进出库、盘点；出库后不跟踪业务库存；通过出库池交接；独立部署并提供 API。其余具体规则为本轮建议设计。

## 1. 结论与质检归属

基于 ai-shop `shop-inventory` 提取独立库存服务。通用库存负责物料、仓库、仓内 SN、质检记录及放行、出入库、盘点、库存流水和出库池；业务系统负责外部对象主数据、设备专项检测执行、安装运营、现场验收和上线门禁。

质检采用分层方案：**仓内质检的任务、模板版本、证据、最终结论和出库拦截归库存；设备专属测试的执行归业务适配器；出库后的现场验收归业务系统。** 实物验收入库即计实存，待检和不合格不计可用。不能把“业务设备可上线”与“仓内允许出库”共用一个状态。

## 2. 阅读与开发分工

| 文档 | 内容 | 主要读者 |
|---|---|---|
| [00-需求边界与决策](./00-需求边界与决策.md) | 确认边界、适用场景、替代方案、功能编号 | 产品、架构、所有开发 |
| [01-服务架构与数据模型](./01-服务架构与数据模型.md) | 部署、模块、表、状态、事务、不变式 | 库存后端、运维 |
| [02-质检方案](./02-质检方案.md) | 归属、模板、检测执行、证据、复检、放行 | 产品、库存、设备后端 |
| [03-API与事件契约](./03-API与事件契约.md) | API、JSON、鉴权、幂等、领取、事件游标 | 所有后端、前端 |
| [04-ai-shop进销存改造](./04-ai-shop进销存改造.md) | 现有代码清单、提取、补缺、商城兼容 | ai-shop 开发 |
| [05-业务系统对接](./05-业务系统对接.md) | powerbank 接入、领取恢复、退仓、商城链路 | 业务后端 |
| [06-前端与权限](./06-前端与权限.md) | 库存作业端、业务选择器、权限与审计 | 前端、权限开发 |
| [07-迁移部署与验收](./07-迁移部署与验收.md) | 数据迁移、灰度、回滚、监控、验收矩阵 | 测试、运维、负责人 |
| [08-开发任务计划](./08-开发任务计划.md) | 文件落点、接口产物、依赖、测试步骤 | 执行开发人员 |

按 00→01→02→03 建立统一理解，再按职责阅读 04/05/06；07 是上线门槛，08 是执行顺序。单份需求的权威落点：边界看 00，质检看 02，接口看 03，禁止在其它文档另起同名状态或字段。

## 3. 证据与限制

本轮静态分析基线：ai-shop 本地 HEAD `df2adc641`，powerbank 本地 HEAD `dddf0ce4`。未运行服务、数据库迁移或测试，文中列出的测试均为待开发验收要求，不是通过记录。执行前重新检查两个仓库 HEAD 和工作区在途改动。

现有代码证据（路径相对各仓库）：

- ai-shop：`backend/shop-inventory/pom.xml`、`config/InventoryDataSourceConfig.java`（Java 相对 `src/main/java/ai/neargo/shop/inventory/`，下同）。
- ai-shop：`service/impl/{StockPostingServiceImpl,TransferServiceImpl,StockCountServiceImpl,OutboundServiceImpl}.java`。
- ai-shop：`api/open/OpenInventoryController.java`、`api/biz/BizStockDocController.java`、`api/ops/OpsInventoryController.java`。
- ai-shop：`backend/shop-app/src/main/java/ai/neargo/shop/invbridge/` 及 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/Inventory{Flow,BizEndpoint,OpenApi}Test.java`。
- powerbank：`backend/sharehub-svc-core/src/main/java/ai/neargo/sharehub/{inv,dev}/` 与 `backend/sharehub-app/src/main/java/ai/neargo/sharehub/portal/core/{InventoryController,AssetOpsController}.java`。
- powerbank：[运营核心流程](../../../requirements/运营核心流程.md)。

本组文件是新增方案，未把旧架构中的服务清单、现有端点、权限目录或实现状态直接改成“已完成”。后续实施任务要求同步这些登记。

## 4. 交付范围

M0–M4：独立服务、仓内 SN、质检、出库池、可靠领取、盘点和 powerbank 端到端；M5：ai-shop 电商库存迁移。最终目标包含两个系统，但商城切流必须独立验证，不作为 powerbank 首次接入的隐式前置。
