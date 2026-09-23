# 10 · 共享构件与 Nexus 发布

> 所属：[v4 详细规划](./README.md) · 决策：[ADR-022](../ADR/ADR-022-共享构件发布到自建Nexus.md)（修订 [ADR-006](../ADR/ADR-006-复用neargo基础框架与依赖方式.md) 的分发方式）
> 共享什么：[09 §二](./09-后端代码架构.md)（后端）· [ADR-020](../ADR/ADR-020-单体优先-支付与协议对接独立部署.md)（支付 / 任务 / 会话存储）
> 状态：**待确认**（2026-09-23；同日修订：框架改由独立仓库 `neargo-framework` 发布，[13](./13-neargo-framework独立仓库.md)；支付与任务为共用服务、暂由 ai-shop 发布；通知与媒体作为组件并入框架，[ADR-024](../ADR/ADR-024-共用服务暂由ai-shop托管-组件并入框架.md)）

> ⏸ **框架线冻结中**（2026-09-23，[ADR-025](../ADR/ADR-025-直接复用ai-shop在跑的支付与任务服务-多系统改造.md) 决定 5，用户定：「framework 暂时先不建，等本地全部处理好并测试通过再建」）。
> 本地 `/Users/robin/work/ai/neargo-framework` **原样保留**（已验证可构建），但不提交、不建远端、不发布、不 install、不做 2.x 重排；ShareHub 父 POM 维持 `neargo-parent`。
> 本篇方案**保留不作废**，解冻判据与顺序见 [14 §七](./14-执行任务清单.md)。

---

## 〇、现状（实测）

| 项 | 现状 | 问题 |
|---|---|---|
| 私有仓库 | **已有，此前不知道**：`https://nexus.neargo.ai/nexus/`（2026-09-23 用户提供并实测）。**Nexus Repository Manager 2.14.20-02**，不是 3.x | 三个仓库仍无私仓配置，还是靠本机 `mvn install`；接上即可解决「只能在一台机器上构建」 |
| 仓库布局（实测） | 托管 `releases` · `snapshots` · `thirdparty`（空）；代理 `central` · `apache-snapshots` · `spring-snapshot` · `central-m1`；组 `public`（能取到 `spring-boot-starter-parent:4.0.7`）；另有 `ume` | 路径是 NXRM2 形式 `/nexus/content/{groups,repositories}/…`，**不是 3.x 的 `/repository/…`**；本篇原按 3.x 写的仓库名与路径已改 |
| ⚠️ **坐标已被占用** | `releases` 里**已有另一套 `ai.neargo:neargo-framework`**（0.0.8 / 1.0.0 / 1.0.1，最近一次 2026-01-14）：`-core` `-web` `-mybatis` `-redis` `-json` `-excel` `-doc` `-satoken` `-idempotent` `-ratelimiter` `-loadbalancer` `-alibaba-bom` + `neargo-framework-bom`；栈是 **Spring Boot 3.3.2 + Spring Cloud Alibaba + Sa-Token + Hutool**，来自 minipos / qrpay / cbdc / emvco 产品线 | 与本线（Boot 4.0.7 + MyBatis-Plus + 不透明令牌）**同 GA 不同栈**。✅ 已定：本线**从 `2.0.0` 起**（用户 2026-09-23 定），接在那条版本线之后；本线其余坐标（`neargo-bom` · `neargo-build-parent` · `neargo-common-*` · `neargo-auth-core`）实测**全部空闲** |
| ⚠️ 匿名可读 | `releases` **公网匿名可浏览可下载**（实测 200）：minipos / cbdc / qrpay / emvco 全部构件对外可取 | 私有构件等于公开，接入前必须先收口 |
| 本线构件 | `ai/neargo/` 下**没有** `neargo-common-*`：ai-neargo / ai-shop / ShareHub 这条线一个都没发过 | N1 仍从零发布 |
| 版本 | 全部 `1.0.0-SNAPSHOT` | 没有可复现的发布；SNAPSHOT 随时被覆盖，生产包依赖的内容无法追溯 |
| 父 POM | ai-shop 与 ShareHub 都继承 `ai.neargo:neargo-parent` —— 它**同时是 ai-neargo 整个仓库的聚合 POM**（列着 merchant / crm / fnb / nearpay / nearboss 等业务模块） | 发一次父 POM 就等于把 ai-neargo 的整个平台版本绑进来；消费方被迫跟着 ai-neargo 的发版节奏 |
| BOM | 没有独立 BOM，版本管理写在 `neargo-parent` 的 `dependencyManagement` 里 | 消费方不能「只取版本清单、不继承构建配置」 |
| 待共享的 ai-shop 模块 | 在 `ai.neargo.shop` groupId 下，随 ai-shop 发版 | 抽离后需要新坐标 |

---

## 一、目标

```
neargo-framework 仓库（框架 + 组件，F1 已在本地建成）   ai-shop 仓库（暂托管两个共用服务，ADR-024）
├─ build/    neargo-build-parent · neargo-bom           ├─ pay-client · shop-job-api · shop-job-target
├─ 1.x：common/* · auth/*（ai-neargo 原样抽出）         │     （产品只许依赖这三个）
└─ 2.x：foundation/ adapters/ infra/ starters/          └─ pay-svc · shop-job（可执行 jar，各产品各自部署）
         components/（notify · media）testing/
        │                                                   │
        └────────────── CI 发布（打标签 → release；主干 → snapshot）
                                  ▼
自建 Nexus（nexus.<域名>）
├─ maven-releases    hosted，同一版本只能发一次
├─ maven-snapshots   hosted，定期清理
├─ maven-central     proxy
├─ aliyun-public     proxy（国内构建机加速）
├─ maven-public      group = 以上全部，消费方唯一入口
└─ npm-hosted        前端共享包（前端会话决定）
        ▲
        │  继承 neargo-build-parent（版本随 neargo-bom）· 共用服务客户端版本钉在产品父 POM
ai-shop · 本项目 · ai-neargo（ai-neargo 不再发布共享构件，只保留自己的 neargo-common-api）
```

**三条原则**：

1. **共享构件有自己的仓库与发布线**，与任何产品（含 ai-neargo）的发版解耦（聚合 POM 不再兼任父 POM）；
2. **生产构建只许依赖 release 版本**（SNAPSHOT 只给开发分支）；
3. **只有 CI 能发布**，人不直接 `mvn deploy`。

---

## 二、发布哪些构件

统一 groupId **`ai.neargo`**（不再分 `ai.neargo.pay` 等子组，与现有 `neargo-common-*` 一致）。

| 构件 | 仓库 | 类型 | 来源 | 首次发布批次 |
|---|---|---|---|---|
| `neargo-build-parent` · `neargo-bom` | framework | pom | ✅ F1 已拆出（BOM 继承 Boot starter-parent，构建父 POM 继承 BOM） | N1 |
| `neargo-common-{core,web,data,security,mq,i18n,config}` · `neargo-auth-core` | framework | jar | ✅ F1 已从 ai-neargo 迁入（1.x，原样） | N1 |
| `neargo-core` · `-data` · `-event` · `-data-mybatis-plus` · `-data-jdbc` · `-web` · `-security` · `-i18n` · `-config` · `-store` · `-auth-core` · `-auth-store` · `neargo-starter-*` · `neargo-test` · `neargo-archrules`（test-jar） | framework | jar / pom | 2.x 重排 + ai-shop 的中性件（会话存储、幂等 / Outbox、令牌过滤骨架、内部调用、架构测试） | N3 |
| `neargo-notify` · `neargo-media`（组件，`components/`） | framework | jar | ai-shop `shop-notify` · `shop-channel/media` + `shop-store-mybatis/media` | N4 |
| `ai.neargo.shop:shop-job-api` · `shop-job-target`（🆕）· `shop-job`（可执行） | **ai-shop** | jar | 任务服务，留在 ai-shop | N4 |
| `ai.neargo.shop:pay-client`（🆕）· `pay-svc`（可执行；通道与微信能力在其内） | **ai-shop** | jar | 支付服务，留在 ai-shop | N5 |

`neargo-common-api` 不发布（ai-neargo 业务契约，[13 §一](./13-neargo-framework独立仓库.md)）。

每个 jar 同时发 `-sources.jar`（排错可读源码）；可执行 jar（`shop-job` · `pay-svc`）由各项目用自己的配置部署实例。ai-shop 的其余模块不发布。

---

## 三、版本策略

| 项 | 规则 |
|---|---|
| 版本号 | **语义化版本**。框架**直接从 `2.0.0` 起**（`ai.neargo:neargo-framework` 的 1.0.x 已被另一条产品线占用，见 §〇；用户 2026-09-23 定）——「原样抽出冻结为 1.0.0」的计划作废，抽出的现状即 `2.0.0-SNAPSHOT` 的起点；组件与框架同一条线；**共用服务**随 ai-shop 发版，客户端 / 契约与服务 jar 同版本 |
| 版本写法 | CI 友好：`<version>${revision}</version>` + `flatten-maven-plugin`，发布时由 CI 传 `-Drevision=1.2.0` |
| SNAPSHOT | 只从主干自动发（`1.3.0-SNAPSHOT`）；消费方的开发分支可用，**生产构建禁止**（发布 profile 里 enforcer `requireReleaseDeps`） |
| 破坏性变更 | 升主版本 + 发布说明写迁移步骤；被替换的类先 `@Deprecated` 保留一个次版本 |
| 兼容验证 | 共享构件发新版前，CI 用它构建并跑 **ai-shop 与 ShareHub 的全量测试**（兼容矩阵）；任一失败不发布 |
| 消费方升级 | 只改一处：`neargo-bom` 的版本号；有 Renovate / 定期升级任务提醒，**不自动合并** |

---

## 四、消费方怎么接

### 4.1 POM

```xml
<parent>
  <groupId>ai.neargo</groupId>
  <artifactId>neargo-build-parent</artifactId>
  <version>1.0.0</version>                         <!-- 取代 neargo-parent:1.0.0-SNAPSHOT -->
</parent>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>ai.neargo</groupId>
      <artifactId>neargo-bom</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

模块 POM 里**不写版本**（ai-shop 已是这个规矩）。

### 4.2 `~/.m2/settings.xml` / CI

```xml
<mirrors>
  <!-- NXRM2 路径形式：/nexus/content/groups/<组> -->
  <mirror><id>neargo</id><mirrorOf>*</mirrorOf><url>https://nexus.neargo.ai/nexus/content/groups/public/</url></mirror>
</mirrors>
<servers>
  <server><id>neargo</id><username>${env.NEXUS_USER}</username><password>${env.NEXUS_PASSWORD}</password></server>
</servers>

<!-- 发布地址（distributionManagement，写在 neargo-build-parent）
     release  : https://nexus.neargo.ai/nexus/content/repositories/releases/
     snapshot : https://nexus.neargo.ai/nexus/content/repositories/snapshots/
     口令只放 ~/.m2/settings.xml 与 CI 凭据库，**不进仓库、不进文档** -->
```

- 凭据**只在** CI 凭据库与个人 `~/.m2/settings.xml`，**不进任何仓库、不进任何文档**；
- NXRM2 OSS 没有用户令牌，只能用账号口令 —— 所以更要用**专用部署账号**（仅 deploy 权限），不用 admin；
- 验收：清空本机 `~/.m2/repository/ai/neargo` 后，ShareHub 与 ai-shop 仍能完整构建。

---

## 五、发布流程（只有 CI 能发）

| 触发 | 做什么 | 发到 |
|---|---|---|
| 框架仓库 / ai-shop 主干合入 | 构建 + 测试 → `deploy` 当前 `-SNAPSHOT` | `maven-snapshots` |
| 在框架仓库打标签 `v1.2.0`（ai-shop 的共用服务构件按 ai-shop 的发版标签） | 构建 + 测试 + **兼容矩阵**（ai-shop、ShareHub 全量测试）→ `deploy -Drevision=1.2.0` + 生成发布说明 | `maven-releases` |

- 用户已有 Jenkins（`release` 技能的链路）：新增一个「neargo 共享构件发布」任务，凭据用 Jenkins 凭据库；
- Nexus 账号分三类：**CI 发布账号**（只有 deploy 权限，只对 hosted 仓库）· **只读账号**（开发者与各项目 CI）· **管理员**（个人，不给 CI）；关闭匿名访问；
- releases 仓库设为**禁止重新部署**（同一版本只能发一次）。

---

## 六、Nexus 部署

| 项 | 建议 |
|---|---|
| 现状 | **已有一台在跑**：`nexus.neargo.ai`，NXRM **2.14.20-02**，不必新建 |
| ⚠️ 版本 | NXRM 2.x **已终止支持**（2.14.20-02 为末版，无安全补丁）。排期升级到 3.x（数据可迁移），或至少确认它只对可信网络开放 |
| ⚠️ 权限（**接入前必做**） | ① 关闭 `releases` / `snapshots` 的**匿名读**；② 建**只读账号**（开发者与各项目 CI）与**部署账号**（仅 deploy）；③ **轮换 admin 口令**，停止用 admin 做日常发布；④ NXRM2 OSS **无用户令牌**（Pro 功能），CI 用部署账号口令，只放凭据库 |
| ~~软件~~ | ~~Nexus Repository 3（社区版）~~ → 已有 2.14，改为「维持现状 + 收口权限，升级 3.x 另排」 |
| 规格 | **至少 4 GB 内存**（JVM 堆约 2–3 GB）· 2 核 · 数据盘 50 GB 起（按 SNAPSHOT 清理策略估算） |
| 放哪 | **不放在现有的腾讯云应用机上**：那台已跑 ai-shop、ShareHub、MySQL 9.7、MariaDB，内存会争抢；单独一台小规格实例。若那台内存 ≥ 8 GB 可暂时同机，但要限制容器内存 |
| 域名与证书 | `nexus.ichain.top`（已有 `*.ichain.top` 泛域证书，[腾讯云部署记录](../../../deploy/tencent/README.md)）；nginx 反代 + HTTPS；仅开放 443 |
| 仓库 | `maven-releases`（禁重部署）· `maven-snapshots`（清理：每个构件保留最近 5 个、30 天）· `maven-central` 代理 · `aliyun-public` 代理 · `maven-public` 组；前端需要时加 `npm-hosted` + `npm-proxy` + `npm-group` |
| 备份 | 每日备份 blob 存储与 Nexus 数据库（Nexus 自带「备份」任务导出）→ 对象存储；季度演练一次恢复 |
| 替代 | 不想自运维：云效 Codeup 制品库 / 腾讯云 CODING 制品库（ADR-006 原方案），POM 与流程不变，只换地址 |

---

## 七、步骤

| 步 | 内容 | 跨仓 | 验收 |
|---|---|---|---|
| **N0** | **收口现有 Nexus**（不必新建）：关匿名读 · 建只读 / 部署两类账号 · 轮换 admin 口令 · 确认备份 · 评估升级 3.x | 否 | 匿名访问被拒；只读账号能从 `public` 组拉到 `spring-boot-starter-parent:4.0.7` |
| **N1** | 发布 `neargo-framework` **`2.0.0`**（F1 已在本地完成：带历史抽取、BOM 与构建父 POM、`2.0.0-SNAPSHOT` 构建通过；待建远端） | framework | `releases` 里有 8 个模块与两个 POM，且**不影响** 1.0.x 的使用方 |
| **N2** | ai-shop 与 ShareHub 改为继承 `neargo-build-parent:2.0.0`（只改 `<parent>`，有效版本不变）；配 mirror | **两仓** | 清空本机 neargo 缓存后两项目都能构建；ShareHub 部署脚本在干净机器上可用 |
| **N3** | 框架 `2.1.0`：分层、多 ORM、收进 ai-shop 中性件（框架 design/01 的 F2a–F2e）；两项目升级，删除副本（`known-pending-shared.txt` 清空） | framework · 两仓 | 兼容矩阵全绿 |
| **N4** | 框架组件 `neargo-notify` · `neargo-media`（随框架 2.x 发布，**F2a 完成即可开工**）；ai-shop 发布 `shop-job-api` · `shop-job-target` · `shop-job`（本项目完全没有、马上要用：定时任务、验证码短信、工单图片） | framework · ai-shop | 兼容矩阵全绿；本项目发出第一条真实验证码短信、`sharehub-job` 按时回调 |
| **N5** | ai-shop 支付服务改造（ADR-024 后果表 1–5：`pay-client`、收款与回调进 `pay-svc`、反向引用泛化、预授权 + AE 通道、微信能力并入）并发布 | ai-shop | 同上 + 支付沙箱回归 + 小程序登录回归 |

N0–N2 不涉及任何代码抽离，**做完就解决「只能在一台机器上构建」这个现实问题**，建议最先做。
N3–N5 的归属与理由见 [ADR-024](../ADR/ADR-024-共用服务暂由ai-shop托管-组件并入框架.md) 与 [11 共用组件方案 §七](./11-共用组件方案.md)；与 [09 §十](./09-后端代码架构.md) 的 C7、[08](./08-落地路线.md) 的 S4 / S6 对齐。
