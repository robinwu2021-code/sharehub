# TDD — 业务层分层设计 · 各层类与方法方案

> 状态：设计（2026-07-12）· 承 [TDD-backend-layered-plan](./TDD-backend-layered-plan.md)，落到**类与方法签名**。
> 范围：**业务层**（Entity/Mapper/Service/Controller/DTO）。`SecurityUtils`/数据权限拦截器/审计切面由**权限对话**提供，本文只**引用**（`SecurityUtils.currentUserNo()/agentNo()/dataScope()`），业务不设计鉴权。
> 依据：[api/README](../api/README.md) · [ddl/](./ddl/README.md) · [系统领域模型](./系统领域模型.md) · [功能权限清单](../requirements/功能权限清单.md)。

---

## 一、分层约定与基类

### 1.1 包结构（每域）
```
<domain>/
  entity/      MyBatis-Plus 实体（镜像 ddl，@TableName/@Version/@TableLogic）
  mapper/      XxxMapper extends BaseMapper<Xxx>（+ 少量自定义查询）
  service/     XxxService（接口，业务方法签名）
  service/impl/ XxxServiceImpl（业务规则实现；调 SecurityUtils 取上下文；发领域事件）
  controller/  XxxController（薄：路由 + @PreAuthorize + @Valid + 调 Service）
  dto/         XxxQuery(查询) / XxxVO(出参) / XxxAddReq·XxxUpdReq(入参)
```

### 1.2 基类/通用件
```java
// 通用 CRUD 服务基类：分页/详情/增改删 + 实体↔VO + 业务键生成，复用消灭样板
abstract class AbstractCrudService<E, VO, Q extends PageQuery> {
  protected BaseMapper<E> mapper;
  PageData<VO> page(Q q);                 // 组 wrapper + selectPage + toVO
  VO get(String bizNo);                   // by <x>_no
  VO create(Object addReq);               // IdGenerator 生 <x>_no + insert
  VO update(String bizNo, Object updReq); // selectByNo→改→updateById（乐观锁）
  void remove(String bizNo);              // 逻辑删
  protected abstract VO toVO(E e);
  protected abstract E toEntity(Object req);
  protected abstract String prefix();     // 业务键前缀，如 "ST"
}

// 领域事件（进程内 Logging，可换 Kafka）
interface DomainEventPublisher { void publish(DomainEvent e); }

// 业务键生成（权限对话外，属通用支撑；本文引用）
class IdGenerator { static String next(String prefix); }  // 如 ST + ULID

// 分页查询基类
class PageQuery { Integer page; Integer size; String keyword; }
```

### 1.3 命名规范
| 层 | 规范 | 例 |
|----|------|----|
| 实体 | `<表名驼峰>` | `LocSite` / `OrdRent` |
| Mapper | `<实体>Mapper` | `SiteMapper` |
| Service | `<聚合>Service` + `Impl` | `SiteService` |
| Controller | `<域>Controller`（按 api 路径分）| `OpsController` |
| DTO | `<实体>{Query,VO,AddReq,UpdReq}` | `SiteQuery/SiteVO` |

### 1.4 权限/业务分离（贯穿）
- Controller 每写接口挂 `@PreAuthorize("@perm.can('码')")`；**Service 内 0 处鉴权**。
- Service 仅 `SecurityUtils.currentUserNo()/agentNo()/tenantId()` 取上下文用于**审计/归属赋值**；数据过滤由 `DataScopeInterceptor` 横切（Service 不写 `where agent_no`）。
- 写操作方法挂 `@OperateLog`（切面落审计）。

---

## 二、各域逐层设计

> 每域给：实体、Mapper 自定义、**Service 接口方法（业务重点）**、Controller 端点→Service→权限码、DTO。CRUD 基础方法沿用基类不重列。

### 2.1 loc 场所（Venue→Site→Point→Contract）
**实体**：`LocVenue` `LocSite` `LocLocation` `LocContract`（已有）。
**Mapper**：`SiteMapper`(+`countPointsBySite`,`countCabinetsBySite`) `LocationMapper` `VenueMapper` `ContractMapper`。
**Service**：
```java
interface SiteService {                    // extends AbstractCrudService<LocSite,SiteVO,SiteQuery>
  PageData<SiteVO> page(SiteQuery q);
  SiteVO get(String siteNo);
  SiteVO create(SiteAddReq r);             // 校验 venue 存在；agent_no 归属；生成 ST 键
  SiteVO update(String siteNo, SiteUpdReq r);
  void setStatus(String siteNo, String status);   // 启停；停用→其点位不接单
  void assignAgent(String siteNo, String agentNo);// 站点归属代理（驱动分润/数据范围）
}
interface LocationService { CRUD + bindCabinet(locationNo, cabinetNo); }
interface VenueService { CRUD; }
interface ContractService { CRUD; List<ContractVO> byVenue(venueNo); } // 分成率驱动分润
```
**Controller**：`OpsController`（场所段）
| 端点 | Service | 权限码 |
|------|---------|--------|
| GET `/api/ops/sites` | SiteService.page | 认证 |
| POST `/api/ops/sites` | create | `location:poi:create` |
| PUT `/api/ops/sites/{no}` | update | `location:poi:update` |
| GET `/api/ops/locations`·`/venues`·`/contracts` | 各 page | 认证 |
**DTO**：`SiteQuery/SiteVO/SiteAddReq/SiteUpdReq` 等。

### 2.2 agt 代理商
**实体**：`AgtAgent` `AgtAccount`。**Mapper**：2×BaseMapper。
**Service**：
```java
interface AgentService {
  PageData<AgentVO> page(AgentQuery q);
  AgentVO create/update(...);
  void setStatus(agentNo, status);                 // 启停
  void configShareRate(agentNo, BigDecimal rate);  // 默认分润比例
  AgentPerfVO performance(agentNo);                 // 片区 GMV/设备/在线率（读聚合）
}
interface AgentAccountService {
  AccountVO open(agentNo, username);   // 开代理登录账号（realm=AGENT，绑 agent_no）
  void disable(accountNo);
}
```
**Controller**：`AgentController`：POST save `@perm.can('agent:agent:update')`；账号 `agent:account:manage`。

### 2.3 iam 员工/角色/权限
**实体**：`IamEmployee` `IamDept` `IamRole` `IamRolePermission` `IamDataScope` `AuditLog`。
**Service**：
```java
interface EmployeeService { CRUD; void assignRoles(employeeNo, List<roleNo>); }
interface RoleService {
  List<RoleVO> list();
  RoleVO create/update(...);                 // 内置角色只读
  void assignPerms(roleNo, List<permCode>);  // 角色→权限码（写 iam_role_permission）
  void setDataScope(roleNo, DataScopeReq);   // 数据范围
}
interface PermQueryService {                  // 供【权限对话】的 PermCache 反查
  Set<String> permsOfRole(role);              // 展开通配
  DataScope dataScopeOf(subjectType, subjectNo);
}
interface AuditService { PageData<AuditVO> page(AuditQuery q); }  // 只读；写由切面
```
**Controller**：`PlatformController`：员工/角色 CRUD `org:employee:*`/`org:role:*`；审计只读 `org:audit:read`。

### 2.4 dev 设备
**实体**：`DevCabinet` `DevSlot` `DevPowerbank` `DevShadow` `DevOtaRelease/Rollout/Task` `DevAlert`。
**Service**：
```java
interface CabinetService {
  PageData<CabinetVO> page(CabinetQuery q);
  CabinetDetailVO detail(cabinetNo);          // 含仓位网格（slots）
  CabinetVO create/update(...); void retire(cabinetNo);
  ImportResult importBatch(List<CabinetAddReq>);  // 导入校验
  void assignToAgent(cabinetNo, agentNo);     // 平台侧归属分配
}
interface CommandService {                     // 远程指令（编排，转 access-gateway）
  CommandAck send(cabinetNo, CommandType type, Map params);  // commandId 幂等；记 gw_command_log
  List<CommandVO> batch(List<cabinetNo>, type);
}
interface PowerbankService { page; void scrap(no); }
interface OtaService { publish(req); rollout(releaseNo, scope, forced); List<TaskVO> track(rolloutNo); rollback(rolloutNo); }
interface AlertService { PageData<AlertVO> page; void ack/resolve(alertNo); }   // 联动工单
```
**Controller**：`OpsController`（设备段）：指令 `device:command:send`，OTA `device:ota:*`，归属 `device:cabinet:assign`。

### 2.5 gw 网关（模块内，真链路属 access-gateway 进程）
**实体**：`GwVendor` `GwVendorConfig`。
**Service**：`VendorService { List<VendorVO> list(); VendorVO saveConfig(vendorCode, VendorConfigReq); }`（密钥 KMS 占位）。
**Controller**：`VendorController`：saveConfig `device:vendor:config`。

### 2.6 trade 订单/计费/支付
**实体**：`OrdRent` `OrdEventLog` `PricePlan` `PriceRule` `PayOrder` `PayAuth` `PayRefund`。
**Service（业务核心）**：
```java
interface OrderService {
  PageData<OrderVO> page(OrderQuery q);
  OrderDetailVO detail(orderNo);
  // 借出编排：校验可用→创单(CREATED)→PaymentPort.preAuth→写 Outbox 弹出指令→DISPENSING
  OrderVO rent(RentReq r);
  void onRentConfirmed(DeviceEvent e);   // 事件驱动：→IN_USE，开始计费
  void onReturned(DeviceEvent e);        // →停计费→BillingService.quote→capture→记账→分润→SETTLED
  void intervene(orderNo, InterveneReq); // 强制归还/免单/补偿（高权限）
}
interface BillingService { Money quote(String planNo, int usedMin, BillingCtx ctx); } // 纯函数：免费时长/累进/封顶/买断
interface PricePlanService { CRUD; PricePlan resolve(siteNo, sceneType); } // 差异化取价
interface PaymentPort {                  // 抽象；StubPaymentPort(MVP) / NearpayAdapter(延后)
  AuthResult preAuth(AuthReq); CaptureResult capture(authNo, Money); void release(authNo);
  RefundResult refund(RefundReq); PayQuery query(ref);
}
interface RefundService { RefundVO apply(orderNo, RefundReq); void audit(refundNo, boolean approve); }
```
**Controller**：`TradeController`：干预 `order:intervene:execute`，退款申请 `order:refund:apply`/审核 `order:refund:audit`，计费模板 `pricing:*`。

### 2.7 finance 账务/分润/结算
**实体**：`AcctAccount` `AcctLedger` `ShareRule` `ShareRecord` `StlSettlement` `StlWithdrawal`。
**Service**：
```java
interface LedgerService { void post(voucherNo, List<Entry> entries); } // 复式：借贷成对、先记账
interface ShareService {
  List<ShareRecordVO> computeAndRecord(orderNo);  // 按合同(场地方)+规则(代理)拆分→share_record
}
interface ShareRuleService { CRUD; }
interface SettlementService { List<SettlementVO> generate(period); void confirm(settleNo); }
interface WithdrawalService { WithdrawalVO apply(payeeReq); void audit(withdrawNo, boolean approve); } // 通过→经 nearpay 打款
interface ReconService { List<ReconDiffVO> run(date); void handle(diffNo); }  // 三方对账
```
**Controller**：`Trade/FinanceController`：分润规则 `finance:share_rule:*`，结算/提现审核 `finance:withdrawal:audit`，对账 `finance:recon:*`。

### 2.8 user C端用户
**实体**：`UsrUser` `UsrIdentity` `UsrCredit` `UsrWallet` `UsrWalletTxn` `CouponTpl` `UsrCoupon` `UsrMembership`。
**Service**：
```java
interface CUserService { PageData<CUserVO> page(q); CUserVO get(cUserNo); }
interface RiskService { void setBlacklist(cUserNo, boolean, reason); }   // 拉黑
interface WalletService { WalletVO get(cUserNo); void txn(...); }
interface CouponService { CRUD tpl; void issue(tplNo, cUserNo); RedeemResult redeem(couponNo, orderNo); }
```
**Controller**：`UserController`（运营端只读+风控）：拉黑 `user:risk:update`；券 `marketing:coupon:*`。（C端 `/mp/**` 属主鉴权归 Phase 2。）

### 2.9 wo 工单
**实体**：`WoOrder` `WoDispatch` `WoSla` `WoHandle` `WoInspectionPlan`。
**Service**：
```java
interface WorkOrderService {
  PageData<WorkOrderVO> page(q); List<WorkOrderVO> board(q);   // 列表 + 看板
  WorkOrderVO create(WoAddReq r);        // 手动/告警自动
  void dispatch(woNo, assigneeId, strategy);  // 指定/就近/负载
  void accept/process/done(woNo, HandleReq);  // 状态机
  void audit(woNo);                       // 验收关单
}
interface InspectionService { CRUD 计划; void generateFromPlan(planNo); }
```
**Controller**：`OpsController`（工单段）：派单 `workorder:wo:dispatch`，处理 `workorder:wo:process`。

### 2.10 ad / platform（P2/支撑）
- `AdSlotService`/`CampaignService`/`CreativeService`/`PlacementService`（P2 建位）。
- `NotifyTemplateService`(CRUD 多语)、`DictService`、`RegionService`。
**Controller**：`SystemController`：通知模板 `system:notify_template:*`，字典 `system:dict:*`。

---

## 三、Controller ↔ Service ↔ 权限码（总映射，节选）
| 端点 | Controller.方法 | Service.方法 | 权限码 |
|------|----------------|--------------|--------|
| POST /api/ops/cabinets/{no}/commands | OpsController.sendCommand | CommandService.send | device:command:send |
| POST /api/ops/work-orders/{no}/dispatch | OpsController.dispatch | WorkOrderService.dispatch | workorder:wo:dispatch |
| POST /api/trade/orders/{no}/intervene | TradeController.intervene | OrderService.intervene | order:intervene:execute |
| POST /api/trade/refunds/{no}/audit | TradeController.auditRefund | RefundService.audit | order:refund:audit |
| POST /api/trade/withdrawals/{no}/audit | FinanceController.auditWithdrawal | WithdrawalService.audit | finance:withdrawal:audit |
| POST /internal/user/credit/blacklist | UserController.blacklist | RiskService.setBlacklist | user:risk:update |
| POST /internal/gw/vendors/{code}/config | VendorController.saveConfig | VendorService.saveConfig | device:vendor:config |
| POST /api/agent/agents | AgentController.save | AgentService.create/update | agent:agent:update |
> 完整表随各域实现补全；权限码常量集中 `powerbank-common-api`（前后端同源）。

## 四、开发顺序与产出（DoD 见 plan §五）
1. **loc/agt/iam** 打样（分层 + 数据范围 + 审计 + 联调），沉淀基类与模板。
2. **dev/gw/trade/finance/user/wo/ad** 复制样板。
3. 每域交付：entity+mapper+service(接口+impl)+controller(薄+@PreAuthorize)+dto+种子迁移 DB+单测(业务规则)+切片测(鉴权矩阵)+ops-web 联调。

## 五、已定（2026-07-12 用户拍板）
1. **VO 尽量复用现 `Dto` records**（合理则复用）：Dto record 不可变、字段与 ops-web `types.ts` 一致 → 作**出参 VO 直接复用**合理；**入参**同样收 Dto（MVP 字段一致，无需单独 AddReq/UpdReq）。仅当出入参字段显著分叉时才拆独立 dto。
2. **状态机独立组件**：订单/工单用独立 `OrderStateMachine`/`WoStateMachine`（`transition(from, event)→to`，非法迁移拒），Service 调用，不散在 Service 内。
3. **ORM = MyBatis-Plus**（BaseMapper + LambdaQueryWrapper + 分页/乐观锁/逻辑删；实体 `@TableName`）。
4. **执行顺序**：先 **运营端(应用端)** 全域落地（entity/mapper/service/controller + DB），再按 **C 端 `/mp` API** 同法实现。
5. **全自动执行**：按本设计逐域实现，构建+联调验证，不再逐步询问。

> 落地约定：出参/入参复用 `Dto`；`AbstractCrudService` 先轻量泛型 `<E,VO,Q>`；app 落库表**去规范化贴合 Dto**（同 loc），`ddl/` 规范化 DDL 为目标模型（差异已知，随迁移收敛）。
