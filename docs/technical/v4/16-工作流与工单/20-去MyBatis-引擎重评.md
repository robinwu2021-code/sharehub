# 20 · 去 MyBatis：引擎重评与 JdbcClient 方案

> 所属：[16 工作流与工单](./README.md) · 状态：**待确认**（2026-09-27）
> 用户要求：「移除 mybatis，用 jdbc client 或其他更优化的方案」。
> 依据：[18 技术栈](./18-技术栈.md) · [10 §四 引擎评估](./10-微服务设计.md) · [03 §六 定义是数据](./03-设计方案.md)。
> 按 v4/11 的约定只用表格、不出图。每条事实的核法见 §七。
> **后续**：用户随即要求「暂不做 AOT，重梳设计方案」，T7 按「移除 mybatis」的指示视为采纳，已落进 [21](./21-设计方案-重梳/README.md)（K1 留有异议口）；§5.1 列的对 18 / 16 的改动由 21 统一承接。

---

## 〇、一页结论

| 问题 | 答 |
|---|---|
| 工作流模块里的 MyBatis 从哪来 | **只从 Flowable 来**。我们自己的代码按 D7 本来就是 `JdbcClient`，一行 MyBatis 都没有 |
| 能不能留着 Flowable、只去掉 MyBatis | **不能**。Flowable 的持久化核心 `DbSqlSession` 直接持有 `org.apache.ibatis.session.SqlSession`，MyBatis 是 compile 依赖、非 optional，引擎自带 13 个实体映射 XML（§一） |
| 所以「去 MyBatis」意味着什么 | **换掉 Flowable**。这会推翻 [10 §4.3](./10-微服务设计.md) 里「工作流引擎不用自研」的决定 —— **需要你明确确认（T7）** |
| 有没有不用 MyBatis 的现成引擎 | 核了四个，**没有一个同时满足**「可嵌入 · 定义是数据 · 有人工任务模型 · 支持 MySQL/MariaDB」（§二） |
| 推荐 | **自研轻量引擎（`JdbcClient`）+ db-scheduler 管持久定时**。范围比听上去小得多：我们不要网关、并行、子流程（10 §4.1），而 Flowable 实际替我们干的只有五件事（§三） |
| 净效果 | 删掉 JSON→BPMN 生成器（原方案最大的一块自研）· 删掉 18 的三处咬合点中的两处 · 删掉 native 最大未知数 N3 · 表从 45 张降到十几张 · **GraalVM 从「要验证」变成「大概率顺」**（§四） |
| 范围 | **只限 `flow-*` 模块**。`sharehub-app` 的 MyBatis-Plus（134 个 Mapper、455 个文件）不在本方案内，要动是另一个项目（§六） |

---

## 一、为什么留不住 Flowable

| 核出来的事实 | 出处 |
|---|---|
| `flowable-engine-common:8.0.0` 与 `flowable-engine:8.0.0` 都以 **compile 范围**依赖 `org.mybatis:mybatis`，无 `<optional>` | 两个 pom |
| 持久化会话 `DbSqlSession` 的字段就是 `protected org.apache.ibatis.session.SqlSession sqlSession`，并公开 `getSqlSession()` | `javap` 反查 |
| 引擎 jar 内 13 个实体映射 XML（`mapping/entity/*.xml`），其余服务 jar 各有自己的 | `unzip -l` |
| Flowable 没有 JDBC / JPA 的替代持久化实现 | 同上：`impl/db/` 下 32 个类全围绕 MyBatis 会话 |

**结论**：MyBatis 不是 Flowable 的"可选后端"，是它的地基。要去 MyBatis，只能去 Flowable。

---

## 二、不用 MyBatis 的现成引擎：逐个核

| 候选 | 持久化 | 现状（已核） | 为什么不行 |
|---|---|---|---|
| **nFlow** 11.0.0 | Spring JDBC ✅ | Apache 2.0 · Spring 7.0.7（Boot 4 同代）· 2026-05 仍在发版 | 流程是 **Java 类**（工作流即代码），外部系统无法经 API 登记定义 —— 违反 08 R2「非 Java 业务也要能登记」；**没有人工任务 / 候选组 / 认领模型**，这正是我们最需要的 |
| **DBOS Transact (Java)** | JDBC ✅ | 最新 **1.2.0-m10**（里程碑，未正式发布） | **只支持 PostgreSQL**，而生产是 MariaDB 12.3.2；同样是工作流即代码；未 GA |
| Temporal / Camunda 8 / Conductor | 自带存储 | 见 10 §4.2 | 要运维集群 / 许可非 OSI / 已归档 —— 理由不变 |
| Camunda 7 · Activiti | **MyBatis** ❌ | —— | 与 Flowable 同源，同样绑 MyBatis；Camunda 7 社区版已停维 |
| jBPM / Kogito | JPA/Hibernate ❌ | —— | 换成更重的 ORM，方向相反 |

**结论**：「可嵌入 + 定义是数据 + 人工任务 + MySQL」这个交集里，**去掉 MyBatis 后就是空集**。所以不存在"换一个引擎"这个选项，只有"自研"与"留 Flowable"二选一。

---

## 三、Flowable 实际替我们干了什么 —— 自研要补的就是这些

对照 [18 §2.3](./18-技术栈.md) 的映射表，把"白拿"的逐条拎出来：

| Flowable 白给的 | 我们的真实需求 | 自研规模 |
|---|---|---|
| 流程实例推进 | 状态图上的一步迁移：`UPDATE … SET status=? , version=version+1 WHERE id=? AND status=? AND version=?`，0 行即 409 | 小。状态机门面（S1-1）**本来就要自研**，只差落库 |
| UserTask | `wf_task` 一张表：待办 / 已领 / 已完成 | 小 |
| 候选组 / 认领 / 放回 | `wf_task_candidate`（资格多行）+ 条件更新抢占 `assignee IS NULL` | 小。**领取超时自动放回 Flowable 本来就不给**，原方案也要自己写 |
| 多租户（tenantId） | 每表带 `system_code`，所有 SQL 都在 Repository 层强制拼上 | 小。10 的 14 张表 DDL **本来就带 `system_code`** |
| 定时器（到期、超时） | 持久化定时：SLA 到期 · 领取超时 · webhook 重试 · 发件箱投递 | **用 db-scheduler，不自研**（§五） |
| 流程定义版本 | 内容哈希版本 —— **原方案本来就要另存** | 零 |

而下面这些**原方案里就已经是我们写**的，不受影响：

| 本来就自研 | 说明 |
|---|---|
| 审批轮次 · 或签 / 会签 · 五条内建规则 | Flowable 不懂这些 |
| SLA 营业时间 · 暂停 / 恢复 | Flowable 的 dueDate 只会按自然时间算 |
| 待办投影（跨 kind 合并 + 锚点 fail-closed） | —— |
| webhook / 发件箱 / 幂等 / 对账 | —— |
| 工单模块全部 | D6：本来就不依赖引擎 |

**而换掉 Flowable 后，有一大块原方案要写的东西直接消失：**

| 删掉的 | 原来为什么要写 |
|---|---|
| **JSON → BPMN 生成器 + 校验**（S1-3，估 4 人日） | 为了把"定义是数据"喂给 Flowable。**这是原方案里最大、最容易出错的一块自研** |
| Flowable 的独立 `SqlSessionFactory` / 事务隔离（18 §3.2） | 防 MP 插件串到 `ACT_*` |
| Jackson 2 这条线 + 对应的 ArchUnit 规则（18 §3.3） | Flowable 内部用 Jackson 2 |
| candidateGroups 的字符串编码（`PERMISSION:x` 塞进组名） | 适配 Flowable 的用户/组模型 |
| 与 Flowable 历史表的双写 / 对照 | 待办投影要从 `ACT_RU_TASK` 翻译 |

---

## 四、两条路对比

| 维度 | A · 留 Flowable（18 现方案） | **B · 自研 + JdbcClient（推荐）** |
|---|---|---|
| MyBatis | 在（引擎内部） | **无** |
| 表 | Flowable **45 张**（关掉 IDM 后约 36）+ 我们的 `wf_*` | 只有我们的 `wf_*` / `wo_*`（10 定稿 14 张 + db-scheduler 1 张） |
| 定义怎么进引擎 | JSON → **生成 BPMN** → 部署 | JSON 直接就是运行时结构，**零翻译** |
| 查询与排障 | 业务问"这单卡在哪" → 要懂 `ACT_RU_EXECUTION` / `ACT_RU_TASK` / `ACT_HI_*` 的关系 | 一张 `wf_instance` + 一张 `wf_task`，SQL 一眼看懂 |
| 持久化风格 | 两套（我们 `JdbcClient` + Flowable MyBatis） | **一套**，与 `job-store` 同构 |
| 18 的三处咬合点 | 三处都在 | Boot 版本差、MyBatis 双装配、Jackson 双线 **全部消失** |
| GraalVM native | 最大未知数 N3（运行期部署 BPMN）+ Flowable 反射面 | 只剩 `JdbcClient` + Jackson 3 + db-scheduler，**反射面极小**；N3 整条删除 |
| 引擎本身的成熟度 | 高：并发推进、异步执行器久经考验 | 要自己写对、测对 —— **这是 B 的真实代价**（§4.1） |
| 将来要网关 / 并行 / 子流程 | 白拿 | 要么自己加，要么那时再引入引擎。**10 §4.1 实测六个项目都不需要** |
| 自研工作量（S1 合计，按 16 任务清单） | 25.5 人日（含 S1-3「Flowable 接入 / BPMN 生成」4）+ B1 实测 1 | 25.5 人日（去掉 S1-3 的 4，加「引擎落库与并发」3、「db-scheduler 接入」1）；**B1 取消** —— **持平，略省 1 天** |

### 4.1 B 的真实代价，以及怎么压住

不自研引擎最大的理由是"别人替你把并发与持久化写对了"。B 要把这件事自己做对：

| 风险 | 压法 |
|---|---|
| 并发迁移丢更新 | 一律条件更新（原状态 + 乐观版本号），0 行即 409；**负对照：去掉 `AND status=?` 必须有并发用例变红** |
| 认领竞争 | `UPDATE wf_task SET assignee=? WHERE id=? AND assignee IS NULL`；两线程同抢只有一个成功 |
| 定时器丢失 / 重复 | 交给 db-scheduler（持久化、多实例安全、至少一次）；业务处理器按 R3 幂等 |
| 与业务同事务（形态 A） | `JdbcClient` 天然走 Spring 事务，**比 Flowable 共用事务更简单**（没有第二个 SqlSessionFactory） |
| 双库差异 | S0-3 的 Testcontainers 三库矩阵照旧；SQL 只用两库交集语法，方言差异收敛到 Repository 的一个 `Dialect` 小接口（分页、`FOR UPDATE SKIP LOCKED`、upsert） |

---

## 五、推荐栈（B 方案）

| 层 | 选型 | 版本（已核） | 说明 |
|---|---|---|---|
| 持久化 | Spring `JdbcClient` | 随 Boot 4.0.7 | 手写 SQL，Repository 一层；行映射用具名 `RowMapper`，**不用 `BeanPropertyRowMapper`**（反射，native 不友好） |
| 方言 | 自有 `Dialect` 接口：MySQL/MariaDB · PostgreSQL · H2 | —— | 只收三件事：分页、`SKIP LOCKED`、upsert |
| 迁移 | Flyway 独立实例 + 独立历史表 | 随 Boot | 不变（D8） |
| **持久定时** | **db-scheduler** | **16.12.0**，自带 Boot 4（4.0.6）starter | 替代 **ShedLock + Flowable 定时器 / 异步执行器**：SLA 到期、领取超时、webhook 重试、发件箱投递都是它的一次性或周期任务；一张 `scheduled_tasks` 表；多实例靠行锁 |
| 状态机 / 审批 / 任务 | 自研 `workflow-core` | —— | §三 |
| 其余 | 同 18：springdoc 3.0.3 · Micrometer · Testcontainers · ArchUnit · Spring AOT | —— | ShedLock **不再需要**，18 §九 A5 随之消失 |

**db-scheduler 的两个注意点**：

| 点 | 处理 |
|---|---|
| 任务数据默认用 **Java 序列化** | 改配 JSON 序列化器（Jackson 3）。Java 序列化对 native 不友好，也让表里的数据不可读 |
| GraalVM 社区元数据库**没有**它（已核 404） | 它反射面很小，但要在 B1-N 里验证；必要时补一个 `RuntimeHintsRegistrar` |

### 5.1 对 18 的影响（确认 T7 后改）

| 18 的章节 | 变化 |
|---|---|
| §〇 引擎行 · §二 整节 | Flowable → 自研 + db-scheduler |
| §三 三处咬合点 | 全部删除 |
| §四 定时互斥 | ShedLock → db-scheduler |
| §六 B1 七条 | 改为"引擎正确性"七条：并发迁移 · 认领竞争 · 定时器至少一次 · `system_code` 隔离负对照 · 双库 · 同事务回滚 · 依赖收敛 |
| §九 GraalVM | N3 删除；A5 删除；A2 保留（本来就不跑表达式） |
| 16 任务清单 | S1-3「Flowable 接入」→「引擎落库 + 并发」；B1「Flowable 8 打通性实测」取消 |

---

## 六、范围：只动 `flow-*`，不动 `sharehub-app`

| 事实 | 结论 |
|---|---|
| `backend/` 里 **134 个** `BaseMapper` 接口、**455 个** Java 文件引用 `com.baomidou` | `sharehub-app` 去 MyBatis-Plus 是一个独立的大型迁移 |
| 同时已有 **120 个**文件在用 `JdbcClient` / `JdbcTemplate` | 团队对 JDBC 风格不陌生，**不是从零开始** |
| 本方案的 `flow-*` 按 D7 本来就不依赖 MP | 两件事互不阻塞 |

若你的本意是**整个后端**去 MyBatis-Plus，请明说（T8）：那需要单独立项评估，不会塞进工作流方案。

---

## 七、核法（2026-09-27）

| 断言 | 核法 |
|---|---|
| Flowable 以 compile 范围依赖 MyBatis | 读 `flowable-engine-8.0.0.pom`、`flowable-engine-common-8.0.0.pom` |
| `DbSqlSession` 持有 MyBatis `SqlSession` | 下载 `flowable-engine-common-8.0.0.jar`，`javap -p` |
| 13 个实体映射 XML | `unzip -l flowable-engine-8.0.0.jar` |
| Flowable 在 MySQL 建 45 张表 | 下载 11 个 Flowable 8.0.0 jar，汇总 `create/*mysql*create*.sql` 里的 `CREATE TABLE` 去重 |
| nFlow 11.0.0：Apache 2.0、Spring JDBC、Spring 7.0.7 | 读 `nflow-engine-11.0.0.pom` 与 `nflow-root-11.0.0.pom` |
| DBOS Java 最新为 1.2.0-m10 | 中央仓库 `maven-metadata.xml` |
| db-scheduler 16.12.0，带 Boot 4.0.6 starter | 读 `db-scheduler-parent-16.12.0.pom` |
| db-scheduler / nFlow 无 GraalVM 社区元数据 | `oracle/graalvm-reachability-metadata` 对应 `index.json` 返回 404 |
| 后端 MP 与 JDBC 用量 | `grep` 统计 `extends BaseMapper` / `com.baomidou` / `JdbcClient|JdbcTemplate` |
| **未核（凭文档记忆，待确认）** | nFlow 的数据库支持清单、「无人工任务模型」—— 均以其官方文档为准，但不改变"流程是 Java 类"这一决定性理由；DBOS「只支持 PostgreSQL」同理 |

---

## 八、要你拍板

| # | 事项 | 建议 |
|:-:|---|---|
| **T7** | 为去 MyBatis 而**撤回「工作流引擎不用自研」**，改用 B 方案（自研轻量引擎 + `JdbcClient` + db-scheduler） | **是**。理由：不存在不用 MyBatis 的合格引擎（§二）；自研量与原方案持平（§四，按 16 任务清单逐项加减）；换来一套持久化、零 BPMN 翻译、native 顺畅 |
| **T8** | 去 MyBatis 的范围：只 `flow-*`，还是连 `sharehub-app` | **只 `flow-*`**；后者另立项 |

确认 T7 后，我按 §5.1 改 18、16 任务清单与 README。

## 修订记录

| 日期 | 变化 |
|---|---|
| 2026-09-27 | 首版：核实 Flowable 与 MyBatis 不可分；四个无 MyBatis 候选逐个排除；给出 B 方案与对 18 的影响清单 |
