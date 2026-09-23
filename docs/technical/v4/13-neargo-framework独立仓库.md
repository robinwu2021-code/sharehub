# 13 · neargo-framework 独立仓库

> 所属：[v4 详细规划](./README.md) · 状态：**F1 已执行**（2026-09-23：本地仓库已建、带历史、构建通过）；2.x 分层方案**待确认**
> 起因：用户要求「针对 neargo framework 抽取一个独立的项目，在 `work/ai` 下新建 `neargo-framework`，单独存放框架层的代码」；
> 随后要求框架「支持多层级、不同粒度的抽象，针对 MyBatis 以及 JDBC 的各种 ORM 的支持」。
> 仓库：`/Users/robin/work/ai/neargo-framework`（尚无远端）· 2.x 方案：该仓库 [`docs/design/01-分层粒度与多ORM.md`](../../../../neargo-framework/docs/design/01-分层粒度与多ORM.md)
> 影响：[10](./10-共享构件与Nexus.md) · [11](./11-共用组件方案.md) · [12](./12-代码库与工程结构.md) · [ADR-022](../ADR/ADR-022-共享构件发布到自建Nexus.md) 已改为以本仓库为框架的发布源。

> ⏸ **框架线冻结中**（2026-09-23，[ADR-025](../ADR/ADR-025-直接复用ai-shop在跑的支付与任务服务-多系统改造.md) 决定 5，用户定：「framework 暂时先不建，等本地全部处理好并测试通过再建」）。
> 本地 `/Users/robin/work/ai/neargo-framework` **原样保留**（已验证可构建），但不提交、不建远端、不发布、不 install、不做 2.x 重排；ShareHub 父 POM 维持 `neargo-parent`。
> 本篇方案**保留不作废**，解冻判据与顺序见 [14 §七](./14-执行任务清单.md)。

---

## 〇、结论

| 项 | 方案 |
|---|---|
| 仓库 | `/Users/robin/work/ai/neargo-framework`；远端建在云效 Codeup（与 ai-neargo 同一组织，**用户建**） |
| 放什么 | **框架层**：不含业务、所有产品写业务代码都会用到的库 + 构建父 POM + BOM |
| 不放什么 | `neargo-common-api`（ai-neargo 的业务契约）；**共用服务**（支付含通道与微信、任务调度）—— 暂由 ai-shop 托管（[ADR-024](../ADR/ADR-024-共用服务暂由ai-shop托管-组件并入框架.md)） |
| 组件 | 嵌入产品的能力库并入本仓库 `components/`：`neargo-notify` · `neargo-media`（[ADR-024](../ADR/ADR-024-共用服务暂由ai-shop托管-组件并入框架.md)） |
| **版本从 2.0.0 起** | `ai.neargo:neargo-framework` 的 **0.0.8 / 1.0.0 / 1.0.1 已被另一条产品线占用**（minipos / qrpay / cbdc 的 Spring Boot 3.3.2 + Spring Cloud Alibaba + Sa-Token 框架，2026-01-14 发布在 nexus.neargo.ai）。用户 2026-09-23 定：**本线从 `2.0.0` 起**，接在那条版本线之后。本地已改为 `2.0.0-SNAPSHOT` 并构建通过 |
| 2.0.0 内容 | = 从 ai-neargo 抽出的 8 个模块**原样**（包名与模块坐标不变；实测这些坐标全部空闲）。原「冻结为 1.0.0」的计划作废 |
| 2.1.0 及以后 | 五级分层 + 多 ORM 适配 + 收进 ai-shop 的中性件；包名改为 `ai.neargo.<模块>`，artifactId 去掉 `common`（框架 design/01） |
| 消费方 | ai-shop 与本项目在 2.0 可用后切换（F3）；ai-neargo 自定时间，之前留在 1.x |

---

## 一、边界

**判据**：框架层 = 所有产品写业务代码时都会用到的**库**；不含业务名词、不含可独立运行的服务、只有基础设施类的表（会话、凭据、幂等、Outbox）。

| 内容 | 进 neargo-framework | 说明 |
|---|---|---|
| 构建父 POM · BOM | ✓（F1 已建） | 从 `neargo-parent` 拆出 |
| `neargo-common-{core,web,data,security,mq,i18n,config}` · `neargo-auth-core` | ✓（F1 已迁） | 2.x 重排为 `neargo-core` / `-data` / `-data-mybatis-plus` / `-data-jdbc` / `-event` / `-web` / `-security` / `-i18n` / `-config` / `-auth-core` |
| ai-shop 的中性件：会话存储、幂等 / Outbox、令牌过滤骨架、内部调用 | ✓（F2d） | 成为 `neargo-auth-store` · `neargo-store` · `neargo-security` · `neargo-web` 的一部分 |
| 架构规则 · 测试支撑 | ✓（F2e） | `neargo-archrules` · `neargo-test` |
| `neargo-common-api` | ✗ | 157 个 DTO、31 个业务枚举、26 个业务事件 —— 全是 ai-neargo 业务；ai-shop 与本项目都不依赖 |
| 通知 · 媒体 | ✓（`components/`） | 嵌入产品的库；与框架同版本、同 BOM |
| 支付（含通道与微信）· 任务调度 | ✗ | 共用服务（自己的进程与库），暂留 ai-shop |

对 11 早先版本的一处修正保留：`neargo-auth-store` 归框架层（L3 基础设施，纯 JdbcClient），否则框架里的认证骨架要反向依赖框架之外的构件。

---

## 二、F1 执行记录（2026-09-23）

| 项 | 实际 |
|---|---|
| 抽取源 | 本机 ai-neargo 的 `chore/2026h2-stabilize` 分支（含为本项目上推的 RBAC `@perm` 与数据范围引擎 `6ee8005`，该提交尚未进 ai-neargo main） |
| 方法 | `git clone --no-hardlinks --single-branch` 到新目录 → **删除 origin**（防止误推回 ai-neargo）→ `git filter-branch --index-filter` 只保留框架路径并改写目录（本机无 `git-filter-repo`，改用 Git 自带工具，效果相同）→ 分支改名 `main` → 清理改写备份并 gc |
| 路径改写 | `commons/neargo-common-*` → `common/neargo-common-*` · `commons/neargo-auth-core` → `auth/neargo-auth-core` · `commons/docs` → `docs` |
| 历史 | 原分支 264 个提交 → 保留触及框架路径的 **20 个**，最早 `b037256 初始化 neargo 平台工程骨架`，最新即 `6ee8005` 对应的改写提交 |
| 新增 | `build/neargo-bom`（继承 Boot starter-parent，钉 MP 3.5.16 / Nacos 2.4.3 与全部框架模块）· `build/neargo-build-parent`（继承 BOM；JDK 21 门禁、编码、插件）· 根聚合 POM `neargo-framework` · README · CLAUDE.md · .gitignore；8 个模块的父 POM 由 `neargo-parent` 改为 `neargo-framework` |
| 版本单一来源 | BOM 里的 `neargo.version` 必须等于工程版本，根 POM 的 enforcer 规则拦截（已做反向测试：改成 `9.9.9` 构建失败） |
| 验证 | JDK 21 离线 `mvn -o verify`：11 个模块全部 SUCCESS，全部测试通过；有效版本与原 `neargo-parent` 一致（Boot 4.0.7 · MP 3.5.16 · Nacos 2.4.3） |
| 刻意没做 | 没有 `mvn install`（1.x 坐标与 ai-neargo `commons/` 相同，会覆盖本机 `~/.m2` 里消费方正在用的构件）；没有提交（改动留在工作区待审）；没有改任何现有仓库 |

继承链（Spring Boot 的做法：版本在下、构建规则在上；仓内构建不依赖 BOM import，因为 Maven 3.9 不能 import 同一 reactor 里的 BOM）：

```
spring-boot-starter-parent 4.0.7
  └ neargo-bom                版本：Boot（继承）+ MP + Nacos + 全部框架模块
      └ neargo-build-parent   构建规则：JDK 21 · enforcer · 插件
          ├ neargo-framework  根聚合 → common/* · auth/*
          └ 消费方（ai-shop / sharehub）：把 <parent> 从 neargo-parent 换成它即可
```

当前目录（1.x）：

```
neargo-framework/
├─ pom.xml                     neargo-framework（聚合）
├─ build/neargo-bom/ · build/neargo-build-parent/
├─ common/neargo-common-{core,security,web,data,mq,i18n,config}/
├─ auth/neargo-auth-core/
├─ docs/README.md · docs/design/01-分层粒度与多ORM.md
└─ README.md · CLAUDE.md · .gitignore
```

2.x 目录按层级重排为 `build/ · foundation/ · adapters/ · infra/ · starters/ · components/ · testing/`，见框架 design/01 §2.2。

---

## 三、2.x 要点（详见框架 design/01）

| 项 | 内容 |
|---|---|
| 五级分层 | L0 纯 Java 基础 → L1 与技术无关的抽象与策略 → L2 适配器（一个模块只绑一种技术）→ L3 自带表的基础设施 → L4 starter；只许依赖更低的级，enforcer + ArchUnit 在框架自己的构建里拦 |
| 多 ORM | MyBatis-Plus（主力，全自动）· JdbcClient（隔离同样自动：复用 MP 的 JSqlParser 改写器包装 `NamedParameterJdbcOperations`，已 PoC）· Spring Data JDBC 预留 · 裸 MyBatis 走 MP · JPA / jOOQ 不支持 |
| 一份策略 | 租户、数据范围、软删、审计、乐观锁、分页、唯一冲突转幂等在 L1 定义一次；适配器负责落实；同一套 TCK 验证一致 |
| 基础设施 | 幂等、Outbox、会话、凭据一律 JdbcClient —— 任何 ORM 的产品都能用，产品不再扫描框架的 Mapper |
| 粒度 | 实体基类四档（`IdEntity` → `CreatedEntity` → `AuditedEntity` → `StandardEntity`）· 数据访问五级阶梯 · 依赖四种粒度（BOM / starter / 选适配器 / 单模块） |
| 解决的 1.x 问题 | 三份重复实现；`data → security` 倒挂；凭据绑 MP；业务表名硬编码进租户白名单；JdbcClient 绕过隔离；乐观锁冲突不报错；驱动强加 |

---

## 四、消费方怎么切换

| 消费方 | 改动 | 何时 |
|---|---|---|
| **本项目** | `sharehub-parent` 改继承 `neargo-build-parent:2.0.x`；按映射表改 import；删除 `sharehub-common` 里的待下沉副本（`BaseEntity` 审计部分、审计填充、Outbox、`DomainEvent`、令牌存储）；按 [12 §四](./12-代码库与工程结构.md) 调整目录 | F3（先） |
| **ai-shop** | 同上；`shop-auth-store` / `shop-store-mybatis` 的中性部分改用框架构件 | F3（次） |
| **ai-neargo** | 从根 `pom.xml` 移除 `commons/` 下 8 个模块，改依赖本仓库 1.x；`neargo-common-api` 原地保留；迁 2.x 由 ai-neargo 自定 | 与 ai-neargo 的并行会话协调 |

**Nexus 就绪前**：消费方从本机 `mvn install` 的构件取依赖。1.x 阶段本仓库**只 verify 不 install**（见上）；2.0 的坐标与 1.x 不同，届时可以放心 install。

---

## 五、仓库治理

| 项 | 规则（已写进仓库 `CLAUDE.md`） |
|---|---|
| 边界 | 不含任何产品的业务名词；产品差异一律走 SPI；不依赖任何产品构件 |
| 改动 | 每个改动带测试；产品需要新能力 → 在本仓库提改动 → 用消费方构建验证 → 发版 → 产品升版本；**禁止在产品里 fork 框架代码** |
| 版本 | 单一版本线；BOM 与工程版本由 enforcer 保持一致；破坏性变更只进下一个主版本 |
| 与 ai-neargo | ai-neargo 是消费方之一，不再拥有框架代码 |

---

## 六、待确认

| # | 事项 | 推荐 | 其他选项 |
|---|---|---|---|
| 1 | 能力组件放哪 | ✅ **已定**（2026-09-23，[ADR-024](../ADR/ADR-024-共用服务暂由ai-shop托管-组件并入框架.md)）：通知、媒体并入本仓库 `components/`；支付（含通道与微信）与任务是共用服务，暂留 ai-shop | — |
| 2 | 远端 | Codeup，与 ai-neargo 同一组织（用户建好后 `git remote add origin …`） | 其他托管 |
| 3 | 抽取基线 | **已按「直接从 `chore/2026h2-stabilize` 抽」执行**。后果：ai-neargo 以后合并该分支时，`commons/` 的删除以本次抽取点为准；抽取点之后 ai-neargo 若再改 `commons/`，需手工同步到本仓库 | 若坚持「先合 main 再抽」，可重做 F1（纯本地操作，成本低） |
| 4 | `neargo-common-api` | 留在 ai-neargo，名字不动 | 改名 `neargo-api`（ai-neargo 内部事） |
| 5 | 历史 | **已保留**（20 个提交） | — |
| 7 | **坐标与版本** | ✅ **已定**（2026-09-23）：沿用 `ai.neargo:neargo-framework`，版本**从 2.0.0 起**（1.0.x 属另一条产品线）。**残留风险**：那条线的使用方若把版本升到 2.x 会拿到完全不同的栈（Boot 4.0.7、无 Sa-Token / Spring Cloud）—— 需在发布说明里写明，并确认 minipos / qrpay / cbdc 均为**固定版本**依赖、不使用版本区间 | 另起坐标（如 `ai.neargo.commons:*`）可彻底隔离，但要改所有 import 的 groupId |
| 8 | 模块命名是否对齐 | 旧线用 `neargo-framework-core` / `-web` / `-mybatis`，本线 2.1 计划用 `neargo-core` / `neargo-web` / `neargo-data-mybatis-plus` —— 名字相近但不同构件，排障时容易认错 | 保持本线命名（已在 design/01 定）；在发布说明与 README 里对照说明 |
| 6 | 2.x 的版本策略、包名改名、时间与软删类型、JDBC 隔离实现、未登记表策略 | 见框架 design/01 §九（7 项） | — |
