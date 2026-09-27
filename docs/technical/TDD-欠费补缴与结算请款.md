# TDD · 欠费补缴与结算请款（C-BL-04）

> 2026-09-27 拍板：**先收钱再分账**。本文是一期的落地方案；二期（押金 / 预授权 / 钱包三个资金来源）见 §6。

## 1. 现状（2026-09-27 查证）

| 环节 | 事实 |
|---|---|
| 结算 | `RentOrderServiceImpl.returnOrderAt` 算完金额就 `SETTLE` 并发 `OrderSettledEvent`，**不调任何支付**（注释自称「支付为骨架」） |
| 分润 | finance 听 `OrderSettledEvent` 按「应收」生成 `share_record` —— 那笔钱从没收过 |
| 欠费 | `ord_order` 没有任何「收没收到」的字段；`ord_deposit.ARREARS` 全仓零写入 |
| 补缴 | `POST /mp/trade/pay` 存在，但不校验订单状态、不幂等（重复调会多一行 `pay_order`）、付完不回写订单 |
| 拦截 | `rent()` 没有任何用户级检查 |
| 买断 | App 发起的买断（`POST /mp/trade/orders/{no}/buyout`）**不发结算事件** —— 这笔收入不分润 |
| 资金来源 | 押金从没真收过（`ord_deposit` 零插入）；`ord_order.auth_no` 从没被写过（免押冻结用的是柜号当订单号）；钱包没有扣款方法 |

## 2. 一期方案

### 2.1 订单收款态（V134）

`ord_order` 加 `pay_status`（`PAID` 已收 / `UNPAID` 欠费；进行中为空）、`paid_amount`、`paid_at`、`pay_no`，
索引 `(c_user_no, pay_status)`。

**存量**：已结算 / 已关闭的历史单一律 `PAID`（`paid_amount = amount`）。它们产生于桩支付阶段、从没有真实请款；
照实标成欠费会让所有老用户借不了、订单列表挂满「欠费」—— 那是把骨架缺口摊到用户脸上，不是修好了 C-BL-04。

### 2.2 结算即请款（`collect`）

结算（归还 / App 买断 / 逾期自动买断）三条路径共用一个收款步骤：

1. 应付为 0（免单、全额券抵）→ `PAID`，照常发事件（分润基数为 0，finance 自己跳过）；
2. 否则经 `PaymentService.pay(type = RENT | BUYOUT, 幂等键 SETTLE-<orderNo>)` 请款：
   - `PAID` → 订单 `PAID` + **此时才发 `OrderSettledEvent`**（分润、站内信随之触发）；
   - 其他 / 抛异常 → 订单 `UNPAID`，**结算本身不回滚**（柜子已经还了、钱已经算了，不能因为扣款失败让订单回到进行中）。WARN 带订单号：用户需补缴，运营可催缴。

**为什么不新增事件**：`OrderSettledEvent` 的注释早就写明基数是「本单**实收**」—— 把发布时机挪到收款之后，
才与它自己的定义一致；finance 不用改。

**资金来源**：一期统一走支付通道请款。现在是桩（恒成功）；接 nearpay 后由通道决定扣卡 / 扣预授权。

### 2.3 欠费拦下次借出

`rent()` 在设备可借校验之后查该用户有无 `UNPAID` 单，有则 409 `error.rent.arrears`（带金额）。
放在 `rent()` 而不是 `RentabilityGuard`：后者是**设备**能不能借（也给列表显示「可借」用），欠费是**人**能不能借。

### 2.4 补缴

`POST /mp/trade/pay`：
- 只接受**已结算且欠费**的单；已收的单 409 `error.pay.already_paid`（此前可重复收）；
- 金额取订单应付（请求体的 amount 不作数，沿用既有约束）；
- 成功 → 订单 `PAID` + 发 `OrderSettledEvent`（迟到的分润照常归结算那个月，`period` 按 `rentEndAt` 定格）。

### 2.5 C 端

- 订单出参带 `payStatus`；订单列表对欠费单打「欠费」标签；
- 订单详情欠费时显示应补金额与「立即补缴」按钮（调 `api.pay`，成功后刷新）；
- 借出被 `error.rent.arrears` 拦下时，提示并引导去订单页。

## 3. 测试

- 桩支付加测试钩子 `StubPaymentPort#declineNextFor(orderNo)`：让该单下一次请款失败（桩在接 nearpay 后整体替换，钩子无 HTTP 入口）。
- 新用例 `ConsumerArrearsTest`：
  1. 请款成功 → `PAID`、有 `pay_order(PAID)`、有分润；
  2. 请款失败 → `UNPAID`、**无分润**、下一次借出 409；补缴 → `PAID`、分润出现、可以再借；
  3. 已收的单再调补缴 → 409（不重复收钱）；
  4. App 买断也请款并分润（此前不发事件）。
- `ConsumerPayAmountTest` 改为先造欠费单再测补缴（原前提「结算后再付」在新语义下就是重复收费）。

## 4. 不在本期

- `force_return` 人工干预直接写 `SETTLED`、不算钱也不发事件 —— 本期不动（`pay_status` 为空，不算欠费）。
- 欠费催缴通知（站内信 / 短信）。

## 5. 风险

- 同一时刻 finance 的分润从「结算时」变成「收款时」：一期桩恒成功，时点几乎重合；接 nearpay 后请款可能异步，届时 `collect` 改为等回调。

## 6. 二期：三个资金来源

拍板时的口径是「免押→扣预授权 / 押金→从押金扣 / 余额→扣钱包」。一期**做不了**，因为三者的前提都不存在（见 §1 最后一行）：
借出时要先真正收押金、把预授权挂到订单上、给钱包加扣款。二期补齐后，`collect` 按来源选择扣哪一个，其余不变。
