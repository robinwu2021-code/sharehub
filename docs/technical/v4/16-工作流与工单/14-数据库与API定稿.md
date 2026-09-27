# 14 · 数据库与 API 定稿（基于原型逐页核对）

> 所属：[16 工作流与工单](./README.md) · 状态：**待确认**（2026-09-27）
> 用户要求：「基于原型整理数据库、API」。
> 基线：[10](./10-微服务设计.md) 的 DDL 与接口约定 + [11](./11-两个模块-工作流支持工单.md) 的模块拆分（`wf_` / `wo_` 前缀、`/api/workflow/v1` 与 `/api/workorder/v1` 两个基路径）。本篇按 [13 原型](./13-产品原型.html) 的九个页面逐页核对，**新增 / 修改处都标了 🆕 / ✏️**；未提到的沿用 10 / 11。

---

## 一、页面 → 接口 → 表

| 页面（原型页签） | 调用的接口 | 背后的表 |
|---|---|---|
| **P1 待办中心** | `POST /workflow/v1/todos/query`（筛选 chips = `source` 参数：全部 / 审批 / 工单 / 可抢）· `POST /todos/count`（角标）· 抢单 `POST /workorder/v1/work-orders/{no}/grab` | `wf_todo`（资格多行 + 锚点）· 抢单落 `wf_task` + `wo_work_order` |
| **P2a 审批列表** 🆕 | `POST /workflow/v1/approvals/query`（🆕 见 §二.1；筛选条件与参数一一对应）| `wf_approval`（🆕 索引 `idx_list`）· 当前步时限读 `wf_task.due_at_ms` |
| **P2 审批详情** | `GET /approvals/{no}`（含全部轮次、每步任务）· `POST /approvals/{no}/decide` / `withdraw` / `resubmit` · 历史 `GET /subjects/{type}/{no}/history` | `wf_approval` · `wf_approval_round` · `wf_task` · `wf_transition_log` |
| **P3 审核队列** | `POST /approval-tasks/query`（`queue` 参数 + 各队列未领数）· `POST /approval-tasks/{no}/claim` / `release` · 决定走 `POST /approvals/{no}/decide` | `wf_task`（`idx_todo` · `idx_claim_timeout`）· `wf_queue` |
| **P4 工单工作台** | `POST /workorder/v1/work-orders/query`（筛选：类型 / 状态 / 优先级 / 受理方 / 来源 / 锚点）· 统计卡 `POST /stats/summary`（**卡可点 = 带同一组筛选参数跳 query**）· `POST /work-orders/reassign`（批量改派） | `wo_work_order`（`idx_todo / idx_pool / idx_sla / idx_scope*`） |
| **P5 工单详情** | `GET /work-orders/{no}` · `GET /work-orders/{no}/timeline` · `POST /work-orders/{no}/events/{event}`（动作条 = 出参 `actions`）· `/assign` `/escalate` `/merge` `/notes` `/priority` | `wo_work_order` · `wo_note` · 实例与任务经 workflow：`wf_process_instance` · `wf_task` · `wf_transition_log` |
| **P5a 流程配置** 🆕 | `GET /definitions/flows`（🆕 列表含在途数）· `GET /definitions/flows/{code}/versions` · `PUT /definitions/flows/{code}`（保存即发布新版本）· `POST /definitions/flows/{code}/disable` 🆕 | `wf_definition`（✏️ 加 `channel`）· `wf_definition_status` 🆕 · 在途数来自 `wf_approval`（`status=PENDING` 按 `flow_code` 计数） |
| **P6 运营配置** | `PUT/GET /sla-rules` · `PUT/GET /calendars/{code}` · `PUT/GET /queues/{code}` | `wf_sla_rule` · `wf_calendar` · `wf_queue` |
| **P7 接入管理** | `GET/PUT /systems`（轮换凭证 `POST /systems/{code}/rotate-credential`）· `PUT /webhooks/subscriptions` · `GET /webhooks/deliveries` · `POST /webhooks/deliveries/{id}/redeliver` · `GET /definitions/{kind}` 版本列表 | `wf_system` · `wf_webhook_subscription` · `wf_outbox` · `wf_webhook_delivery` · `wf_definition` |

工作台 / 详情 / 待办 / 审批列表的每个筛选控件都必须对应一个查询参数 —— 页面不做「取一页再在页内过滤」（[拿分页当查询](../..) 与提现 `payeeNo` 的教训）。

---

## 二、接口增量（相对 10 §五）

### 2.1 🆕 `POST /workflow/v1/approvals/query` —— 审批列表

```json
{ "status": ["PENDING"], "flowCode": null, "submittedBy": null,
  "submittedFrom": 1758844800000, "submittedTo": null,
  "keyword": "CT0042",                       // 匹配 审批单号 / subject_no / display.title
  "scope": { "slots": { "1": ["ST0311"] } },
  "page": 1, "size": 20 }
```

返回行：`approvalNo · flowCode · flowName · subject{type,no,display} · status · currentStep · currentRound · submittedBy · submittedAtMs · waitingMs`（待审 = 当前步任务创建至今；已结 = `finishedAt - submittedAt`）· `stepOverdue`（当前步任务 `due_at_ms` 已过 → 列表标黄）。
取代 10 里的 `GET /approvals?subjectType=&subjectNo=&status=`（按对象查保留，作为 `subject` 精确参数并入本接口）。

### 2.2 🆕 流程配置四个接口

| 接口 | 说明 |
|---|---|
| `GET /definitions/flows` | 列表：`code · name · currentVersion · steps 摘要 · pendingCount`（在途待审数）· `enabled` |
| `GET /definitions/flows/{code}/versions` | 版本历史：`version · registeredAtMs · registeredBy · channel(API/CONSOLE) · note` |
| `PUT /definitions/flows/{code}` ✏️ | 10 已有；补两点：请求体加 `note`（版本说明，进版本历史）；**响应加 `pendingOnOldVersions`**（在途且非本版本的单数，界面据此显示「在途 N 单仍按旧版走完」） |
| `POST /definitions/flows/{code}/disable` / `enable` 🆕 | 停用 = 不再接受新提交，在途走完；写 `wf_definition_status` |

校验失败（422）逐条返回：`{ "errors": [{ "path": "steps[1].when", "error": "flow.def.unknown_attr_op" }] }` —— 原型「保存被拒并逐条说明」的接口形态。

### 2.3 ✏️ 定稿口径（原型核出来的三处）

| 处 | 定稿 |
|---|---|
| 待办 `source` 筛选 | `TODO_ALL / APPROVAL / WORK_ORDER / POOL` 四值，与 P1 的 chips 一一对应；`POOL` 只返回「我够资格抢」的 |
| 统计卡可点 | `stats/summary` 的每个数字都附 `queryParams`（一组可直接回填给 query 接口的参数），前端不自己拼 —— 保证「卡与列表口径一致」可测 |
| 工单详情动作条 | `GET /work-orders/{no}` 出参含 `actions: [{event, label, needReason, style}]`，由平台按状态图 + 操作人算好；前端不维护迁移表（延续既有卡口思路） |

---

## 三、数据库增量（相对 10 §三，前缀按 11 改 `wf_` / `wo_`）

### 3.1 ✏️ `wf_definition` 加一列

```sql
ALTER TABLE wf_definition ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'API';   -- API / CONSOLE：这版是代码登记的还是界面发布的
ALTER TABLE wf_definition ADD COLUMN note VARCHAR(512) NULL;                        -- 版本说明（界面发布时填）
```

`registered_by` 已有：API 登记 = 系统代号；界面发布 = 操作人编号。

### 3.2 🆕 `wf_definition_status` —— 按代号的启停（版本行只增不改，启停不能落在版本行上）

```sql
CREATE TABLE wf_definition_status (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  system_code VARCHAR(32) NOT NULL,
  kind VARCHAR(16) NOT NULL, code VARCHAR(64) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(64) NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_code (system_code, kind, code)
);
```

### 3.3 🆕 `wf_approval` 补审批列表的索引

```sql
ALTER TABLE wf_approval ADD KEY idx_list (system_code, status, flow_code, submitted_at_ms);
ALTER TABLE wf_approval ADD KEY idx_submitter (system_code, submitted_by, submitted_at_ms);
```

`idx_subject`（按对象）、`idx_pending_due`（超时扫描）沿用。

### 3.4 其余确认项（核对原型后**不需要**改表的）

| 原型元素 | 为什么现有结构够 |
|---|---|
| P2a「已等待」与超时标黄 | 待审 = 当前步 `wf_task.created`→now；步骤时限在 `wf_task.due_at_ms`（10 已有） |
| P5a 在途单数 | `wf_approval` 按 `flow_code + status=PENDING` 计数，`idx_list` 覆盖 |
| P7 投递重试次数 / 重投 | `wf_outbox.attempts` + `wf_webhook_delivery` 每次一行（10 已有） |
| P4 SLA 四色 | `wo_work_order.sla_state`（ON_TRACK / PAUSED / BREACHED / MET）+ `sla_due_at_ms` 差值分黄 |
| P1 资格与范围 | `wf_todo` 资格多行 + 三个锚点列（10 已有） |

---

## 四、契约真源

| 项 | 定 |
|---|---|
| OpenAPI | `flow-svc` 生成 `openapi.yaml` 落盘入库（10 §五）；本篇 §二 的增量并入 |
| ops-web 侧 | 前端契约方法名与本篇接口一一对应（见 [15 ops-web 落地](./15-opsweb落地.md)）；mock 按同一契约实现，状态机在 mock 层强制 |
| 校验 | 「筛选控件 ↔ 查询参数」进表单字段卡口的同类检查：列表页的每个筛选项都要能在契约参数里找到同名字段 |
