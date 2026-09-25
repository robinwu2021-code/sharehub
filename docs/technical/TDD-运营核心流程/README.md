# TDD · 运营核心流程 —— 开发设计总览

> 2026-09-25 · 状态：**设计稿，待评审后开工**
> 目标：把已定稿的四份方案落成**可以直接照着写代码**的设计：库表、领域对象、API、Controller、Service、Mapper、核心逻辑、测试。
>
> | 上游（需求与方案，已定稿） | 本目录对应章节 |
> |---|---|
> | [运营核心流程](../../requirements/运营核心流程.md) · [功能矩阵](../../requirements/运营核心流程-功能矩阵.md) · [分级矩阵](../../requirements/功能清单-分级矩阵.md) | 全部 |
> | [方案-文件上传与COS存储](../方案-文件上传与COS存储.md) | [01-文件上传](./01-文件上传.md) |
> | 运营核心流程 §十.1 合同走审批 | [02-合同审批](./02-合同审批.md) |
> | 运营核心流程 §十.2 站点状态合并 | [03-站点状态机](./03-站点状态机.md) |
> | 功能清单 §四 上线门禁 · S-RENT-01 停借保还 | [04-设备状态与上线门禁](./04-设备状态与上线门禁.md) |
> | [方案-告警码与自动开工单 v2](../方案-告警码与自动开工单.md) | [05-业务告警](./05-业务告警.md) |
> | 告警方案 §十—§十一 · 功能清单 §七 | [06-工单增强](./06-工单增强.md) |
> | 流程 §四 联动 E1–E18 · v4/07 | [07-联动与定时任务](./07-联动与定时任务.md) |
> | [方案-ops-web运营流程页面完善](../方案-ops-web运营流程页面完善.md) | [08-前端实现](./08-前端实现.md) |
> | — | [09-测试与实施计划](./09-测试与实施计划.md) |

> **接口路径以 [运营核心流程-接口设计](../../api/运营核心流程-接口设计.md) 为准**（2026-09-25 按整体架构裁决：文件改走 `/api/platform/files`、合同审批合并为 `/audit`、设备新端点用目标名 `/api/ops/devices/{deviceNo}/…`、设备事件批量带 `eventId`、定时任务按目标任务名命名）。

---

## 一、范围与分期

本设计**只覆盖分级矩阵新增的 L0 流程能力**及其必需的地基，L1/L2 只留接口位：

| 章 | L0 必做 | 留位（L1/L2，本设计给出接口形状，不展开实现） |
|---|---|---|
| 01 文件上传 | `sys_file` · 上传 / 下载 / 绑定 · 本地与 COS 两个适配器 · 合同签署件接入 | 缩略图、秒传、清理对账任务、PII 独立库迁移 |
| 02 合同审批 | 六态状态机 · 提交 / 撤回 / 通过 / 驳回 / 签署归档 · 生效与到期定时迁移 | 条件加签、补充协议版本链、提前终止审批、到期提醒 |
| 03 站点状态机 | 五态状态机 · 暂停 / 恢复 · 首台上线自动营业 · 运维责任人 · 状态日志 | 撤场流程与关闭清单、门店生命周期只读看板 |
| 04 设备与门禁 | 机柜状态机 · 上线门禁 · 试借还 · 保护动作引用计数 · **停借保还** | 入库质检、调拨签收回写 |
| 05 业务告警 | 信号字典 · 业务码配置 · 判定引擎 · 影响评估 · 根因路由 · 自愈 / 开单 / 恢复关闭 · L0 十个码 | 窗口计数、层级取代、待办处置、周期指标码 |
| 06 工单增强 | 类型 × 优先级 · 自动撤单 · 合并挂靠 · 完工必填与照片 · 完工复核 · 按责任人派单 | SLA 升级与接管、巡检自动生成 |

---

## 二、代码归属

沿用现有模块与包，不借本次做目录重构（v4/12 的层级化目录另行推进）。

| 能力 | Maven 模块 | 包（`ai.neargo.sharehub.` 之后） | 表前缀 |
|---|---|---|---|
| 文件 | `sharehub-svc-platform` | `platform.file` | `sys_file` |
| 合同、站点、点位 | `sharehub-svc-platform` | `loc`（状态机与服务在主包；日志表实体在 `loc.ext`） | `loc_*` |
| 机柜、充电宝、保护动作、试借还 | `sharehub-svc-core` | `dev` | `dev_cabinet` · `dev_slot` · `dev_protection` · `dev_trial_rent` |
| 借出校验 | `sharehub-svc-core` | `trade` | — |
| 业务告警、信号字典、告警待办 | `sharehub-svc-ops` | `alarm` | `dev_alarm*`（沿用前缀，归 ops） |
| 工单 | `sharehub-svc-ops` | `wo`（新增能力放 `wo.ext`，遵守「主包不知道 ext」） | `wo_*` |
| 跨模块端口、事件、DTO | `sharehub-api` | `api.{core,platform,ops}.{port,event,dto}` | — |
| Controller | `sharehub-app` | `portal.{platform,ops,core}` | — |

**依赖方向（必须守住，ArchUnit `noCyclesBetweenDomains` 会拦）**：

```
                 sharehub-api（端口 + 事件 + DTO）
                 ▲        ▲            ▲
   platform: loc ─┘  core: dev/trade ─┘  ops: alarm ──▶ wo ──▶ （不反向）
                                              │
                                              └──▶ cs
  同模块内：alarm 可依赖 wo 与 cs；wo 与 cs 永不 import alarm（完工复核走事件回调）
  跨模块：一律经 sharehub-api 的 Port 读、经事件写
```

`scripts/module-graph.py` 的表归属不需要改：`sys_file` 归 platform（`sys_*`），`dev_protection` / `dev_trial_rent` 归 core（与 `dev_cabinet` 同组，**需在映射里补这两个表名**），`dev_alarm_*` 归 ops。

---

## 三、全局编码约定（本设计所有章节遵守）

摘自现状盘点，写代码前对照：

| 项 | 约定 | 依据 / 卡口 |
|---|---|---|
| 实体 | `@Data @EqualsAndHashCode(callSuper=true) @TableName("x") class X extends BaseEntity`；审计四列**不手填**（`AuditMetaObjectHandler`）；追加型日志表不继承 `BaseEntity`，只 `id/created_at/created_by` | `common/BaseEntity.java` |
| 业务号 | `IdGenerator.next(BizKey.X)`；**新前缀先登记 `common/BizKey.java`**，不得与既有前缀过近 | `BizKey` 注释 |
| tenant_id | 继承 `BaseEntity` 的表必须有 `tenant_id` 列，写入常量 `MAIN` | ADR-026 |
| Mapper | `BaseMapper<E>` + `LambdaQueryWrapper`；无 XML；实体未映射的列才用字符串 `UpdateWrapper` | `MybatisPlusConfig` |
| Service | 接口 + `impl`，构造器注入；有业务规则的聚合手写，不继承 `AbstractCrudService`；`@Transactional` 在 impl 方法上 | `ArchitectureTest` |
| 错误 | 参数与非法迁移 → `IllegalArgumentException`（400）；**业务拒绝 → `ServerException.of(ErrorCode.CONFLICT, "…")`（409）**；禁止新增裸 `IllegalStateException` | `GlobalExceptionHandler` · `BizRejectionNotRawIllegalStateTest` |
| 状态机 | `XxxStatus` 枚举（含宽松 `of(String)`）+ `@Component XxxStateMachine`：`Map<String 事件, Map<Status, Status>> TRANSITIONS` + `String next(String from, String event)`；实体字段仍是 `String`；**设状态一律 `sm.next(...)`，比较一律 `XxxStatus.X.name()`** | `StateMachineUsesEnumTest` · `BareStatusLiteralRatchetTest` |
| 两端一致 | 每个后端状态机在 ops-web `lib/types/*.ts` 有同名状态类型与 `X_TRANSITIONS`；后端有而前端无按钮的边登记 `known-missing-ui-transitions.txt` | `StateMachineEdgeAcrossEndsTest` · `StatusVocabularyAcrossEndsTest` |
| DTO | `public final class XxxDtos` 内嵌 record；响应用名词、请求用 `…Req`；record 加字段**追加在末尾**；**新端点禁止实体做请求体** | `EntityRequestBodyRatchetTest` |
| 归属锚点 | `site_no` / `agent_no` / `location_no` 一律服务端派生，不收请求值 | mass-assignment 加固 |
| Controller | `sharehub-app/portal/*`；动作一律 `POST /{no}/<动词>`；**无 `@DeleteMapping`**；`@PreAuthorize("@perm.can('m:r:a')")`；不碰 Mapper、不写 `@Transactional` | `ArchitectureTest.controllersDoNotTouchMappersDirectly` |
| 新权限码 | `scripts/perm-catalog-names.tsv` 加行 → `gen-perm-catalog.py` 生成迁移 → 同一提交登记 `docs/requirements/功能权限清单.md` 与 ops-web `UI_PERM_MAP` | `PermCatalogCoverageTest` · `perm-ssot-align --strict` |
| 迁移 | `V<n>__snake.sql`，`SET NAMES utf8mb4;` 开头，头部注释写「为什么」；`ADD COLUMN IF NOT EXISTS`；无外键；`DATETIME(3)`；**枚举列 COMMENT 写全合法值 `VAL 中文 / …`**；`MODIFY` 要重述完整定义；不改已应用的迁移 | `DdlStatusVocabularyCommentTest` · `StoredValueInVocabularyTest` · `SchemaCommentEncodingTest` |
| 数据范围 | 带 `agent_no/site_no/region_id` 的新表**必须**在 `DataScopeRegistration` 登记全部维度，或进台账（只减不增） | `DataScopeCoverageRatchetTest` |
| 事件 | 事件类放 `sharehub-api/.../event`，实现 `DomainEvent`；发布方在业务事务内 `events.publish(...)`；监听方 `@EventListener @Transactional(REQUIRES_NEW)`，放消费域 `port/`；**必须幂等** | `OutboxEventBus` |
| 定时 | 禁 `@Scheduled`。系统 cron → `POST http://127.0.0.1:8082/internal/<域>/<x>/tick`；端点校验回环地址、`SecurityConfig` 单独 permitAll、无变更时 `AuditNoop.mark(req)`、**幂等** | `known-inprocess-schedules.txt` · 调价 tick 先例 |
| 审计 | `/api/**` 写操作自动进 `iam_audit_log`；字段级变更 `AuditChanges.record("中文名", before, after)`（改字段前取快照） | `AuditTrailInterceptor` |
| 日志 | 异常带业务键；参数化；WARN/ERROR 要能回答「谁该做什么」 | `LoggingConventionTest` |

> ⚠️ **事件的已知缺陷**：进程内监听失败后，Outbox 轮询器当前**不会重投**（找不到 `OutboxConsumer` 就直接标 SENT）。
> 修复已另立任务。在它落地前，本设计里**不能丢**的联动都配了对账兜底（见 [07](./07-联动与定时任务.md) §三）。

---

## 四、迁移清单（版本号落地时取当时最新 + 1）

多会话并行开发，版本号以**合入时**的最新号为准（参见部署记忆里的 V47/V48 乱序事故）。本设计用 `M1…M8` 占位，**顺序不可调换**：

| 占位 | 内容 | 章 |
|---|---|---|
| M1 | `sys_file` · `loc_contract_attach.file_no` | 01 |
| M2 | `loc_contract` 扩列 + 状态词表扩为六态 · `loc_contract_log` | 02 |
| M3 | `loc_site` 扩列 + 状态词表扩为五态 · `loc_site_lifecycle_log` 语义转为状态日志 | 03 |
| M4 | `dev_cabinet` 状态词表加 `IN_TRANSIT` + 扩列 · `dev_protection` · `dev_trial_rent` | 04 |
| M5 | `dev_alarm_code` / `dev_alarm` 扩列 · `dev_alarm_route` · `dev_event_code` · `dev_alarm_todo` · `dev_alarm_site_profile` · 种子（信号字典 + L0 业务码 + 路由） | 05 |
| M6 | `wo_order` 扩列与词表补齐 · `wo_sla_rule` 加 `priority` 与唯一键 · `wo_dispatch.strategy` 加 `OWNER` | 06 |
| M7 | 权限码目录（`gen-perm-catalog.py` 生成，仅合同三码；告警细码由在途 V89 相关工作提供） | 02 |
| M8 | 存量归一：告警码 `E001–E004` → 信号 · `loc_site_lifecycle` 阶段 → 站点状态 | 03 · 05 |

---

## 五、错误码约定

现状只有通用错误码（400 / 403 / 409 / 500），业务文案直接写中文。本设计**不引入**新的错误码体系（v4/09 的 `BizException` 另行推进），统一为：

| 场景 | 抛出 | HTTP | 文案规范 |
|---|---|:-:|---|
| 参数不合法、缺必填 | `IllegalArgumentException("xxx 必填")` | 400 | 说清哪个字段、合法范围 |
| 非法状态迁移 | 状态机 `next()` 抛 `IllegalArgumentException` | 400 | 状态机统一格式 `…非法迁移: FROM --EVENT--> ?` |
| 业务前置条件不满足（门禁未过、有未结工单） | `ServerException.of(ErrorCode.CONFLICT, "…")` | 409 | **说清缺什么、去哪补**，如「站点无生效合同，请先在进场合同中完成签署」 |
| 对象不存在 / 无权 | `IllegalArgumentException("xxx 不存在")` | 400 | 越权与不存在同一文案（不泄露存在性） |

门禁类拒绝统一返回 409。`ServerException` 的响应体只有 `code/message`、带不了清单，所以**每个门禁都另有一个只读的 `GET …/gate` 端点**返回逐项结果（见 04 §五）：前端先调 `gate` 渲染 `GateChecklist`、全部通过才启用动作按钮；动作端点在服务端**重新校验一遍**（防并发与绕过前端），不通过时 409 的文案列出第一项未通过的原因。
