# TDD-enforcer-module-boundary（B2 第二项 · Maven 层面的依赖纪律卡口）

状态：**已实现**（2026-09-23）
关联需求：[v4/12 §5.2 模块依赖矩阵 · §5.6 校验](./v4/12-代码库与工程结构.md) · [v4/09 C0](./v4/09-后端代码架构.md) · [v4/14 B2](./v4/14-执行任务清单.md)
前序：[TDD-arch-guard-fix](./TDD-arch-guard-fix.md)（B2 第一项）

---

## 1. 需求摘要

给每个后端模块加 `bannedDependencies`，外加 `dependencyConvergence` —— 让 [v4/12 §5.2](./v4/12-代码库与工程结构.md)
的依赖矩阵从「文档里的表」变成「构建时的卡口」。

**与 arch-guard 的分工**（两者查的不是同一件事）：

| | 查什么 | 漏什么 |
|---|---|---|
| `arch-guard.py` G3 | 源码里**注入了**别的服务的实现类 | 声明了依赖但还没用 |
| enforcer `bannedDependencies` | pom 里**声明了**谁 | 同模块内的越界写法 |

先声明依赖、后写代码是常态，所以 enforcer 会**更早**拦住 —— 在还没写出第一行越界代码时。

---

## 2. 当前架构分析（实测 2026-09-23）

依赖现状**本来就是干净的**，与 v4/12 矩阵一致：

```
common → （无）
api    → common
svc-*  → common + api          （五个 svc 之间互不依赖 ✅）
app    → common + api + 全部 svc
app-gateway → svc-gateway
```

所以这次是**锁住现状**，不是清理欠账。父 POM 继承 `neargo-parent`，enforcer 插件与
`enforce-build-env`（锁 JDK 21）已经在跑，加执行即可，不必引新插件。

**`dependencyConvergence` 先探后用**：这条最可能一上来就红。实测（`fail=false` 探针）
**全部模块零冲突** —— Spring Boot BOM + MyBatis-Plus BOM 把版本管住了，所以直接开 `fail=true`，
是「保持现状」而不是「先欠着」。

---

## 3. 方案设计

### 3.1 两层规则

| 层 | 执行 id | 规则 |
|---|---|---|
| 父 POM（全模块继承） | `sharehub-global-rules` | `dependencyConvergence`（同构件两个版本即失败）· `banDuplicatePomDependencyVersions`（同 pom 写两遍）· `bannedDependencies`：禁 `ai.neargo.shop:*` |
| 各模块 | `module-boundary` | 该模块自己的边界 |

禁 `ai.neargo.shop:*` 是**前瞻性**的：今天一个都没用；[ADR-024](./ADR/ADR-024-共用服务暂由ai-shop托管-组件并入框架.md)
定了将来只放行三个服务客户端 / 契约（`pay-client` · `shop-job-api` · `shop-job-target`），
届时加进 `includes`。在那之前保持"一个都不许进来"，比事后再收紧容易。

### 3.2 各模块边界

| 模块 | 禁 | 理由 |
|---|---|---|
| `sharehub-common` | 全部 `ai.neargo.sharehub:*` | 最底层，被所有人依赖，反向依赖即成环 |
| `sharehub-api` | 全部 `sharehub:*`，**放行 common** | 契约层依赖了具体实现，Port 就失去「跨模块唯一通道」的意义 |
| `sharehub-svc-*`（5 个） | 其余四个 svc + 两个启动模块 | 业务域之间互不依赖，跨域只经 `sharehub-api` 的 Port |
| `sharehub-app` | `sharehub-app-gateway` | 两个启动模块互相依赖没有正当理由 |
| `sharehub-app-gateway` | 四个业务 svc + `sharehub-app` | 网关启动模块只带网关内核，不把业务域链接进来 |

### 3.3 一处有意放宽：`sharehub-app` → `sharehub-svc-gateway`

规则一加上就抓到了它。但**这是当前形态的有意为之**，不是欠账：

- 今天是单体形态，网关内嵌在本进程（[ADR-017](./ADR/ADR-017-单体与微服务双形态部署.md) 双形态：本地实现在 classpath 上就走本地）；
- v4/12 的矩阵描述的是 **S5 之后**的目标形态（网关独立部署 :8091，业务侧只经 `DeviceCommandPort` 远程调用）。

**现在就禁只会逼着把正常的单体形态绕过去。** 所以放宽，并在 `sharehub-app/pom.xml` 的注释里
写明「**S5 落地时把 `sharehub-svc-gateway` 加进 excludes**」—— 卡口留了准星，时机到了再扣扳机。

---

## 4. 验证（反向对照）

| # | 操作 | 期望 | 实测 |
|---|---|---|---|
| 1 | `dependencyConvergence` 探针（`fail=false`） | 看清欠账规模 | **零冲突**，可直接开 `fail=true` ✅ |
| 2 | 正常构建 `mvn -o -B validate` | 通过 | BUILD SUCCESS ✅ |
| 3 | 探针：`svc-core` 依赖 `svc-ops`（业务域互相依赖） | 拦住 | 退出码 **1**，报 `sharehub-svc-ops <--- banned` ✅ |
| 4 | 探针：`common` 反向依赖 `api`（成环） | 拦住 | 退出码 **1** ✅ |
| 5 | 清理探针 | 恢复 | BUILD SUCCESS ✅ |
| 6 | 全量测试 + arch-guard | 无副作用 | **145 通过 0 失败**；`arch-guard --strict` 绿 ✅ |

> **第 3、4 步第一次做时两个探针都没插进去**：脚本锚点用了 4 空格缩进，实际是 2 空格，
> Python 的 `replace` 静默无操作 —— 于是「退出码 0」看起来像规则没生效，其实是探针根本没写进文件。
> 这正是 [CLAUDE.md](../../CLAUDE.md) 记的那个坑。改法：**插入后先 `grep` 确认落点，再跑构建**。
> 如果不做这一步，我会误以为规则失效而去改规则 —— 改一个本来就对的东西。

---

## 5. 改了什么

| 文件 | 改动 |
|---|---|
| `backend/pom.xml` | 新增 `sharehub-global-rules`：`dependencyConvergence` · `banDuplicatePomDependencyVersions` · 禁 `ai.neargo.shop:*` |
| `backend/sharehub-{common,api,svc-platform,svc-core,svc-ops,svc-finance,svc-gateway,app,app-gateway}/pom.xml` | 各自的 `module-boundary` 执行（9 个） |

**没有改动任何业务代码，也没有改动任何依赖声明** —— 现状本来就合规。

---

## 6. B2 剩余两项

| 项 | 状态 |
|---|---|
| ArchUnit（控制器位置、Port 位置、禁全限定名、公共层隔离） | 待做，首跑必然大量违例，要先建 `known-*.txt` 基线 |
| **H2 测试隔离** | 待做。测试至今**直连开发库 `pb_core`**（`src/test/resources` 下只有 fixtures）。建议与 **B4 目录重排同窗口**做，否则测试要搬两次 |
