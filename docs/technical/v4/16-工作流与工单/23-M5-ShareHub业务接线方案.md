# M5 · ShareHub 业务接线方案（待拍板）

> 2026-09-28 · 起草：前端 / 联调会话 · 状态：**已拍板（2026-09-28，§八 四条全按建议），执行中**
> 依据：21/07-任务清单 §6.2（P1–P8）· 21/01 附录 A · 21/06-接口设计 · 现网实测（生产 OpsKit fc9fb341，`/flow/*` 已开）
> 本文回答三件事：**先接哪条业务、两个系统怎么说话、出了错怎么兜**。末尾 §八 是需要拍板的四个问题。

---

## 一、现状：OpsKit 已就绪、ShareHub 一条业务都没接

| 已有 | 说明 |
|---|---|
| 登录打通 | ShareHub 内省端点 `POST /internal/opskit/introspect` 已上线；运营登录一次，OpsKit 认得人、角色（§16.1 映射）、数据范围（`*` / `agent:AGxxx`） |
| 生产 OpsKit | 空间 `ext:MAIN`；预置流程 `repair_flow` · SLA `repair_sla` · 工单类型「设备报修」（手动派单） |
| 运营界面 | `/flow/*` 九页已连生产 OpsKit：待办 · 审批 · 审核队列 · 工单 · 配置 · 实例 · 统计 |
| ShareHub 投递底座 | `sys_outbox`（同事务写、提交后投递、退避重试、6 次后 DEAD）；**`OutboxConsumer` 零实现** —— 正好是「推给 OpsKit」的插口；消费侧去重 `sys_event_consumed` |

**缺口**：生产上的审批与工单页是**空的** —— 没有任何业务往 OpsKit 里送单。M5 就是补这一段。

## 二、候选业务盘点（ShareHub 现有，按价值排序）

### 审批类（→ OpsKit 流程实例 + 审批）

| 业务 | 现状（ShareHub） | 审批形态 | 难度 |
|---|---|---|:-:|
| **提现** | `WithdrawalServiceImpl` apply → audit → pay；审批人 ≠ 放款人已落（WARN 档） | 单级审批（财务），放款仍在 ShareHub | ★ |
| **合同** | `ContractServiceImpl` 最复杂：提交 → 审核 →（条件）财务会签 → 签署；另有**终止审批** | 两级审批门 + 条件会签 + 外部确认门（时段不重叠） | ★★★ |
| 代理入驻 | `ApplyServiceImpl`，通过即激活代理与账号 | 单级 + 外部确认门（激活成功才生效） | ★★ |
| 退款 | `RefundServiceImpl`；⚠️ 批了也不真退（TODO） | 单级 | ★（但先要补真退款） |
| 场地方入驻 | `VenueOnboardingServiceImpl` review | 单级 | ★ |
| 结算确认 / 调整单 | `SettlementStateMachine` · `AdjustmentServiceImpl` | 确认型，更像状态机 | 不建议进 |

### 工单类（→ OpsKit 工单）

| 业务 | 现状 | 进 OpsKit 的形态 |
|---|---|---|
| **客服报障** | `CsTicketServiceImpl.report()` 先建客服单，再按问题字典分流（自助 / 开工单 / 退款） | 工单类型「报障」；C 端报障、客服手工都走建单 |
| **告警开单** | `AlarmEngineImpl.dispose()` 按告警码路由，`openOrAttach` 已有去重键 | 工单类型「告警」，`dedupeKey` = 现有去重键 |
| 运维工单 | `wo_*` 七态 + 派单 / 抢单池 / 接管 / SLA 扫描 / 巡检 / 费用 / 处理记录 | 见 §六 —— **这块最大，建议单独决策** |
| 宝疑似丢失 | 定时任务打标，人工确认 / 解除 | 可做一个轻工单类型；不急 |

**不进 OpsKit**：站点五态、代理清退、设备上线门、调价 —— 它们是**有门禁的状态机**，没有「人审批」这一步，搬过去只是换个地方存状态。21/07 的 P1（14 个状态机改用状态机库）同理，**建议移出 M5**，不影响接线。

## 三、总体设计：业务记录留在 ShareHub，「谁批 / 谁办」交给 OpsKit

```
 ShareHub                                             OpsKit
 ─────────                                            ──────
 业务写入（如提现申请）                                  
   ├─ 业务行：状态 = 待审批(PENDING_AUDIT)               
   └─ sys_outbox：opskit.submit（同事务）                
          │  提交后投递（OutboxConsumer: OpsKitSubmitter）
          └──────────────► POST /instances   (应用凭证 okk_… + onBehalfOf=提交人 + Idempotency-Key=业务号)
                                    │
                                    ▼  运营在 /flow/approvals 决定（驳回必填原因 · 跨步互斥 · 自审禁止）
          ◄──────────────  webhook  instance.transitioned / approval.completed（HMAC 签名）
 /internal/opskit/webhook
   ├─ 验签 · 按 idemKey 去重（sys_event_consumed）
   └─ 调既有服务方法推进业务行（如 audit(APPROVE)），操作人 = OpsKit 回传的决定人
```

**三条原则**

1. **业务状态的真源仍是 ShareHub**：提现单、合同的状态字段、金额、放款都在 ShareHub；OpsKit 只管「这一步该谁、谁决定了什么、超时没」。webhook 到了才推进业务行 —— **ShareHub 不会因为 OpsKit 挂了而丢数据或乱状态**。
2. **推进复用既有服务方法**：webhook 接收器调 `WithdrawalService.audit(...)` 这类现成方法，业务规则（金额、自审、同人放款标记）一处不改，既有场景测试不改仍绿（21/README 的 M5 出口标准）。
3. **每条业务一个开关**：`sharehub.flow.<业务>.engine = LOCAL | OPSKIT`。LOCAL = 现在的样子（运营在业务页上直接审）；OPSKIT = 送 OpsKit。**一条一条切、随时可切回**。

### 3.1 ShareHub → OpsKit（送单）

| 项 | 做法 |
|---|---|
| 时机 | 业务写入与 `sys_outbox` 同事务；提交后由 `OpsKitSubmitter`（`OutboxConsumer` 首个实现）投递 |
| 身份 | 应用凭证 `okk_…`（放 env，同内省凭据一样管理）；`onBehalfOf` = 提交人（代理提现 = 代理本人），审批单上显示真实提交人 |
| 业务对象 | `subject = {type: "withdrawal", ref: 提现单号, display: {title: "提现 AED 1,200 · AG0007"}}` —— `/flow/instances` 的「按业务对象对账」直接可用 |
| 范围标签 | `scopes = ["agent:AG0007"]`：代理只看得到自己的单，财务（`*`）看全部 |
| 幂等 | `Idempotency-Key = opskit:<业务>:<业务号>:<轮次>`；重投拿到的是同一个实例 |
| 失败 | 退避重试 6 次后 DEAD → 业务行停在「待审批 · 送审失败」，业务页显示「重新送审」按钮 + 一条 ERROR（谁该做什么：检查 OpsKit 可用性后点重送） |

### 3.2 OpsKit → ShareHub（回写）

| 项 | 做法 |
|---|---|
| 入口 | `POST /internal/opskit/webhook`（只本机可达，nginx 不转发 `/internal/`；与内省端点同一原则） |
| 验签 | `X-OpsKit-Signature: t=…,v1=HMAC-SHA256(secret, t.body)`，±5 分钟；失败 401 且不落任何业务 |
| 去重 | `idemKey` 进 `sys_event_consumed`；OpsKit 同一事件重投多次只推进一次 |
| 顺序 | 同一实例按 `seq` 递增；收到旧 `seq` 直接丢弃（业务行已前进） |
| 推进 | 业务已不在「待审批」时回 200 并记 WARN（人工在 ShareHub 页上已处理过 —— 切换期可能发生），**不报错重投** |

### 3.3 对账（兜「两边说法不一」）

每日一次（业务时区 03:00）：ShareHub 所有「待审批 > 24h」的行，逐条查 `GET /subjects/{type}/{ref}/history`；OpsKit 已结而 ShareHub 未推进 → 补推进并 WARN；OpsKit 查无此单 → 标「送审失败」进重送。

### 3.4 界面：决定只在一处做

- **审批的决定一律在 `/flow/approvals`**（统一收件箱、驳回原因、跨步互斥都已有）。
- 业务页（如「财务 › 提现审核」）在 OPSKIT 模式下**不再显示通过 / 驳回按钮**，改显示审批进度（当前步骤 · 谁在审 · 已等多久）+「去审批」链接到 `/flow/approvals/detail`；**放款按钮留在业务页**（放款是执行，不是审批）。
- 两处都能点「通过」的话，一定会出现两边状态打架 —— 所以不做。

## 四、OpsKit 侧必须先补的（M5-0，归 OpsKit 会话）

调研确认：能力在核心代码里都有，**HTTP 没开出来**，且 07 任务清单里没有任务覆盖这些：

| # | 缺口 | 为什么必须 |
|:-:|---|---|
| 1 | `POST /instances`（`InstanceAppService.start` 已有） | 送审入口 |
| 2 | HTTP 解析 `onBehalfOf`（`ActorResolver.forApp` 已支持） | 审批单显示真实提交人、自审禁止才判得准 |
| 3 | `Idempotency-Key` 过滤器（`IdempotencyStore` 已是 Bean，未接） | 重投不重复建单 |
| 4 | 应用凭证签发入口 + webhook 订阅 API（`WebhookDao.subscribe` 无调用方） | 生产上没法开应用、没法订阅 |
| 5 | `approval.completed` 带 `subject`；信封补 `workspaceExternalRef` | 回写时不用再反查实例 |
| 6 | `POST /confirmations/{token}` | 合同「时段不重叠」、代理入驻「激活成功才生效」要用；提现试点不需要 |
| 7 | 目录同步 `POST /directory/sync` + `PUT /groups/{code}` | 队列组、代理公司组、没登录过但要被指派的人 |
| 8 | `ticket.*` 事件（至少 created / transitioned / closed） | 工单回写 ShareHub（报障闭环通知用户） |

1–5 是试点的硬前置；6 在合同前补；7、8 在工单阶段前补。

## 五、分期（建议）

| 期 | 内容 | 验收 | 估（人日） |
|---|---|---|:-:|
| **M5-0** | OpsKit 补 §四 1–5；ShareHub 侧接入骨架：`OpsKitClient` · `OpsKitSubmitter`（OutboxConsumer）· webhook 接收器（验签 / 去重 / 顺序）· 对账任务 · 每业务开关 | 骨架有负对照：坏签名 401、重复事件只推进一次、OpsKit 停机 → 重试 → DEAD → 重送成功 | 3（OpsKit）+ 3（ShareHub） |
| **M5-1 试点：提现** | 代理申请 → OpsKit 单级审批（财务）→ 回写 audit → 放款仍在业务页 | 端到端：代理提交 → 财务在 `/flow/approvals` 通过 → 提现单变「放款中」；驳回带原因回写；开关切回 LOCAL 立即恢复旧行为；既有提现用例全绿 | 2 |
| **M5-2 合同** | 两级审批 + 条件会签 + 终止审批；外部确认门「时段不重叠」 | 21/07 P4 验收（五条规则各一条负对照） | 4 |
| **M5-3 报障 / 告警工单** | C 端报障、客服建单 → 「报障」类型；告警 → 「告警」类型（去重键沿用）；`ticket.*` 回写报障单状态并通知用户 | 报障从 C 端提交到运营关单全链路；同一告警重复触发只挂一张单 | 4 |
| **M5-4 其余审批** | 代理入驻（确认门）· 场地方入驻 · 退款（先补真退款） | 各一条端到端 | 3 |
| **M5-5 运维工单** | 见 §六的决策结果 | —— | 5–8 |
| **M5-6 收尾** | 各开关默认 OPSKIT；旧审批按钮下线；目录同步定时；监控看板 | —— | 2 |

**顺序理由**：提现最简单（单级、单角色、状态清楚），用它把「送单 · 回写 · 对账 · 开关」整条管道跑通，后面每条业务只是换 subject 和回写方法。合同价值最高但规则最多，放在管道验证之后。

## 六、运维工单（`wo_*`）：需要单独决策

ShareHub 的运维工单不只是「流转」：还有费用明细、处理记录与照片、巡检计划、派生工单、按代理停用批量改派。两条路：

| | A. 流转进 OpsKit，执行细节留 ShareHub | B. 暂不迁，只把报障 / 告警进 OpsKit |
|---|---|---|
| 做法 | 工单的状态 / 派单 / SLA / 时间线由 OpsKit 管；费用、处理照片、巡检仍存 ShareHub，按 `ticketNo` 挂 | `wo_*` 原样；`/work-orders` 页保留；OpsKit 里只有报障与告警单 |
| 好处 | 一个工作台看全部工单；SLA / 统计一个口径 | 风险小、工作量小 |
| 代价 | 5–8 人日；`wo_*` 双写过渡；现有工单页要重做或下线 | 两套工单并存，运营要看两个地方 |

建议：**先 B，M5-3 做完再评估 A** —— 报障与告警上线后，能看到运营实际怎么用工作台，再决定运维工单搬不搬。

## 七、风险

| 风险 | 对策 |
|---|---|
| 两边状态打架（切换期有人在旧页审） | 开关 = OPSKIT 时业务页不再出审批按钮；回写遇到「已不在待审批」只 WARN 不重投 |
| OpsKit 停机 | 送审走 outbox，业务写入不受影响；审批期间停机只是「晚一点推进」；对账兜底 |
| webhook 伪造 | 只本机可达 + HMAC 验签 + 时间窗；密钥同内省凭据一样放 env |
| 代理看到别人的单 | 送审必带 `scopes`（agent 标签）；OpsKit 可见性 fail-closed；每条业务一条「代理看不到别家」负对照 |
| 生产 OpsKit 空间里没有流程定义 | 每条业务的流程随 bootstrap `definitions-dir` 发布（已验证：改文件 → 重启 → 新版本，在途单走旧版本） |

## 八、拍板结果（2026-09-28：四条全按建议）

1. **试点选提现**（而不是 21/07 原定的合同先行）？—— 建议提现，理由见 §五。
2. **审批决定只在 `/flow/approvals` 做**，业务页只显示进度 + 链接？—— 建议是。
3. **运维工单先不迁**（§六 B），报障 / 告警先进？—— 建议是。
4. **P1（14 个状态机改用状态机库）移出 M5**？—— 建议移出，接线不依赖它。

拍板后：M5-0 的 OpsKit 部分交 OpsKit 会话，ShareHub 骨架与提现试点由本会话做，完成标准是 §五 M5-1 的端到端用例在私有实例与生产各跑通一次。

---

## 九、ShareHub 侧实施设计（M5-0 骨架 + M5-1 提现）

### 9.1 分层落点

| 模块 | 新增 / 改动 |
|---|---|
| `sharehub-common` | **修投递缺口**：`OutboxEventBus` 进程内投递后，若有跨进程消费者（`OutboxConsumer`）认领该类型，**不再直接标 SENT**，而是立即交给 `OutboxDispatcher.dispatchOne` —— 此前 `OutboxConsumer` 只有在进程内投递**失败**时才会被调用（零实现所以没人发现）；`OutboxConsumer` 加 `onDead(row)` 钩子，转死信时通知认领方 |
| `sharehub-api` | 事件 `OpsKitApprovalRequested{bizType, bizNo, round, processCode, subject, fields, scopes, submitter}`；SPI `OpsKitApprovalOutcome`（`bizType()` · `onSubmitted` · `onSubmitFailed` · `onCompleted`） |
| `sharehub-app` `integration/opskit` | `OpsKitProperties`（`sharehub.opskit.*`）· `OpsKitClient`（RestClient，应用凭证 + `Idempotency-Key`）· `OpsKitSubmitter`（`OutboxConsumer`）· `OpsKitWebhookController`（`POST /internal/opskit/webhook`：验签 → `EventIdempotency.once(idemKey)` → 按 `subject.type` 路由到 SPI） |
| `sharehub-svc-finance` | 提现：`sharehub.flow.withdrawal.engine=LOCAL|OPSKIT`；申请时 OPSKIT 模式发事件；新列 `approval_no` · `approval_state`；`WithdrawalApprovalOutcome` 实现 SPI；OPSKIT 在审的单拒绝旧 `/audit`（409） |
| `ops-web` | 财务 › 提现审核：OPSKIT 在审显示进度 + 「去审批」，隐藏通过 / 驳回；送审失败 / 审批通过未生效显示「重新送审」/「重试生效」 |

### 9.2 提现的 `approval_state`（新列，词表）

| 值 | 含义 | 出口 |
|---|---|---|
| 空 | LOCAL 模式（原样） | —— |
| `SUBMITTING` | 已申请，送审事件在途 | → `IN_REVIEW`（OpsKit 建单成功）· → `SUBMIT_FAILED`（重投达上限） |
| `IN_REVIEW` | OpsKit 审批中（`approval_no` 已回填） | → 空（审批结束且已回写 `status`） · → `APPROVED_BLOCKED` |
| `SUBMIT_FAILED` | 送审失败 | 「重新送审」→ `SUBMITTING`（新轮次幂等键） |
| `APPROVED_BLOCKED` | OpsKit 已通过，但 ShareHub 业务检查不让过（无收款账户 / 代理停用） | 补齐后「重试生效」→ 走 `audit(approve)` 同一段逻辑 |

`status` 仍是原提现状态机（APPLY → PAYING → PAID / FAILED），**不新增状态**；`approval_state` 只描述「审批这件事」走到哪。

### 9.3 配置（生产 env）

`SHAREHUB_OPSKIT_BASE_URL=http://127.0.0.1:8090` · `SHAREHUB_OPSKIT_WS=ext:MAIN` · `SHAREHUB_OPSKIT_APP_TOKEN=okk_…` · `SHAREHUB_OPSKIT_WEBHOOK_SECRET=…` · `SHAREHUB_OUTBOX_DISPATCH_ENABLED=true`（单实例，打开重投轮询 —— 此前生产是关的，失败事件无人重投） · `SHAREHUB_FLOW_WITHDRAWAL_ENGINE=OPSKIT`（切回 `LOCAL` 即回滚）。

### 9.4 测试

- 投递修复：有跨进程消费者的事件，提交后立即调用消费者；消费者抛异常 → FAILED + 退避；达上限 → DEAD 且 `onDead` 被调（负对照：拆掉修复，消费者零调用）。
- webhook：坏签名 / 过期时间戳 401 且不动业务；同一 `idemKey` 两次只推进一次；未知 `subject.type` 记 WARN 回 200。
- 提现：OPSKIT 模式申请 → 送审请求体（subject / scopes / onBehalfOf / 幂等键）正确；`approval.completed` APPROVED → `status=PAYING`、审批人 = 回传的宿主编号；REJECTED 带原因；无收款账户 → `APPROVED_BLOCKED`；OPSKIT 在审时旧 `/audit` 409；LOCAL 模式既有提现用例全绿不改。

---

## 十、告警开单接 OpsKit：八处联动的对应（待拍板）

> 2026-09-28 · 报障开单已切 OPSKIT（生产开关 `SHAREHUB_FLOW_CS_TICKET_ENGINE=OPSKIT`）；告警开单**暂未切**。
> 原因：告警引擎对「处置单」不只是开单，还有 7 处联动直接调本地工单服务（`AlarmEngineImpl` · `WorkOrderReviewListener`）。
> 只把开单切过去，其余联动会**静默失效**（调本地服务查不到 OpsKit 单号 → 返回空 → 什么都不做，不报错）。

### 10.1 八处联动与对应

| # | 联动（现在调本地工单） | 何时发生 | OpsKit 侧对应 | 同步 / 异步 |
|:-:|---|---|---|:-:|
| 1 | `openOrAttach` 开单 / 并入 | 告警处置为「工单」 | `POST /tickets`（应用凭证，`dedupeKey` = 现有并单键 `mergeKey`）；单号回填 `disposition_ref` | 异步（outbox） |
| 2 | `raisePriority` 提优先级 | 告警影响升级 | `PATCH /tickets/{no}` `{priority}` + 内部评论「告警 X 影响升级」 | 异步 |
| 3 | `mergeInto` 并单 | 站点级告警取代柜级告警 | `POST /tickets/{no}/merge` `{into, reason}` | 异步 |
| 4 | `annotate` 改挂说明 | 上层告警还没单，下层单改挂上层 | 内部评论 | 异步 |
| 5 | `withdraw` 撤单 | 告警自愈 / 自动修复，且单还没人接 | 迁移 `cancel`（必须写说明：「关联告警已自动恢复」） | 异步 |
| 6 | `markAlarmRecovered` 标记恢复 | 告警自愈，但单已在处理 | 内部评论「关联告警已于 … 自动恢复」 | 异步 |
| 7 | `statusOf` 查单状态 | 5/6 二选一的依据 | **不在告警引擎里同步查**：把「告警已恢复」作为一条命令交给执行器，执行时 `GET /tickets/{no}`：`NEW` → 撤单，其余 → 评论 | 异步 |
| 8 | 完工复核（`WorkOrderReviewListener`） | 工单完工 | `ticket.transitioned` 到 `DONE` 的 webhook → 复核 `disposition_ref` = 该单号的全部告警：全恢复 → 关告警；未恢复 → 内部评论「复核未通过：…」并让告警保持打开 | webhook |

另：处置预览里的「建议受理人」（`woOps.candidates`）在 OPSKIT 模式下改为显示派单策略（由 OpsKit 派），不再算本地候选。

### 10.2 实现形状

- **一个通用命令事件** `OPSKIT_TICKET_COMMAND {ticketNo, command, args}`（与业务同事务落 outbox），命令：`RAISE_PRIORITY` · `MERGE` · `COMMENT` · `ALARM_RECOVERED`（执行时自己查状态决定撤单还是评论）。
  执行器幂等：重复的合并 / 撤单遇到「已合并 / 已结」按成功处理，不无限重投。
- **告警开单与报障同一条路**：`TicketRequestedEvent(bizType=alarm, bizNo=告警号)`；单号未回填前的联动命令排在开单之后（同一 outbox 按序投递；执行时单号仍空则重投）。
- **开关** `sharehub.flow.alarm-ticket.engine=LOCAL|OPSKIT`：告警引擎里 7 处调用改走一个「处置单端口」，LOCAL 实现即现有 `woOps` 调用，行为不变。
- **工单类型**：新建「告警工单」类型（代号 `alarm`，经「运营配置 › 工单类型」或 bootstrap），字段：告警码、站点、机柜；流程沿用 `repair_flow`；优先级沿用告警的四档（URGENT / HIGH / MEDIUM / LOW 映射 P1–P4）。

### 10.3 需要 OpsKit 确认（应用凭证能否做）

1. `PATCH /tickets/{no}` 改优先级；2. `POST /tickets/{no}/merge`；3. 迁移 `cancel`（repair_flow 的作废原定「提出人或办理人」—— 应用自身是否放行）；4. 应用凭证写 INTERNAL 评论。

### 10.4 工作量与测试

约 3 人日。测试按 8 处联动各一条：假 OpsKit 记录收到的命令并断言顺序与内容；完工复核用签名 webhook 驱动（全恢复 → 告警关；未恢复 → 评论 + 告警仍开）；开关 LOCAL 下既有告警用例全绿不改。
