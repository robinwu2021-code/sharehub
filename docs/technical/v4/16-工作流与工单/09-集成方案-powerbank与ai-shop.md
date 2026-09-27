# 09 · 集成方案：powerbank 与 ai-shop 怎么接入流程与工单服务

> 所属：[16 工作流与工单](./README.md) · 状态：**待确认**（2026-09-27）· 依据：[00 概要设计](./00-概要设计.md) · [08 纯脱耦场景分析](./08-纯脱耦服务-场景分析.md)
> 用户要求：「给出工单和审批流和 ai-shop 和 powerbank 项目的集成方案」。
> 前提：按 08 的结论，服务是**独立库 + 独立进程 + 只有 API 与 webhook**；两个项目都以 HTTP 规格接入，Java 客户端只是封装。文中「服务」= flow 服务，「系统代号」= `SHAREHUB` / `SHOP`（沿用 ADR-025 的 `system` 维度）。
> 「今天」一列都是 2026-09-27 从两边源码查出来的，注明了位置。

---

## 一、两个项目共同的接入骨架

不管接什么流程，每个项目都要做一次的七件事：

| # | 事项 | 做什么 | powerbank 从哪来 | ai-shop 从哪来 |
|:-:|---|---|---|---|
| 1 | **系统登记** | 在服务里登记系统代号、回调地址、凭证 | `SHAREHUB` | `SHOP` |
| 2 | **操作人上下文**（每次调用随请求带） | 类型 · 编号 · 姓名 · 权限集合 · 角色 · 队列 · 租户 | `StaffContext`（员工）· 代理会话 · `SYSTEM`（定时任务） | `SessionProfile` + `OperatorIdentityLoader`（员工 / 商家 / 用户三池） |
| 3 | **数据范围条件**（查待办、查列表时带） | 槽位 → 允许值集合 | 现有 `DataScope`（区域 / 站点 / 代理）翻成锚点条件 | `StaffScopes`（运营员工三个归属键 → 数据域）翻成锚点条件；商家会话固定为「本商户」 |
| 4 | **锚点解析**（提交 / 开单时算） | 从业务对象算出最多 3 个锚点 | 槽位 1 = `site_no`、2 = `agent_no` | 槽位 1 = `merchant_no`、2 = `store_no` |
| 5 | **回调端点** | 接收审批结果、工单迁移、SLA 超时；验签；幂等；只推进自己的状态、不回调服务 | 新增 `/callback/flow/*`；幂等**复用**现有 `EventIdempotency`（`common/event/dedup`） | 新增 `/callback/flow/*`；**直接复用**现有 `idem/IdempotencyService` · `EventIdempotency` |
| 6 | **发件箱与对账** | 调服务前先落发件箱；定时投递；本地「审批中」超阈值的向服务查一次补推 | **复用**现有 `OutboxEventBus` + `OutboxDispatcher`（`common/event`）；对账任务新增 | **复用**现有 outbox（`OutboxDispatcher` · `PlatformOutboxJobHandler`）；对账任务新增 |
| 7 | **定义登记** | 启动时把状态图、审批流程、工单类型（数据 + 版本）推给服务 | 各业务域的 `@Bean` 定义 | 同 |

**决定入口在业务**（08 的 R1）：审批人、受理方的每一个动作都先打业务自己的接口 —— 业务用自己的权限码拦、做自己的重校验 —— 再由业务调服务。服务不给人直接操作的接口。

---

## 二、powerbank 集成方案

### 2.1 审批流

| 流程 | 今天 | 接入后：流程定义 | 业务侧要改 | 语义变化 |
|---|---|---|---|---|
| **进场合同**（`ContractServiceImpl`） | 两级：运营 `location:contract:audit` → 条件触发财务 `location:contract:cosign`；通过前重查时间段重叠；重提覆盖上一轮审批人字段 | 步骤 1「运营」按权限码；步骤 2「财务」进入条件 `needsCosign=true`、按权限码、**开启跨步互斥**（同一人不能既审又签） | ① `submit` 里算 `needsCosign` 与锚点后调服务；② `audit` / `cosign` 接口保留：**先 `requireNoOverlap` 再调服务 decide**；③ 回调置 `SIGNED` / 回 `DRAFT`（幂等键：合同号 + 轮次）；④ 合同表上 `auditedBy / auditNote / financeAudited*` 列停写、保留只读，详情页改调服务 `history` | 无。`PENDING` 已经是中间态 |
| **代理入驻**（`ApplyServiceImpl.audit` → `activate`） | 通过那一刻**同一事务**造主体 + 账号 + 属主关系（ADR-030 §3.4） | 单步，按权限码 | ① 提交 / 决定同上；② 申请单**新增状态 `ACTIVATING`**；③ 回调「通过」→ 置 `ACTIVATING` → 在自己的事务里 `activate`（幂等键：申请号 + 轮次）→ 置 `APPROVED`；失败留在 `ACTIVATING`，运营台可见可重试 | **「通过即激活」→「通过后最终激活」**（08 §五 #1，需确认） |
| **提现**（`WithdrawalServiceImpl`） | 通过前查代理未停用；通过后进出款 | 单步，按权限码 `finance:withdrawal:audit` | 决定接口先 `requireAgentNotSuspended` 再调服务；回调 → 进出款（本就异步） | 无 |
| **退款**（`RefundServiceImpl`） | 运营审批 | 单步 | 同上 | 无 |
| **场地方入驻**（`VenueOnboardingServiceImpl`） | 运营审核 | 单步 | 同上 | 无 |

### 2.2 工单类型

| 类型 | 今天 | 状态图（角色） | 派单 | SLA | 回调里做什么 |
|---|---|---|---|---|---|
| **运维工单** `PB_MAINTENANCE` | `wo_*` 表；`WorkOrderService` + `WoOpsService` 两套并存；派单 / 抢单 / 接管 / 驳回 / 返工 / 复核 / 撤单 / 合并 | `CREATED`(INITIAL) → `DISPATCHED / ACCEPTED / PROCESSING`(ACTIVE) → `DONE`(AWAITING_VERIFY) → `AUDITED` → `CLOSED`(TERMINAL)；`REJECT` 回 `CREATED`；`REWORK` 回 `PROCESSING`；`WITHDRAW` / `MERGE` → `CLOSED` | **候选人在业务算**（站点归属代理 + 站点运维），选定后调 `assign`；抢单池按类型开关 | 规则组 `PB_WO`，从现有 `wo_sla_rule` 迁入；告警类「仅营业时间」用日历 | 完工 → 回写设备状态（幂等键：工单号 + 序号） |
| **客服报障** `PB_CS_TICKET` | `cs_ticket`：`OPEN / PROCESSING / CLOSED`；转退款、转运维工单 | `OPEN`(INITIAL) → `PROCESSING`(ACTIVE) → `CLOSED`(TERMINAL) | 指派给客服 | 响应时限 | 「转退款」是业务动作（读报障 → 建退款单 → 在工单记时间线）；「转运维」= 在服务内开第二张单并关联 |
| **告警开单** | `AlarmEngine` 调 `openOrAttach`；自愈撤单 | 复用 `PB_MAINTENANCE` | 去重键 = 告警码 + 设备 | 同运维 | 无 |
| **巡检开单** | `InspectionPlanRunJob` 定时建单 | 复用 `PB_MAINTENANCE`（来源 = 巡检） | 指派给计划里的负责人 | 同运维 | 无 |
| **工单内嵌审批**（维修费用超额） | 无 | 工单进入「等审批」(WAITING_EXTERNAL) → 服务内发起审批 → 结果推进工单 | —— | 暂停 | 业务只收工单迁移回调 |

### 2.3 状态机（不经服务）

14 个手写状态机改用 `flow-api` 的状态机库，仍在各自业务域里：订单 · 试借 · 机柜 · 充电宝 · 调拨 · 结算 · 提现 · 合同 · 站点 · 商机 · 代理清退 · 活动 · 告警 · 工单（工单那张同时作为工单类型的定义登记到服务）。两条前后端一致卡口改读导出的图。

### 2.4 跨域读取改为调服务

| 今天 | 改为 |
|---|---|
| 代理清退门禁：`COUNT(*) FROM wo_order WHERE assignee = 代理 AND status IN (未结)`（`AgentExitServiceImpl:116`） | 服务 `countOpen(受理方 = AGENT/编号)` |
| 代理月度考核：完成数 · SLA 达标数 · 被接管数（`AgentOpsAssessmentServiceImpl:48-57`，`JOIN wo_sla`） | 服务 `stats(受理方, 时间段)`；口径由服务定义一次 |
| 运营看板：待办数、超时数 | 服务 `summary(锚点条件)` |

### 2.5 分阶段

| 阶段 | 做什么 | 验收 |
|:-:|---|---|
| P1 | 状态机库落地；14 个状态机迁入；卡口改读图 | 全量测试一条不改仍绿；每个状态机一条负对照 |
| P2 | 服务以**测试模式**嵌入 powerbank 进程（同一套 HTTP 契约、进程内直连）；接入骨架七件事 | 契约测试：回调幂等、回调不回调服务 |
| P3 | 合同审批接入（最复杂：条件会签 + 跨步互斥 + 决定前重查） | 合同既有用例全绿；五条内建规则各一条负对照 |
| P4 | 运维工单 + 告警开单 + 巡检开单接入；并掉两套 service；`wo_*` 双写过渡 | 工单既有用例全绿；SLA 暂停 / 超时 / 改派 / 去重各有用例 |
| P5 | 客服报障、提现、退款、场地方入驻接入 | 同上 |
| P6 | 代理入驻接入（引入 `ACTIVATING`）；清退门禁与月度考核改调服务 | 激活失败可重试的用例 |
| P7 | 起独立 `flow-svc` 与独立库；powerbank 切 `mode=remote`；数据按系统代号迁移 | 同一套用例在远程模式下全绿；回调重复 / 丢失 / 乱序各一条 |

P1 ~ P6 都在嵌入的测试模式下完成，P7 才起独立服务 —— 这样 ai-shop 接入时服务已被 powerbank 的全部场景验证过。

---

## 三、ai-shop 集成方案

### 3.1 审批流

| 流程 | 今天 | 接入后：流程定义 | 业务侧要改 |
|---|---|---|---|
| **商品审核**（`MerchantGoodsService.audit`、`OpsGoodsController /ops/goods/{no}/audit`） | `prd_goods.audit_status`：`AUDITING / APPROVED / REJECTED`；审核队列 `auditQueue()` 按数据域分；**有免审分支** | 单步，**队列模式**（审核员领取后决定）；队列按数据域拆（如按类目 / 区域） | ① 商家提交 → 需审的调服务 `submit`（属性：类目、商户；锚点：`merchant_no`）；**免审的直接置 `APPROVED`，不经服务**；② `/ops/goods/{no}/audit` 保留：先做业务校验再调服务 `decide`；③ 回调置 `audit_status`（幂等键：商品号 + 轮次）；④ 审核队列页改调服务 `myTasks` |
| **商户主体申请**（`MchEntityApply`：`PENDING / REVIEWING / APPROVED / REJECTED`） | 平台运营审核；`REVIEWING` 即「已领取」 | 单步，队列模式（`REVIEWING` 对应任务已领取） | 提交 / 决定同上；回调置 `APPROVED / REJECTED`；通过后建主体的动作在回调里做（同 powerbank 入驻，需 `ACTIVATING` 中间态） |
| **平台活动报名审核**（`PlatformActivityService.review`） | 平台运营通过 / 驳回 | 单步，按角色（平台运营） | 同上 |

### 3.2 工单类型

| 类型 | 今天 | 状态图（角色） | 派单 | SLA | 回调里做什么 |
|---|---|---|---|---|---|
| **售后** `SHOP_AFTER_SALE`（`OrdAfterSale`） | 状态 `APPLIED / REFUNDING / REFUNDED / REJECTED / ARBITRATING / CLOSED`；子类型 `REFUND_ONLY / RETURN_REFUND / EXCHANGE`；商家同意 → 仅退款立即退、退货退款先等寄回；驳回后用户可申诉 → 平台仲裁；**商家超时未处理系统自动同意**（`AfterSaleServiceImpl:984`） | `APPLIED`(INITIAL, 受理方 = 商家) → `REFUNDING`(WAITING_EXTERNAL：等支付回执 / 等寄回) → `REFUNDED`(TERMINAL)；`REJECTED`(ACTIVE，**不是终态**：可申诉) → `ARBITRATING`(ESCALATED, 受理方 = 平台) → `REFUNDING` / `CLOSED`；`CLOSED`(TERMINAL) | 开单即指派给子单所属商家；申诉 = `escalate` 到平台 | 规则组 `SHOP_AS`：商家处理时限；**超时 webhook → 业务自动同意**（把今天的定时扫描换成订阅服务的超时事件） | 「同意」→ `doRefund`（幂等键：售后号 + 序号）；「确认收货」→ 退款 |
| **客服工单** `SHOP_CS_TICKET`（`MsgTicket`：`OPEN / REPLIED / CLOSED`；`assignedTo`） | 平台客服指派、回复 | `OPEN`(INITIAL) → `REPLIED`(ACTIVE) → `CLOSED`(TERMINAL) | 指派给客服 | 响应时限 | 回复内容作时间线备注 |

ai-shop 现有 `OrderStateMachine` 注释里把售后 `REJECTED` 列为终态，图里它却有 `→ ARBITRATING` 的出边 —— 迁入时「终态由图推导」会直接纠正。

### 3.3 状态机（不经服务）

`OrderStateMachine` 三张图（订单 5 边 · 子单 9 边 · 售后 11 边）改用状态机库。**每条边要补事件名**（要懂业务的人做）；补名前先用 `projection()` 保持旧调用点 `assertXxxTransit(from, to)` 不变。散在 7 个文件的 `"AUDITING"` 字面量改为枚举。

### 3.4 商家侧与运营侧的待办

| 今天 | 改为 |
|---|---|
| `merchantPendingCount(merchantNo)`（`AfterSaleService:77`） | 服务 `summary(锚点 1 = merchantNo)` |
| 商品审核队列 `auditQueue()` | 服务 `myTasks`（队列成员来自操作人上下文） |

### 3.5 分阶段

| 阶段 | 做什么 | 依赖 |
|:-:|---|---|
| Q1 | `OrderStateMachine` 迁状态机库（先 `projection()` 兼容，再补事件名） | 服务的状态机库发布 |
| Q2 | 接入骨架七件事（复用现有 idem / outbox） | powerbank P7 完成、服务已独立运行 |
| Q3 | 商品审核接入（队列模式；免审不经服务） | Q2 |
| Q4 | 售后接入（含超时自动同意改为订阅 SLA 事件） | Q2 |
| Q5 | 客服工单、商户主体申请、平台活动接入 | Q2 |

ai-shop 不做嵌入模式：它是第二个接入方，直接以远程模式接已被 powerbank 验证过的服务。

---

## 四、两个项目并行时的顺序

| 顺序 | 谁 | 事 | 为什么 |
|:-:|---|---|---|
| 1 | powerbank | P1 ~ P6：嵌入模式下把全部场景跑通 | 服务的每个能力都要先有一个真实调用方验证 |
| 2 | powerbank | P7：起独立服务与独立库，powerbank 切远程 | 让服务先在生产以远程形态跑一段 |
| 3 | ai-shop | Q1（可与 1 并行）：状态机库 | 只依赖 `flow-api`，不依赖服务 |
| 4 | ai-shop | Q2 ~ Q5 | 服务已稳定；ai-shop 只以远程接入 |

**服务的改动归属**：代码先放 powerbank，改动由 powerbank 侧提交；ai-shop 需要的能力（如队列模式的数据域拆分、超时自动同意事件）**提给 powerbank 侧改**，同 ADR-025 里「支付 / 任务的改动提给 ai-shop 统一改」的对称做法。将来独立仓库后再各自提。

---

## 五、两边都要过的验收

| # | 验收 | 怎么验 |
|:-:|---|---|
| 1 | 既有场景测试**一条不改**仍绿 | 全量跑 |
| 2 | 每个迁入的状态机、每条内建规则有负对照 | 拆掉一条边 / 一条规则 → 对应用例红 |
| 3 | 回调幂等：同一结果交付两次，业务只变一次 | 服务自带契约测试 |
| 4 | 回调不回调服务 | 契约测试 |
| 5 | 数据范围 fail-closed：单站点 / 单商户账号看不到别人的 | 场景测试 |
| 6 | 用另一系统的凭证查数据 → 404 | 沿用 ADR-025 的越权验收 |
| 7 | 决定前的业务重校验仍生效（合同重叠、代理停用） | 既有用例覆盖 |
| 8 | 远程模式下回调重复 / 丢失 / 乱序 | 三条用例 |

---

## 六、待确认

| # | 问题 | 建议 |
|:-:|---|---|
| 1 | 代理入驻、商户主体申请接受「通过后最终激活」（引入 `ACTIVATING`） | 接受；否则这两条留在业务里自己写审批 |
| 2 | ai-shop 售后的「商家超时自动同意」改为订阅服务的 SLA 超时事件 | 改；保留一个对账兜底 |
| 3 | ai-shop 商品审核队列怎么拆（按类目 / 区域 / 商户等级） | 由 ai-shop 定；服务只认队列代号 |
| 4 | ai-shop 的 25 条状态边由谁命名 | ai-shop 维护人 |
| 5 | 服务改动的提交归属（先在 powerbank） | 同 ADR-025 的对称做法 |
