# ai-shop 进销存额外能力与提取改造

> 状态：开发设计草案（未实施） · 2026-10-29
> 已确认边界：仓内库存、进出库、盘点；出库后不跟踪业务库存；通过出库池交接；独立部署并提供 API。其余具体规则为本轮建议设计。

## 1. 现有事实与复用范围

基线见 README。已有：独立数据源、Flyway、余额/流水同事务、出入库单、数量预留、在途库位、数量盘点、供应商、报表与 Outbox。缺失：独立可执行完整库存服务、通用认证、仓内 SN、质检任务、外部接收对象、逐件出库池和领取。

现有开放 API 主要是读余额、读流水和数量同步，不能把“已有 openapi profile”当成完整命令接入。现有运营端只读治理为主，制单作业在商家端。物料 BARCODE 映射是找物料，不是每件 SN 身份；`trackBatch` 留位不是序列号能力。

## 2. 文件改造清单

以下旧 Java 路径前缀 `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/`；新目标前缀 `backend/inventory/`。不直接删除旧目录，先提取再按切换计划收口。

| 旧文件/目录 | 改造 |
|---|---|
| `pom.xml` | 去除 shop-base/shop-base-auth/shop-store-mybatis 的商城耦合，公共依赖从已核对 neargo 构件获取 |
| `config/InventoryDataSourceConfig.java` | 独立启动只需库存数据源；保留迁移先于 Mapper 装配、表归属检查 |
| `service/impl/StockPostingServiceImpl.java` | 提取 PostingService；加入冻结检查、可用/预留分类扣减、SN 与单据原子过账 |
| `service/impl/InboundServiceImpl.java` | SN 实收、库存周期、初检任务、RETURN 关联和池终结 |
| `service/impl/OutboundServiceImpl.java` | ISSUE、外部对象、出库池、SN 校验；分用途约束纠正，不能通用 void 反冲领用 |
| `service/impl/StockCountServiceImpl.java` | 锁范围、SN 快照/扫描、差异复核、终态校验 |
| `service/impl/TransferServiceImpl.java` | 保留仓间能力，补并发幂等及独立命令；不把站点当库存仓 |
| `service/impl/ReservationServiceImpl.java` | 保留 quantity 销售预留；与盘点冻结、扣减规则统一 |
| `service/impl/InventoryAclServiceImpl.java` | 拆通用外部映射与商城 SKU/门店适配，商城部分留 shop-app/invbridge |
| `api/{biz,ops,open}/` | 通用资源 API 进入 inventory-server，商城权限/BizContext 适配留宿主 |
| `job/` 与 EventSink | 自带已注册的任务与投递，不依赖宿主是否开启 worker |
| `src/main/resources/db/inventory/` | 基线/增量迁移在服务自有历史中执行，增量号执行前查最新 |

新增核心服务：SerialStockService、DispatchPoolService、QcService、ExternalTargetService、CommandService；见 08 的接口与落点。

## 3. 必须修复/验证的风险

| 风险 | 当前静态证据 | 改造要求 |
|---|---|---|
| 调拨并发重复执行 | ship/receive 先查状态后生成新单，无调拨级 CAS | 单头状态条件更新/锁＋请求幂等＋业务动作唯一约束 |
| 作废盘点仍可能 post | post 仅对 POSTED 早退，未限定 COUNTING | 新状态机逐动作前置，CANCELLED/VOIDED 禁止过账 |
| 盘点期间正常移动误计 | diff=counted-book，快照不自动消除期间出入库 | 首版冻结范围，清理活跃预留后开盘 |
| 普通出库侵占预留 | applyDelta 只要求 onHand+delta>=0 | 自由库存扣减与预留消费分开 |
| OpenAPI 同步幂等不足 | requestId 仅写 operator，未持久化请求结果 | inv_command 及同事务响应 |
| 新余额行并发插入 | ensureBalanceRow 先查再插 | insert-if-absent/唯一冲突处理后重读，不吞其它 SQL 错误 |

这些是待实现与运行验证的风险清单，不声称已复现或修好。

## 4. 额外功能分类

### 首次通用服务必做

- 自主启动、完整 API、独立鉴权/作用域、命令幂等、部署健康检查。
- quantity 与 SERIAL 并存；仓内周期、SN 单据明细与余额对账。
- 质检模板/任务/检测结果/放行、外部对象同步。
- ISSUE 出库池、查询、原子领取、补偿、退仓终结。
- 仓内盘点冻结/复核、纠正、审计、事件可靠拉取。

### powerbank 宿主必做

- 型号/SN/站点/人员映射，检测执行器，业务 BFF，安装领取与回仓。
- 旧库存和仓内质检写权退出，业务状态词表改造，历史与运行门禁隔离。

### 商城迁移必做

- 预留/提交/释放及退货网络协议、物料门店投影、库存权威切换。
- 现有商城报表保留 SALE/RETURN/TRANSFER 等语义，新增 ISSUE 不进入销量。
- 已有商家端出入库与盘点经 BFF 调新服务，运营只读策略仍由商城权限决定。

### 后续扩展

不停仓盘点、数量批次质检、复杂抽检、采购审批应付、跨所有权划转、数量型交接池。不得用“后续扩展”替代现有 ai-shop 已有数量调拨/预留能力的迁移验收。

## 5. 防回归约束

旧 `SALE` 仍由订单预留提交生成，不能通过新增通用创建单接口任意制造销售流水。普通 SKU 不强制 SN、不强制检测。商城已有采购成本、供应商、库存月报和 CSV 编码保持兼容。旧 HTTP 响应在宿主 BFF 转换，独立服务不被旧端点格式绑死。
