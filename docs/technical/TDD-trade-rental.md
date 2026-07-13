# TDD — 租借交易闭环（trade-rental）

状态：草稿 / 待确认
关联需求：[PRD-trade-rental.md](../requirements/PRD-trade-rental.md)
关联：[architecture.md §7](./architecture.md) · [db-design.md §5](./db-design.md) · [api/README §5](../api/README.md) · [TDD-access-gateway](./TDD-access-gateway.md)
创建日期：2026-07-11

---

## 1. 需求摘要

租借订单生命周期编排：状态机（CREATED→DISPENSING→IN_USE→RETURNED→SETTLED）+ 计费引擎（时长/免费时长/封顶/买断，按租户/点位配置）+ 押金/免押编排（`PaymentPort`，MVP Stub→nearpay 延后）+ 归还结算 + 异常/退款 + 复式记账/分润规则。核心验收：幂等、创单→弹出一致性、计费准确、支付解耦。

## 2. 当前架构分析
- **复用 commons**：`BaseEntity`/`IdGenerator`(order_no)/`DomainEventPublisher`/`Result`/`TenantContext`/账务基础。
- **上游**：C端 BFF（`/mp/trade/*`）、客服（`/api/trade/*`）。
- **下游**：access-gateway（指令+事件）、ops（可用性/点位价/买断通知）、`PaymentPort`→nearpay（延后）。
- **事件驱动**：借出/归还由 `DeviceEvent`（access-gateway 发布）驱动，trade 消费；trade 自身发 `OrderEvent`/`PayEvent`。
- **数据**：`pb_core(trade 子域)` 库（ord_rent / ord_event_log / price_plan / pay_order(引用) / pay_auth / acct_* / share_*）。

## 3. 方案设计

### 3.1 订单状态机
```
CREATED ──(指令下发)──> DISPENSING ──(RENT_CONFIRMED)──> IN_USE ──(RETURNED)──> RETURNED ──(结算)──> SETTLED → CLOSED
   │                        │                                                        
   │(preAuth 失败)          │(指令超时/CommandFailed)                                 
   ▼                        ▼                                                        
 拒绝(不建单)            EXCEPTION(解冻+通知)                                          
IN_USE ──(超时达总封顶)──> EXCEPTION(买断) ；任意态 ──(客服干预)──> 退款/免单
```
- 状态迁移集中在 `RentOrderStateMachine`，非法迁移拒绝；每次迁移落 `ord_event_log` + 发 `OrderEvent`。

### 3.2 计费引擎
```java
interface BillingEngine {
    Money quote(PricePlan plan, Duration used, BillingContext ctx); // 归还时算费
}
```
- 规则：`used ≤ freeMinutes` → 0；否则 `ceil((used-free)/unitMinutes) × unitPrice`，`min(capDaily×天, capTotal)`；达 `capTotal` → `buyout=true`。
- 计费模板 `price_plan` 按租户；点位差异化 `price_rule`（P1）经 ops 取价。纯函数，易单测。

### 3.3 支付端口（解耦核心）
```java
interface PaymentPort {
    AuthResult preAuth(AuthRequest r);      // 冻结押金/免押额度
    CaptureResult capture(String authNo, Money actual);
    void release(String authNo);            // 解冻
    RefundResult refund(RefundRequest r);
    PayQuery query(String ref);
}
```
- **MVP：`StubPaymentPort`**（内存/DB 记账，preAuth/capture 直接成功，模拟回调）——跑通状态机。
- **延后：`NearpayPaymentPort`**（`RestClient` 调 nearpay + 回调 `/notify/pay/nearpay`）。业务只依赖 `PaymentPort`，Spring 按 profile/config 注入实现。

### 3.4 借出流程（一致性：本地事务 + Outbox）
```
POST /mp/trade/orders/rent
 1. 校验: GET /internal/ops/cabinets/{no}/availability
 2. 事务: 建 ord_rent(CREATED) + PaymentPort.preAuth(冻结) + 写 Outbox(弹出指令)
    ├─ preAuth 失败 → 回滚, 拒绝
 3. Outbox → access-gateway POST /internal/gw/commands(EJECT_SLOT, orderNo)
 4. 订单→DISPENSING
 5. 消费 DeviceEvent:
    ├─ RENT_CONFIRMED → IN_USE, 记 powerbank_no/仓位, 开始计时
    └─ CommandFailed/超时 → EXCEPTION + PaymentPort.release + 通知
```

### 3.5 归还流程
```
消费 DeviceEvent RETURNED(归还柜机/仓位/电量)
 1. 幂等校验(order_no 未结)
 2. 停止计时 → BillingEngine.quote → 应扣金额
 3. PaymentPort.capture(实际) 或 release(免费时长内)
 4. 账务复式记账(acct_ledger) + 生成 share_record(分润规则)
 5. 订单 RETURNED→SETTLED→CLOSED, 发 OrderEvent, 通知用户
 失败(capture失败) → 挂账 + 重试 + 告警, 订单留 RETURNED 待补偿
```

### 3.6 异常与退款
- 未弹出：由 §3.4 分支处理（EXCEPTION+release）。
- 超时买断：`OverdueScanner`(XXL-Job) 扫 IN_USE 超阈 → 达 `capTotal` → EXCEPTION(买断) + 通知 ops 标记充电宝 LOST/SOLD。
- 退款：`RefundService` → PaymentPort.refund → refund 单状态跟踪（Stub 直接成功）。
- 幂等：`order_no`/`refund_no` + Outbox/去重表，所有资金动作先记账。

### 3.7 事件契约
| 消费(from access-gateway) | 生产(trade 发) |
|---|---|
| `RENT_CONFIRMED` / `RETURNED` / `CommandFailed` | `OrderCreated` / `OrderInUse` / `OrderSettled` / `OrderException` / `PayCaptured` |

## 4. 核心模型与数据
- `ord_rent`(聚合根，状态机) · `ord_event_log`(append) · `price_plan`/`price_rule`。
- `pay_order`/`pay_auth`/`pay_refund`：**nearpay 交易引用 + 状态镜像**（ADR-005）。
- `acct_account`/`acct_ledger`(复式,append) · `share_rule`/`share_record`。
- 详见 [db-design §5](./db-design.md)。

## 5. 配置项（零硬编码）
| 配置 | 位置 | 默认 |
|------|------|------|
| 免费时长/单位/封顶 | `price_plan`（租户配）| 免费 5min / 30min 单位 / 日封顶 / 买断价 |
| 超时买断阈值 | Nacos `trade.overdue.*` | 72h 或达总封顶 |
| capture 重试 | Nacos `trade.capture.*` | 3 次退避 |
| PaymentPort 实现 | profile/config `trade.payment.provider` | `stub`（MVP）/ `nearpay`（延后）|

## 6. 测试策略
- **单元**：`BillingEngine`（免费时长/累进/日封顶/总封顶买断/跨天 边界值）；`RentOrderStateMachine`（合法/非法迁移）。
- **契约**：消费 `RENT_CONFIRMED`/`RETURNED` → 状态与计费断言；`PaymentPort` 接口契约（Stub 与 nearpay 同契约）。
- **集成**：借出全链路（mock ops 可用性 + mock gateway 指令 + StubPaymentPort）→ IN_USE；归还 → SETTLED + 账务分录断言。
- **必测场景**：① 借出闭环；② 未弹出→解冻；③ 归还计费+请款+记账；④ 超时买断；⑤ 重复归还幂等；⑥ 退款；⑦ preAuth 失败拒绝借出。

## 7. 风险与注意事项
- **资金一致性**：capture/refund 失败的补偿与对账是重点；先记账后执行 + 挂账重试。
- **PaymentPort 契约前瞻**：Stub 契约需贴近 nearpay 真实语义（auth/capture/void/部分请款），减少后期返工——对接前与 neargo 对齐 nearpay 接口。
- **计费边界**：跨天、免费时长临界、封顶买断口径需产品确认（PRD 待确认）。
- **事件乱序/重复**：RETURNED 可能早于 RENT_CONFIRMED 或重复，需幂等 + 状态守卫。

## 8. 实现任务
- [ ] common-api：OrderEvent/PayEvent 契约 + PaymentPort 接口 + DTO
- [ ] pb_core(trade 子域) 建表（ord_rent/ord_event_log/price_plan/pay_*/acct_*/share_*）
- [ ] RentOrderStateMachine + ord_event_log 落库
- [ ] BillingEngine（纯函数 + 全边界单测）
- [ ] PaymentPort + StubPaymentPort（MVP）
- [ ] 借出编排（事务+Outbox+调 gateway）+ DeviceEvent 消费
- [ ] 归还结算 + 复式记账 + 分润规则计算
- [ ] 异常（未弹出/超时买断/坏机）+ OverdueScanner + RefundService
- [ ] BFF/客服 API（/mp/trade、/api/trade）
- [ ] 单元/契约/集成测试（7 必测场景）

---
确认记录：待确认（PRD 待确认项 + 计费口径）
