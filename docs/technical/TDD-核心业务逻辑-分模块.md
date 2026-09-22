# TDD — 核心业务逻辑（分模块）

> 创建：2026-07-29 · 定位：**骨架之上「不是 CRUD 的那部分」的设计**
> 前置：[db-design.md](./db-design.md)（131 表）· [api/README.md](../api/README.md)（248 端点）· [架构图.md](./架构图.md)
> 不重复的部分：**借还闭环（订单状态机 / 计费引擎 / PaymentPort / 借出归还编排 / 退款）已在
> [TDD-trade-rental.md](./TDD-trade-rental.md) 规定**，本文只补它没覆盖的模块，并在 §0 给出实现顺序。

---

## 〇、实现顺序（按依赖与风险排，不按菜单顺序）

| 批次 | 模块 | 为什么排这个位置 |
|:---:|---|---|
| **B1** | 数据范围注册面（§5） | **安全缺口**：`ord_rent`/`wo_order` 有 `agent_no` 列未接拦截器，代理端一开即越权。与功能无关，先堵 |
| **B2** | 计费引擎 + 取价链（§1） | 纯函数、零依赖、可单测；借还、结算、分润全都下游依赖它 |
| **B3** | 借还编排 + 状态机（TDD-trade-rental §3.4/3.5） | MVP 主线。依赖 B2 |
| **B4** | 分润计算 + 复式记账（§4） | 归还结算的下游。依赖 B3 |
| **B5** | 告警归一化/去重/自动开单（§2） | 运维闭环上游，独立于交易线，可与 B3 并行 |
| **B6** | 工单派单 + SLA 计时（§3） | 依赖 B5（告警是工单主要来源） |
| **B7** | 风控与免押额度（§6） | 借出前置校验，但可先用固定额度跑通 B3，再回填 |
| **B8** | 通知发送管道（§7） | 被 B5/B6/B3 调用，先给同步 Stub，后接真实通道 |

> **B1 先行的理由**：它是唯一一条「不做就有安全问题」的项，其余都是「不做就少个功能」。

---

## 一、计费引擎（`trade/price`）

### 1.1 取价链（四层，逐层覆盖）

价格不是单一来源，取价必须按固定优先级串起来，否则同一订单在不同入口算出不同的钱：

```
① price_plan（基础模板）        ← 订单落 price_plan_no 快照，历史单不重算
      ↓ 覆盖
② price_rule（差异化：按点位/场景/站点）  ← 匹配 dimension + match_ref，取 priority 最高的一条
      ↓ 乘以
③ price_schedule（时段/节假日倍率）      ← multiplier 可 >1，DECIMAL(6,4)
      ↓ 应用
④ 会员权益 / 优惠券                      ← 免费时长增量、折扣率、封顶下调
```

**关键约束**：
- **`price_plan_no` 在下单时快照到 `ord_rent`**，模板后续改动只影响新订单（[api/README §4.2]）。
- ②③④ 不快照，**按归还时刻重算** —— 时段价本来就依赖使用时段；会员/券在归还结算时才确定。
- 四层的入参出参都是值对象，**取价链是纯函数**，不碰 DB（DB 读在外层做），便于单测。

### 1.2 计费公式

```
billable = max(0, usedMinutes - freeMinutes)          // freeMinutes 含会员权益增量
units    = ceil(billable / unitMinutes)
raw      = units × unitPrice × scheduleMultiplier
fee      = min(raw, capDaily × ceil(usedMinutes/1440), capTotal)
```

- `fee == capTotal` → **`buyout = true`**，充电宝转 `SOLD`（[db-design §9A.1]），订单进 `EXCEPTION` 走买断流程。
- 券在 `fee` 算出后抵扣，**不参与封顶计算**（先封顶再抵扣，否则券会被封顶吃掉）。
- 免费单（`free_reason` 非空）：`fee = 0` 且 `waived_amount = 本应收的 fee`，**要算出来再减免**，
  否则免费订单的统计口径（月减免总额）拿不到数。

### 1.3 边界

| 场景 | 处理 |
|---|---|
| 免费时长内归还 | `fee=0`，`PaymentPort.release` 全额解冻，不 capture |
| 跨天 | 按 `capDaily × 自然天数` 封顶，不是按 24h 段 |
| 超时未归还 | `OverdueScanner` 扫 `IN_USE`，达 `capTotal` → 买断；超阈未归还 → 充电宝 `LOST` |
| `usedMinutes` 为负（时钟回拨） | 取 0，记 WARN 日志，不抛异常（不能因为时钟问题卡住归还） |

---

## 二、告警（`dev/alarm`）

### 2.1 多厂商错误码归一化

设备上行带的是**厂商私有错误码**，不同厂商同一故障码值不同。归一化在**入口一次完成**：

```
DeviceEvent(FAULT, vendorCode, vendorErrorCode)
   → 查 gw_vendor 的码表映射 → alarm_code（平台统一码）
   → 查 dev_alarm_code 字典 → level / suggestion / autoWorkOrder
   → 落 dev_alarm（双列都存：alarm_code + vendor_error_code）
```

**两列都要存**：`alarm_code` 用于聚合统计与规则匹配，`vendor_error_code` 用于回厂排障 —— 丢了后者，
供应商那边无法复现。映射缺失时 `alarm_code = 'UNKNOWN'` 并**照常落库**，不能丢告警。

### 2.2 去重（`dedup_key`）

同一故障会持续上报，不能一条一单：

```
dedup_key = hash(cabinet_no + alarm_code + 故障持续窗口起点)
窗口内重复上报 → count++ , 更新 occurred_at , 不新建行
窗口 = 可配（默认 30 分钟），来自 sys_param
```

告警 `status` 转 `CLOSED` 后，同 key 再来 → **新建一行**（这是新一轮故障，不是旧的延续）。

### 2.3 自动开单与幂等

`dev_alarm_code.auto_work_order = true` → 建工单，`source=ALERT`、`source_ref=alarm_no`。
**幂等靠 `wo_order.source_ref` 的 UNIQUE**：重复触发返回首次的 `wo_no`，不产生第二张单。
建单成功后回填 `dev_alarm.wo_no` —— 这个回填是「我们比竞品多的那一环」（对方到发通知就断链）。

### 2.4 静默窗口与升级

```
命中 dev_alarm_rule
  ├─ 当前时刻在 [quiet_start, quiet_end) 内 → 不即时发，入待发队列，窗口结束后合并发一条摘要
  ├─ method=DIGEST → 同样合并
  └─ 否则即时发
发出后启动升级计时：escalate_minutes 内 status 仍为 OPEN → 升一级目标再发一次
```

**静默窗口跨零点**（如 22:00–08:00）必须支持 —— 判断写成 `start <= end ? (t>=start && t<end) : (t>=start || t<end)`，
这是最容易写错的一行。

---

## 三、工单（`wo`）

### 3.1 派单策略

```java
interface DispatchStrategy { String pick(WoOrder wo, List<Assignee> candidates); }
```

| 策略 | 规则 | 何时用 |
|---|---|---|
| `MANUAL` | 指定人 | 默认，人工兜底 |
| `NEAREST` | 候选人当前位置到 `cabinet_no` 所属站点的直线距离最小 | 有位置数据时 |
| `LOAD` | 在办工单数最少；并列则取 `NEAREST` | 位置数据缺失时 |
| `GRAB` | 不指派，进抢单池 | P2 |

**候选人过滤（先于策略）**：角色含 OPS · 状态 ACTIVE · **数据范围覆盖该工单的 `site_no`/`agent_no`**。
最后一条容易漏 —— 派给一个看不到这台设备的人，他打开工单会 403。

### 3.2 SLA 计时

`wo_sla` 是**逐单计时**，`wo_sla_rule` 是**规则配置**，两者别混：

```
派单(DISPATCHED) → 写 wo_sla.respond_due_at = now + rule.responseMins
接单(ACCEPTED)   → 若 now > respond_due_at 则 respond_breached = true
完工(DONE)       → 若 now > resolve_due_at 则 resolve_breached = true
超时未接单       → 扫描任务触发升级（escalate_to），记 escalated_at
```

**`ACCEPTED` 态不能省**（[db-design §9A.4]）—— 它是响应 SLA 的唯一计时终点。

### 3.3 关单

`CLOSED` 时 **`close_reason` 必填**（`RESOLVED/INVALID/DUPLICATE/WITHDRAWN`）。
误报告警产生的单走 `INVALID`，不新增 `CANCELLED` 状态（避免多一条平行终态线）。
`AUDITED` 验收不通过 → 退回 `PROCESSING` 返工，不是直接关。

---

## 四、分润与账务（`trade/finance`）

### 4.1 分润计算时点与多级

**时点**：订单 `SETTLED` 时一次性计算，不在 `RETURNED` 时算（那时 capture 可能还没成功，金额未定）。

**多级**：一笔订单可能同时分给场地方与代理：

```
基数 = ord_rent.fee_amount（实收，非应收；免费单不分润）
  ├─ VENUE 维度：按站点关联的 loc_contract.share_rate
  └─ AGENT 维度：按 agt_commission（dimension=GMV 用 rate；=ORDER_COUNT 用 fixed_amount）
两者独立计算、互不扣减，各生成一条 share_record
```

**规则冲突**：`share_rule` 同维度命中多条时取 `priority` 最高的**一条**，不叠加。
分润合计 > 基数时 **拒绝并告警**（配置错误），不静默截断 —— 静默截断会让账对不平且没人发现。

### 4.2 复式记账

`acct_ledger` 只增不改。每笔业务动作产出**一组**分录，组内**借贷必须平衡**：

```
归还结算（fee=10, 场地方分3, 平台留7）：
  DEBIT  用户应付        10
  CREDIT 平台收入        7
  CREDIT 场地方应付      3
```

**落库前校验 `sum(DEBIT) == sum(CREDIT)`，不平直接抛异常回滚**。这是复式记账相对流水账的全部价值所在，
校验省掉就退化成了流水账。同组分录共享 `voucher_no`。

### 4.3 提现手续费

`fee` 的**唯一来源**是 `sys_biz_rule(category='WITHDRAW')` 的 `feeRate`/`feeCap`：

```
fee = min(amount × feeRate, feeCap)
实际到账 = amount - fee     ← 派生值，不落库
```

财务页不得另存一份费率。审批驳回**必须**有 `reject_reason`；`auditor_no`/`audited_at` **服务端回填，不信前端**。

---

## 五、数据范围注册面（`auth`）— **B1 先行**

### 5.1 现状与缺口

`DataScopeHandler` 已实现，但 `DataScopeTableRegistry` **只注册了 `loc_site`**。
`ord_rent` 与 `wo_order` 都已有 `agent_no` 冗余列却未注册 → **AGENT 角色能看到全量订单与工单**。

### 5.2 补齐清单

| 表 | 过滤列 | 支持的 scope | 状态 |
|---|---|---|---|
| `loc_site` | `agent_no` / `region_id` / `site_no` | AGENT / REGION / SITE | ✅ 原有 |
| `loc_location` | `agent_no` / `site_no` | AGENT / SITE | ✅ **B1 已注册** |
| `dev_cabinet` | `agent_no` / `site_no` | AGENT / SITE | ✅ **B1 已注册** |
| `ord_rent` | `agent_no` / `site_no` / **`c_user_no`** | AGENT / SITE / **SELF** | ✅ **B1 已注册** |
| `wo_order` | `agent_no` / `site_no` | AGENT / SITE | ✅ **B1 已注册** |
| `dev_alarm` | `agent_no` / `site_no` | AGENT / SITE | ⬜ 表未创建 |
| `share_record` | `payee_no`(payee_type=AGENT 时) | AGENT | ⬜ 表未创建 + 需条件锚点 |
| `stl_settlement` | `payee_no` | AGENT | ⬜ 表未创建 |

> **前置条件是列必须真的存在**。B1 落地时发现开发库里 `ord_rent`/`wo_order`/`dev_cabinet`/`loc_location`
> **都没有 `agent_no`** —— 这才是数据范围长期只注册了一张表的真实原因（不是「忘了注册」）。
> 补列与按归属链回填见 `ddl/pb_core-v2-datascope.sql`。

### 5.3 `SELF` 语义 —— ⚠️ 本节已被实测推翻并重写

> **原结论（错误）**：「C 端属主不走数据范围拦截器，两套机制别混」。
> 该结论建立在「未登记的维度会放行」这个**未经验证的假设**上。

**实测行为**：`DataScopeHandler.buildCondition` 在「当前 spec 的维度在本表锚点里找不到列」时，
生成的是 **`1=0`（全部拒绝）而非放行** —— 它是 fail-closed 的。

**由此得出的真正规则**：

1. **一张表一旦被注册，所有可能访问它的主体的维度都必须登记**，漏一个 = 那类主体全瞎。
   C 端会话的 spec 是 `SELF`，所以 `ord_rent` 必须登记 `SELF → c_user_no`；
   漏登记的直接后果是 C 端「我的订单」返回空集（B1 落地时 `ConsumerRentFlowTest` 实测挂掉）。
2. 登记 `SELF` 后，属主过滤额外获得了 **SQL 层的防 IDOR 兜底**，是纵深防御而非冗余。
3. **但显式属主守卫仍不能撤**：数据范围会在守卫之前把行过滤掉，导致
   「403 无权」退化成「400 不存在」。需要 403 语义的路径（如 `GET /mp/trade/orders/{no}`）
   必须用 `DataScopeContext.executeWithoutScope(...)` 取数、再由守卫判定 ——
   见 `RentOrderService#detailForConsumer`。
   **豁免只能加在有显式守卫兜底的那一条路径上**：给运营端共用的 `detail()` 加豁免，
   会让 AGENT 读到其它代理的订单详情。

**遗留问题（`/mp/nearby/**` 落地时必须处理）**：`loc_site`/`dev_cabinet`/`loc_location`
上没有、也不该有 `c_user_no` 这类 SELF 锚点，而 C 端要浏览它们找附近网点。
按 fail-closed 规则，附近网点查询会拿到空集。届时应在那几个查询上显式 `executeWithoutScope`，
**而不是**给这些表编一个假的 SELF 锚点。

### 5.4 验收

每张新注册的表都要有一条测试：**AGENT 角色查询，返回集合中不含其它 `agent_no` 的行**。
没有这条测试的注册视为未完成 —— 数据范围是那种「不测就等于没做」的能力。

---

## 六、风控与免押（`user/core`）

### 6.1 借出前置校验（顺序固定，短路返回）

```
1. 已登录？             否 → 引导登录
2. 在黑名单？           是 → 拒绝 + 展示原因与申诉入口（usr_blacklist.status=ACTIVE）
3. 有欠费？             是 → 拒绝 + 跳补缴（ord_rent 有 EXCEPTION/未结清）
4. 有进行中订单？       依配置：默认允许多单并行，可配单用户并发上限
5. 在免费白名单？       是 → 免押 + 免费，跳过 6/7
6. 信用分够免押额度？   是 → preAuth 冻结额度；否 → 降级押金
7. 免押/押金授权成功？  否 → 拒绝（不建单）
```

**第 3 条最容易漏**：欠费不拦，用户可以无限借下去不还钱。

### 6.2 免押额度

```
额度 = base(来自 sys_biz_rule BILLING) × 信用分系数 × 会员加成
信用分系数：≥700 → 1.0 ; 600-699 → 0.7 ; <600 → 不予免押
```

系数表落 `sys_param`，**不硬编码**。免押失败一律**降级押金**，不直接拒绝借出。

### 6.3 信用分变动

| 事件 | 变动 |
|---|---|
| 正常归还 | +1（上限 850）|
| 超时未归还 | −20 |
| 买断/丢失 | −50 |
| 投诉被判定为恶意 | −30 |
| 欠费结清 | +5 |

变动**必须留痕**（`usr_credit` 更新 + 审计），否则用户申诉时无法解释分数怎么来的。

---

## 七、通知发送管道（`platform/notify`）

```
send(templateCode, target, params, channel)
 1. 查 notify_blacklist —— 命中(含 channel=ALL) → 丢弃，落 notify_log(status=BLOCKED)
 2. 查 usr_notify_pref —— 分类关闭 / 免打扰时段内 → 延后或丢弃（关键通知除外）
 3. 渲染 notify_template（按用户语言取 ar/en/zh）
 4. 发送 → 落 notify_log（target 存储即脱敏、记 cost）
 5. 失败 → 重试 N 次 → 终态 FAILED + fail_reason
```

**「关键通知」豁免免打扰**：借出成功、归还成功、请款结果、退款到账 —— 这几类不受免打扰与营销开关限制
（[C端功能清单 C-MS-01]「关键通知必达」）。营销类一律受限。

`target` **落库即脱敏**（手机留前 6 后 2、邮箱留首字母 + 域名），不是查询时才脱敏 ——
日志表会被导出、被下游消费，脱敏必须发生在写入侧。

---

## 八、横切：幂等的四种落法

| 场景 | 落法 | 表 |
|---|---|---|
| 用户重复提交（退款申请） | 前端生成 `Idempotency-Key` + 表内 UNIQUE | `ord_refund.idempotency_key` |
| 系统重复触发（告警/投诉转工单） | 业务外键 UNIQUE，命中返首次结果 | `wo_order.source_ref` |
| 外部重复回调（nearpay） | UK(来源单号, 事件类型) | `pay_event_log(ref_no, event_type)` |
| 指令重复下发 | 全局 `command_id` + Redis SETNX | `gw_command_log.command_id` |

**统一行为：重复请求返回首次结果（200），不返 409。** 409 会让前端以为失败而重试，反而放大问题。

---

## 九、测试策略

| 层 | 覆盖什么 | 形式 |
|---|---|---|
| 纯函数 | 计费公式、静默窗口跨零点、借贷平衡校验、免押额度 | 单测，无 Spring 上下文 |
| 状态机 | 合法迁移全通过 + **每个非法迁移都抛异常** | 单测 |
| 数据范围 | 每张注册表：AGENT 查询不含他人数据 | 集成测试（§5.4，硬要求）|
| 幂等 | 四种场景各一条「调两次结果相同且只产生一条记录」 | 集成测试 |
| 主链路 | 借出→使用→归还→结算→分润 端到端 | 集成测试（已有 `ConsumerRentFlowTest` 可扩）|

---

## 十、待确认

1. **多单并行上限**（§6.1 第 4 条）：默认允许几单？前端「多单并行时分别展示」暗示 >1。
2. **信用分变动数值**（§6.3）是我按常见做法拟的，需产品确认；上下限（850/0）同。
3. **告警去重窗口**默认 30 分钟是否合适？窗口太长会漏掉「修好又坏」，太短会告警风暴。
4. **分润合计 > 基数时拒绝**（§4.1）—— 也可以选择按比例缩放。本文取「拒绝并告警」，因为这是配置错误，
   缩放会掩盖问题。
5. 派单 `NEAREST` 依赖运维位置数据，当前无采集 —— 首期是否只做 `MANUAL` + `LOAD`？
