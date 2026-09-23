# ADR-022 共享构件发布到自建 Nexus

状态：**提议 · 待确认**（2026-09-23，依据用户同日要求「neargo 的共享件后面考虑提交到部署的 nexus 中」起草）
· **修订** [ADR-006](./ADR-006-复用neargo基础框架与依赖方式.md) 的「依赖分发方式」（Codeup 私仓 → 自建 Nexus；「私仓坐标依赖、不进 monorepo、不用 submodule」的结论不变）

细化文档：[v4/10 共享构件与 Nexus](../v4/10-共享构件与Nexus.md)

> **同日修订（2026-09-23）**：发布源由「ai-neargo 的 `platform/`」改为 ——
> **`neargo-framework`**（框架 + 组件 `neargo-notify` / `neargo-media`；已从 ai-neargo 带历史抽出，本地构建通过，[v4/13](../v4/13-neargo-framework独立仓库.md)）与
> **ai-shop**（暂托管的两个共用服务：只发布 `pay-client` · `shop-job-api` · `shop-job-target` 与 `pay-svc` · `shop-job` 两个可执行 jar，[ADR-024](./ADR-024-共用服务暂由ai-shop托管-组件并入框架.md)）。
> 决策 2 已在 `neargo-framework` 落地（`neargo-bom` 继承 Boot starter-parent，`neargo-build-parent` 继承 BOM）；
> 决策 3 的「同一条版本线」改为：框架与组件一条线（`1.0.0` 原 commons 冻结 → `2.0.0` 新分层）；共用服务随 ai-shop 发版。

> **2026-09-23 再修订（实测）**：私仓**已经存在** —— `https://nexus.neargo.ai/nexus/`，**Nexus Repository Manager 2.14.20-02**（非 3.x，路径为 `/nexus/content/{groups,repositories}/…`）。
> 决策 1「自建 Nexus」**已满足**，改为「收口现有实例」：关闭 `releases` / `snapshots` 的公网匿名读、建只读与部署两个专用账号、轮换 admin 口令、评估升级 3.x（2.x 已停止支持）。
> 决策 3「统一 groupId `ai.neargo` 一条版本线」需注意：`ai.neargo:neargo-framework` 的 0.0.8 / 1.0.0 / 1.0.1 **已被另一条产品线**（minipos / qrpay / cbdc，Spring Boot 3.3.2 + Spring Cloud Alibaba + Sa-Token）占用，本线**从 `2.0.0` 起**（用户 2026-09-23 定）。
> 凭据一律只放 `~/.m2/settings.xml` 与 CI 凭据库，**不进任何仓库与文档**。

## 背景

- ADR-006 定了「neargo commons 发 Codeup 私仓，按坐标依赖」，**至今未落地**：本机与三个仓库都没有私仓配置，
  ai-shop 与 ShareHub 都靠在同一台机器上先 `mvn install` 一份 `1.0.0-SNAPSHOT` 才能构建；
- ADR-020 / 021 与 v4/09 又新增了一批要共享的构件（任务、会话存储、支付、内部调用、持久件、认证骨架），共享面变大；
- `neargo-parent` 同时是 ai-neargo 整个仓库的聚合 POM，被 ai-shop 与 ShareHub 直接当父 POM 继承 —— 发布它就把 ai-neargo 的业务版本绑给了消费方。

## 决策

1. **自建 Nexus Repository 3** 作为 Maven（及将来的 npm）私有仓库；消费方只经 `maven-public` 组拉取；
2. **拆出 `neargo-build-parent` 与 `neargo-bom`**，与 ai-neargo 的聚合 POM 分开；ai-shop 与 ShareHub 改为继承前者、导入后者；
3. **共享构件统一 groupId `ai.neargo`、同一条语义化版本发布线**，由 `neargo-bom` 对外；
4. **生产构建只许 release 版本**；SNAPSHOT 只给开发分支；
5. **只有 CI 能发布**（Jenkins，打标签触发），发布前跑 ai-shop 与 ShareHub 的兼容矩阵；releases 仓库禁止重复部署；
6. Nexus **不与现有腾讯云应用机同机**（内存争抢），单独部署并每日备份。

## 否决

| 方案 | 理由 |
|---|---|
| 继续本机 `mvn install` | 换机器、上 CI 就无法构建；SNAPSHOT 不可追溯 |
| 继续继承 `neargo-parent` | 把 ai-neargo 业务版本绑给消费方 |
| 每个共享构件各自一条版本线 | 组合爆炸；消费方难以判断哪些版本能一起用 —— 用一个 BOM 版本代表一组经过兼容验证的组合 |
| 托管制品库（Codeup / CODING） | **可作为替代**：不想自运维时直接换地址，POM 与流程不变 |

## 后果

- 先做 N0–N2（部署 Nexus、拆父 POM 与 BOM、两项目切换）即可解决「只能在一台机器上构建」，不依赖任何代码抽离；
- 多一套需要运维与备份的服务；
- 共享构件的每次发布都要过兼容矩阵，发版会比直接改代码慢 —— 这是两个项目共用一份代码的代价。

## 待确认

1. Nexus 放哪台机器（推荐单独一台 ≥ 4 GB 内存的小实例）、域名（推荐 `nexus.ichain.top`）；
2. 自建 Nexus（推荐，用户意向）还是托管制品库；
3. 首个 release 版本号（推荐框架 `1.0.0`，即把当前 commons 冻结为第一个正式版；新分层发 `2.0.0`）。
