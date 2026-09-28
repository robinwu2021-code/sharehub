# OpsKit 性能基线（21/07 O4-5）

复跑：`scripts/verify.sh -pl process/process-core test -Dtest=ProcessPerfBaselineTest -Dopskit.perf=true`（约 2 分钟；
`-Dopskit.perf.tasks=` 改数据量，`-Dopskit.perf.flush=2` 关逐次落盘作诊断对照）。报告写 `process/process-core/target/perf/baseline.md`，
含待办 SQL 的 `ANALYZE FORMAT=JSON`。默认 verify 跳过它。

## 2026-09-28 · 本机（colima 容器，MariaDB 11.4，缓冲池 512M，`innodb_flush_log_at_trx_commit=1`）

数据：任务 1,000,000（未结 5%）· 候选 2,000,000 · 实例 250,000（一半带范围标签）· 成员 5000 · 组 40 · 范围值 200。
采样 200 次随机成员（1 组 · 3 个范围值），**平均每人待办 482 条**（组内未结任务多，属于压力口径）。

| 操作 | P50 ms | P95 ms | P99 ms | 目标 P95 |
|---|--:|--:|--:|--:|
| 待办列表（首页 20 条 + 总数） | 58.5 | 84.9 | 112.7 | 100 ✅ |
| 待办角标（四个页签） | 16.7 | 19.5 | 26.2 | 100 ✅ |
| 迁移（submit：建办理任务 + 候选 + 流转日志 + 事件） | 6.8 | 8.9 | 13.4 | 20 ✅ |
| 对照：空库上的同一迁移 | 6.5 | 8.3 | 9.9 | — |
| 对照：`SELECT 1` 往返 | 0.3 | 0.4 | 0.4 | — |

## 改前 → 改后（同一基线）

| | 改前 | 改后 |
|---|--:|--:|
| 待办列表 P95，10 万任务 | 489 ms | 17 ms |
| 待办列表 P95，100 万任务 | 未测（10 万已超标 5 倍） | 85 ms |

**原因**：待办 SQL 从「空间内全部未结任务」出发，逐行 `EXISTS` 判候选；候选里的「参与者角色」一支又是逐行关联子查询（10 万任务时单这一支 150 ms）。
**改法**（`QueryDao.mineFrom`）：先从我这一侧取「我的任务集合」再回表 —— 三路各走一条索引：候选键命中（`idx_mine`）· 我担任的参与者角色
（`idx_principal` → 新增 `proc_task_candidate.idx_inst_key`，迁移 `V2__proc_candidate_instance_index`）· 在我名下（`idx_assignee`）。
来源（PROCESS / TICKET）随候选行带出，不再逐行子查询；总数外包一层派生表（MariaDB 对 COUNT 直接接 UNION 派生表选了更慢的计划）。
口径逐条不变：候选行在任务被人领走后仍开放，所以候选一路在外层仍要求任务 OPEN、名下一路要求非 OPEN。
`participant:ROLE` 候选只可能来自 FIELD 字段值，此前没有用例 —— 已补 `participantRoleCandidatesReachWhoeverHoldsTheRole` 并做负对照。

## 读数时要知道的

- **迁移的绝对值受环境支配**：每次迁移约 11 条语句、**只提交一次**；耗时 ≈ 语句数 × 往返 + 一次提交落盘。本机容器虚拟机的 fsync 很慢且抖动大
  （同一代码多次跑 P95 在 9–45 ms 间摆动；`flush=2` 时稳定在 ~16 ms 以内），生产同机部署的往返与落盘都快得多。
  所以基线对迁移守的是「百万数据下不比空库慢」（P95 ≤ 空库 × 1.5 + 5 ms），不在这台机器上断言绝对值。
- 待办列表里最贵的是**总数**（每次先跑、冷页多）；首页 20 条本身约 20 ms。前端若只要「有没有更多」，可改为多取一条而不数总数。
- 平均每人 482 条待办是压力口径（40 个组平分 5 万未结任务）；真实租户每人几十条时，列表在 10 ms 量级。
