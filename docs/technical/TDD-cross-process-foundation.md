# TDD-cross-process-foundation（B7 · 跨进程地基）

状态：**已实现**（2026-09-23）
关联需求：[v4/02 §3.3 · §3.4](./v4/02-服务与模块.md) · [v4/08 S2](./v4/08-落地路线.md) · [v4/14 B7](./v4/14-执行任务清单.md)
创建日期：2026-09-23
定位：**底座/框架**（不含业务语义）—— 符合 2026-09-23「底层功能代码与脚手架优先」的方向

---

## 1. 需求摘要

[v4/08](./v4/08-落地路线.md) S2：「Outbox 跨进程投递 + 消费去重（`sys_event_consumed`）；服务凭证过滤器；`traceparent` 透传」。
交付判据：**两进程冒烟 —— 事件至少一次送达且不重复处理。**

这是 S5（协议对接服务独立部署）与 S6（支付接入）的前置：在此之前，`sharehub-app` 与
`sharehub-app-gateway` 之间没有任何可靠的通信地基。

---

## 2. 当前架构分析（实测 2026-09-23）

| 件 | 现状 | 缺口 |
|---|---|---|
| **Outbox 写入** | ✅ `OutboxEventBus`：同事务写 `sys_outbox` + `afterCommit` 投递；回滚不留事件。注释把「为什么必须等提交后」「为什么写库而不只延迟」写得很清楚 | — |
| **Outbox 投递** | ⚠️ 只有**进程内** `ApplicationEventPublisher.publishEvent` | 没有跨进程投递 |
| **失败重投** | ❌ 投递失败写 `status=FAILED` + `nextRetryAt`，注释说「交给轮询器重投」—— **但轮询器不存在**，FAILED 的事件永远停在那里 | 没有 `OutboxDispatcher` |
| **消费去重** | ❌ `sys_event_consumed` 表**没建**，也没有去重代码 | 至少一次投递 → 必然重复消费 |
| **服务凭证** | ❌ 无 `InternalClient` / `ServiceLocator` / `InternalTokenFilter`，`X-Internal-Token` 在代码里零出现 | 跨进程调用没有任何身份 |
| **链路追踪** | ❌ `traceparent` 零出现 | 两进程的日志无法串起来 |
| **远程 Port** | ❌ `DeviceCommandPort` 没有远程实现 | S5 才需要，不在本次 |

**可移植件**：ai-shop 的 `shop-base/svc/{InternalClient, ServiceLocator, ServiceName}` 已经成熟，
且注释里写清了四条规矩（不经 nginx、共享密钥不是用户令牌、不记 body、HTTP/1.1）与**三种失败要分开**
（没配地址 / 连不上 / 对方返回错误）。按 [ADR-024](./ADR/ADR-024-共用服务暂由ai-shop托管-组件并入框架.md)
它属于将来下沉到框架的中性件，**本次原样移植并登记待下沉**，不重新发明。

---

## 3. 方案设计

### 3.1 四件事

| # | 件 | 放哪 | 说明 |
|---|---|---|---|
| **① 服务凭证客户端** | `sharehub-common` 的 `svc/`：`InternalClient` · `ServiceLocator` · `ServiceName` | 移植 ai-shop，改配置前缀为 `sharehub.services.*`；登记 `known-pending-shared.txt` |
| **② 服务凭证过滤器** | `sharehub-common` 的 `auth/`：`InternalTokenFilter` | 校验 `X-Internal-Token`；**没配密钥就一律拒绝**，不是「没配就不校验」 |
| **③ Outbox 跨进程投递 + 重投** | `sharehub-common` 的 `event/`：`OutboxDispatcher` · `OutboxConsumer` SPI | 轮询 `PENDING`/到期 `FAILED` → 本地消费者 + 跨进程 HTTP；指数退避；上限后转 `DEAD` |
| **④ 消费去重 + 链路透传** | `sharehub-common`：`EventIdempotency`（表 `sys_event_consumed`）· `TraceContext` + RestClient/HttpClient 拦截 | 去重键 `(event_no, handler)`；`traceparent` 进出站透传 |

### 3.2 三个设计决定

**① 「至少一次」是刻意的，所以去重是必需品而不是可选项**

Outbox + 重投必然产生重复投递（投递成功但回写 `SENT` 前宕机 → 下轮重投）。
把它做成「恰好一次」需要分布式事务，代价远大于收益。
所以**约定是「至少一次 + 消费端去重」**，去重表 `sys_event_consumed(event_no, handler)` 唯一键即闸门 ——
**以 DB 唯一约束为权威**，不靠应用层 check-then-act（并发下必漏）。

**② 没配密钥 = 全部拒绝，不是全部放行**

`InternalClient` 与 `InternalTokenFilter` 两侧都是：密钥为空 → 拒绝。
理由与 B1 的 fail-closed 一致 —— 「没配就不校验」的表现是**内部口对任何人开放且没有任何症状**。

**③ 退避与死信：失败不能无限重投，也不能悄悄消失**

`retryCount` 到上限后置 `DEAD` 并告警，而不是继续重投或直接删除。
指数退避 1m → 5m → 30m → 2h（上限 6 次）。**没有死信状态的重投队列，最终会变成一个无人看的失败堆。**

### 3.3 配置项（零硬编码）

| 键 | 默认 | 说明 |
|---|---|---|
| `sharehub.services.internal-token` | 空 | 共享密钥。**空 = 内部调用与内部端点全部拒绝** |
| `sharehub.services.targets.<名>` | 空 | 各服务基址（`SHAREHUB` / `GATEWAY`），走内网不经 nginx |
| `sharehub.outbox.dispatch.enabled` | `true` | 轮询开关（单测可关） |
| `sharehub.outbox.dispatch.batch-size` | `200` | 每轮条数 |
| `sharehub.outbox.dispatch.max-attempts` | `6` | 超过即 `DEAD` |

### 3.4 不在本次范围

| 项 | 去向 |
|---|---|
| `DeviceCommandPort` 等**具体业务 Port** 的远程实现 | S5（业务代码，等业务模块梳理） |
| 定时驱动 `outbox-dispatch`（由共用调度器回调） | H1/H4，需 ai-shop 的 M0；本次用**进程内定时**兜底，接入后切换 |
| 下沉到 `neargo-common-internal` / `-store` | 框架 2.x（冻结中），本次登记 `known-pending-shared.txt` |

---

## 4. 测试策略

| # | 场景 | 断言 |
|---|---|---|
| 1 | 未配密钥时调 `/internal/**` | **拒绝**（不是放行） |
| 2 | 密钥错误 | 401 |
| 3 | 密钥正确 | 放行 |
| 4 | `InternalClient` 没配目标地址 | 返回 `NOT_CONFIGURED`，**与「连不上」区分** |
| 5 | 同一事件投递两次 | 消费方只处理一次（去重表唯一键） |
| 6 | 并发投递同一事件 | 只有一个成功，另一个按幂等命中既有 |
| 7 | 投递失败 | 转 `FAILED` + `nextRetryAt`，下轮被捞起重投 |
| 8 | 重投到上限 | 转 `DEAD`，不再重投 |
| 9 | `traceparent` | 入站带则沿用、不带则生成；出站必带 |
| 10 | 事务回滚 | 不留事件（已有 `OutboxEventTest` 覆盖，回归） |

**反向对照**（B1/B2/B3 的教训）：每条防护都要验「去掉防护后测试会红」——
尤其第 5 条，去重若失效而测试仍绿，说明夹具根本没触发重复。

---

## 5. 风险

| 风险 | 对策 |
|---|---|
| **并行会话冲突**：另一会话正在重构 `AgtAgent` / `LocVenue` 等，且起草了 `ADR-026 取消租户概念` | 本次只新增文件 + 动 `OutboxEventBus` 一处；**不碰实体与业务 service**。`sys_outbox.tenant_id` 若因 ADR-026 消失，影响面是一列，不影响本方案骨架 |
| 轮询器在多副本下重复投递 | 本次单副本；多副本需分布式锁（ShedLock，随 H1 任务目标件一起） |
| 进程内定时是临时方案 | 明确标注，H4 接入共用调度器后切换；**生产禁止**进程内模式（与 v4/07 一致） |

---

## 6. 实现任务

- [ ] T1 `ServiceName` · `ServiceLocator` · `InternalClient`（移植 ai-shop，改前缀）
- [ ] T2 `InternalTokenFilter` + 安全链放行 `/internal/**`
- [ ] T3 `sys_event_consumed` 迁移 + `EventIdempotency`
- [ ] T4 `OutboxDispatcher` + `OutboxConsumer` SPI + 退避/死信
- [ ] T5 `TraceContext` + 进出站透传
- [ ] T6 测试 10 条 + 反向对照 + 全量回归
- [ ] T7 登记 `known-pending-shared.txt`；回填 v4/08 S2、v4/14 B7、实现状态总表

---

确认记录：2026-09-23 用户确认「进程内定时兜底」+「只做透传 + 日志关联」两项推荐。

## 7. 实现记录（2026-09-23）

| 任务 | 落点 |
|---|---|
| T1 | `svc/{ServiceName, ServiceLocator, InternalClient}`（移植 ai-shop，前缀改 `sharehub.services.*`） |
| T2 | `svc/InternalTokenFilter` + 安全链接线 |
| T3 | `V43__sys_event_consumed.sql` + `common/event/dedup/{SysEventConsumed, Mapper, EventIdempotency}` |
| T4 | `common/event/{OutboxConsumer, OutboxDispatcher, OutboxDispatchScheduler}` |
| T5 | `trace/{TraceContext, TraceFilter}` |
| T6 | `CrossProcessFoundationTest`（9 例）+ 两次反向对照 |
| T7 | `known-inprocess-schedules.txt` · `known-pending-shared.txt` |

### 7.1 一刀切差点打断线上调用方

最初把安全链写成 `/internal/** → hasRole("INTERNAL")`。跑全量时冒出 401，查下来
**ops-web 今天就在用员工令牌调 `/internal/gw/vendors` 与 `/internal/user/credit/blacklist`**，
测试也在调 `/internal/trade/orders/*/return`。

`/internal/` 这个前缀下混着两类东西：真正的跨进程调用，和一批**历史业务端点**
（按 [v4/04 §三](./v4/04-接口.md) 本就计划改名到 `/api/platform/...`）。
改法：凭证只圈 `/internal/events/**` 与 `/internal/ping`，前缀可配，迁一批加一条。
并加了一条测试专门守这个边界 —— 它记录的是一次**事故预演**，不是一条普通断言。

> 顺带：为测这条边界，给过滤器加了 `public appliesTo(String)`，而不是把
> `shouldNotFilter` 放宽到 public 或在测试里 mock 一个 `HttpServletRequest`。
> 「圈了哪些路径」是本类最该被测住的决定，它值得一个真正的公开方法。

### 7.2 卡口的白名单又过期了一次

加了 `svc/` 与 `trace/` 两个包之后，arch-guard 的 G4 立刻报
「`InternalClient` 依赖业务包」—— 因为它的 allow 清单**硬编码**了 `common`/`auth` 两个包。

这与 B2 修掉的 `EXTRA_SRC` 是**同一种失败模式**（硬编码清单会过期），只是这次表现为
**误报**而非漏报。误报同样有害：它会逼下一个人去开豁免，而豁免一旦开口，真违规就能混进来。
所以没有往清单里补两个名字了事，而是改成**从模块自己的目录结构推导**。
反向对照：放一个真的依赖业务包的探针进去 → 仍被抓到（退出码 1）。

### 7.3 全量测试的状态（如实记）

`mvn -o -B test`：**162 个测试，6 个失败**。这 6 个**不是本次引入的** ——
先跑了一次不含本次改动的基线，失败的**测试名完全一致**（基线当时是 11 个，
期间另一会话在修，数字在变）。它们来自共享开发库 `pb_core` 的状态与并行会话的在途重构。

**这恰恰是 B2 尚未完成的那一项（H2 测试隔离）的代价**：两个会话共用一个开发库，
谁都无法得到可信的全量结果。本次 9 个新测试稳定通过，`arch-guard --strict` 绿。
