# TDD-auth-security-hotfix（B1 · S0 安全止血）

状态：**已实现**（2026-09-23，全量 133 个测试通过）
关联需求：[v4/06 认证与权限 §〇](./v4/06-认证与权限.md)（A0 五条）· [v4/08 落地路线 S0](./v4/08-落地路线.md) · [v4/14 执行任务清单 B1](./v4/14-执行任务清单.md)
创建日期：2026-09-23
范围：**仅后端**。前端对应项（删 `role:"ADMIN"`、登出调后端、401 跳登录）是 W1，由前端会话做，需与本次同一窗口上线。

---

## 1. 需求摘要

v4/06 §〇 列了五条必须先修的问题，其中**第 2 条现网生效**：`powerbank.ichain.top` 上任何人都能用 `000000` 以任意手机号登录 C 端。

验收标准（来自 v4/06 §〇 与 v4/14 B1）：

1. 非开发环境用 `000000` 登录 **失败**；`POST /mp/auth/otp` **不再回传验证码**；
2. 运营端登录**永远校验凭据**，且登录后的角色**只由账号决定**，不采信前端传入；
3. 内存令牌**会过期**（滑动续期 + 绝对上限）；
4. 登出**真正吊销**服务端令牌；
5. 空库启动**不灌演示数据**（`seed.enabled` 默认 `false`）。

---

## 2. 当前架构分析（实测 2026-09-23）

| 位置 | 现状 | 问题 |
|---|---|---|
| `sharehub-svc-core/…/user/consumer/OtpService` | `issue()` 把固定码 `000000` 存进 `ConcurrentHashMap` 并返回；`verify()` **无条件接受** `000000`（`!otp.equals(DEV_MASTER)` 短路） | 现网任意手机号可登录；无有效期、无频控 |
| `sharehub-app/…/portal/core/ConsumerAuthController#otp` | 返回 `Map.of("sent", true, "devCode", code)` | 验证码**明文回传**给任何调用方 |
| `sharehub-svc-platform/…/platform/iam/AuthController#login` | `sharehub.admin.password` **为空时不校验密码**，且此时 `role` 取前端传入的值 | 漏配即「任意用户名 + 任意角色」直接发 token |
| `sharehub-common/…/auth/store/MemoryTokenStore` | `ConcurrentHashMap`，**无 TTL** | 令牌到进程重启前永不失效 |
| `sharehub-app/…/config/TokenStoreConfig` | 开关键写的是 **`powerbank.auth.token-store`**，而 `application.yml` 配的是 `sharehub.auth.token-store`；memory 分支 `matchIfMissing = true` | **切换开关是死的** —— 配 `redis`/`mysql` 也永远拿到无过期的 memory 实现（本次一并修，属同一问题的根因） |
| `application.yml` | `sharehub.seed.enabled: true`（注释却写「默认即关」） | 空库启动灌入演示数据 |
| 两个 `logout` 端点 | `AuthController#logout` / `ConsumerAuthController#logout` **已调用** `tokenStore.revoke()` | ✅ 后端无需改；缺的是前端不调（W1） |
| 6 个 Seeder | 都有 `@ConditionalOnProperty("sharehub.seed.enabled", havingValue="true")` | ✅ 门禁健全，只是默认值错 |

**复用机会**：`LocalCacheTokenStore`（ehcache 档）已实现「滑动续期 + 惰性过期」，`MemoryTokenStore` 的 TTL 可照它实现，不引新概念。

**现网影响评估**：生产已配 `SHAREHUB_ADMIN_PASSWORD`（见 `deploy/tencent/nginx/powerbank.ichain.top` 注释），故第 2 项改为 fail-closed **不会锁死现网**；`seed.enabled` 改默认为 `false` 后，因 Seeder 幂等（有数据即跳过），**存量演示数据不受影响**。

---

## 3. 方案设计

### 3.1 方案选型：开发便利开关怎么做

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A（推荐）单一开关 `sharehub.dev-mode.enabled`，默认 `false`**，统一门禁「固定 OTP 码 + OTP 回传 + 免密登录」，开启时启动打 WARN 横幅 | 一个概念、一处配置；生产「什么都不配」即全关；漏配的后果是**更严**而不是更松 | 本机想只开 OTP 不开免密登录时做不到 | ✅ 采用 |
| B 每项一个开关（`otp.master-code.enabled` / `auth.dev-login.enabled` …） | 粒度细 | 三个开关三处漏配风险；「安全默认」要维护三遍 | ❌ |
| C 绑 Spring profile（`@Profile("dev")`） | 不用新配置项 | 现仓库**只有一个 `application.yml`、没有任何 profile 文件**，等于要先引入 profile 体系；且 `SPRING_PROFILES_ACTIVE` 被误设为 dev 就全开 | ❌ 本次不引入 |

**失败方向（fail-closed）**：所有开关缺省即「关」；缺配置时**拒绝登录**，而不是放行。

### 3.2 模块设计

| 动作 | 模块 | 变更 |
|---|---|---|
| 新增 | `sharehub-common/…/auth/DevMode.java` | 读 `sharehub.dev-mode.enabled`（默认 `false`）的小组件；`isEnabled()`；`@PostConstruct` 开启时打 WARN 横幅。放 common 是因为 svc-core（OTP）与 svc-platform（登录）都要用 |
| 修改 | `OtpService` | ① 真实 6 位随机码（`SecureRandom`）；② 记 `issuedAt`，**5 分钟**有效；③ 校验成功或失败达上限即失效；④ 同手机号 **60 秒**内不重复发；⑤ 固定码 `000000` **仅** `DevMode.isEnabled()` 时接受 |
| 修改 | `ConsumerAuthController#otp` | `devCode` **仅** dev-mode 开启时返回；否则只回 `{"sent": true}` |
| 修改 | `AuthController#login` | 口令闸改 **fail-closed**：`sharehub.admin.password` 为空时，除非 dev-mode 开启，否则一律 `401`；`role` **一律**取 `sharehub.admin.role`，**删除**对 `in.role()` 的采信 |
| 修改 | `TokenStoreConfig` | `KEY` 由 `powerbank.auth.token-store` 改为 `sharehub.auth.token-store`（与 yml 一致，开关生效） |
| 修改 | `MemoryTokenStore` | 构造入参 `Duration ttl`；`Entry(data, expireAt)`；`get()` 惰性过期；`refresh()` 滑动续期；照 `LocalCacheTokenStore` 的写法 |
| 修改 | `application.yml` | `sharehub.seed.enabled: false`；新增 `sharehub.dev-mode.enabled: false`、`sharehub.auth.otp.*` 三项 |
| 修改 | `deploy/tencent/README.md` | 环境变量表补 `SHAREHUB_SEED_ENABLED`（演示机才设 `true`）、`SHAREHUB_DEV_MODE_ENABLED`（生产**绝不可**设）

**不改**：两个 `logout` 端点（已正确吊销）；6 个 Seeder 的 `@ConditionalOnProperty`（门禁本就正确）。

### 3.3 核心接口

```java
// 新增（sharehub-common）
public final class DevMode {                 // @Component
    public boolean isEnabled();              // sharehub.dev-mode.enabled，默认 false
}

// OtpService（签名不变，语义变严）
public String issue(String phone);           // 返回真实随机码；60s 内重复发 → IllegalStateException
public void verify(String phone, String otp);// 过期/错误/超次 → IllegalArgumentException

// MemoryTokenStore（新增构造参数）
public MemoryTokenStore(Duration ttl);
```

### 3.4 配置项（零硬编码）

| 键 | 默认 | 说明 |
|---|---|---|
| `sharehub.dev-mode.enabled` | `false` | 总开关：固定 OTP 码 + OTP 回传 + 免密登录。**生产绝不可开** |
| `sharehub.auth.otp.ttl` | `5m` | 验证码有效期 |
| `sharehub.auth.otp.resend-interval` | `60s` | 同手机号重发间隔 |
| `sharehub.auth.otp.max-attempts` | `5` | 单个码最多校验次数 |
| `sharehub.auth.token-ttl` | `2h` | **已存在**，本次让 memory 档也生效 |
| `sharehub.seed.enabled` | `true` → **`false`** | 演示种子 |

常量 `DEV_MASTER = "000000"` 保留为**常量**（不是魔法字符串），但只在 dev-mode 下生效。

---

## 4. 测试策略

新增 `sharehub-app/src/test/java/ai/neargo/sharehub/auth/SecurityHotfixTest.java`（复用现有 `ApiTestSupport`）：

| # | 场景 | 断言 |
|---|---|---|
| 1 | dev-mode 关 + `000000` 登录 C 端 | 400/401，**登录失败** |
| 2 | dev-mode 关 + `POST /mp/auth/otp` | 响应体**不含** `devCode` |
| 3 | dev-mode 开 + `000000` | 成功（保留本机联调能力） |
| 4 | 发码后用**真实码**校验 | 成功；再用同一码 → 失败（单次失效） |
| 5 | 60 秒内重复发码 | 拒绝 |
| 6 | `admin.password` 未配 + dev-mode 关 + 任意账号登录 `/api/auth/login` | **401** |
| 7 | `admin.password` 已配 + 正确口令 + 前端传 `role:"ADMIN"`（账号角色为 `VIEWER`） | 登录成功但**角色为 VIEWER**，不采信前端 |
| 8 | `MemoryTokenStore` TTL 到期 | `get()` 返回 empty |
| 9 | `MemoryTokenStore` 滑动续期 | `refresh()` 后未到期 |
| 10 | `sharehub.auth.token-store=ehcache` | 装配到 `LocalCacheTokenStore`（键名修复的回归） |
| 11 | 默认配置启动 | 无 Seeder bean（`seed.enabled` 默认关） |

**回归**：现有 23 个测试类全量跑通（其中 `RbacMatrixTest` / `DataScopeAuthTest` / `ConsumerRentFlowTest` 依赖登录链路，最可能受影响）。

---

## 5. 风险与注意事项

| 风险 | 对策 |
|---|---|
| **现网运营端登录被锁死** | 已核实生产配了 `SHAREHUB_ADMIN_PASSWORD`；发布前再 `ssh` 确认一次该变量非空，否则先补配再发 |
| 现有测试依赖「免密登录 + 前端传 role」 | 测试侧统一改为走 `ApiTestSupport` 并在测试配置里开 dev-mode；**这是修改已测功能，属 P6**，需确认 |
| C 端本机联调拿不到验证码 | dev-mode 开启时行为与今天完全一致；文档里写明本机怎么开 |
| 演示机（腾讯云）种子不再自动灌 | 在 `deploy/tencent/README.md` 标明演示机显式设 `SHAREHUB_SEED_ENABLED=true`；存量数据不受影响（Seeder 幂等） |
| OTP 仍是内存实现（重启丢失、多实例不共享） | 本次只止血；落库与频控属 A3，不在 B1 范围 |

### P6 修改确认（已测试功能）

⚠️ 本次会修改已有测试覆盖的登录链路：

- 目标：`AuthController#login`（现有 `RbacMatrixTest` 等 3 个测试类间接依赖）
- 修改原因：漏配 `admin.password` 时任意账号可登录，且前端可自选角色 —— 是 v4/06 §〇 第 1 条
- 变更：fail-closed + 角色只由账号决定
- 已验证无法「仅扩展不修改」：现行为本身就是缺陷，必须改语义
- 影响：测试需改为显式开 dev-mode 或配口令；**全量重测**

---

## 6. 实现任务

- [ ] T1 新增 `DevMode`（含启动 WARN 横幅）
- [ ] T2 `OtpService`：随机码 + TTL + 重发间隔 + 尝试次数 + 固定码仅 dev
- [ ] T3 `ConsumerAuthController#otp`：`devCode` 仅 dev 返回
- [ ] T4 `AuthController#login`：fail-closed + 角色只由账号决定
- [ ] T5 `TokenStoreConfig`：修 `powerbank.` → `sharehub.` 键名
- [ ] T6 `MemoryTokenStore`：TTL + 滑动续期
- [ ] T7 `application.yml`：`seed.enabled=false` + 新增配置项
- [ ] T8 新增 `SecurityHotfixTest`（11 个场景）
- [ ] T9 全量测试 + `deploy/tencent/README.md` 环境变量表
- [ ] T10 更新 v4/06 §〇、v4/14 B1 状态与[实现状态总表](./实现状态总表.md)

---

确认记录：2026-09-23 用户确认「单一开关 dev-mode」+「随机码 + 有效期 + 频控」两项推荐方案。

## 7. 实现记录（2026-09-23）

| 任务 | 落点 | 说明 |
|---|---|---|
| T1 | 🆕 `sharehub-common/…/auth/DevMode.java` | 默认 false；开启时 `@PostConstruct` 打 WARN 横幅 |
| T2 | `sharehub-svc-core/…/user/consumer/OtpService.java` | `SecureRandom` 6 位码 · 5 分钟有效 · 60 秒重发间隔 · 单码 5 次校验上限 · 成功即失效；固定码仅 dev-mode |
| T3 | `sharehub-app/…/portal/core/ConsumerAuthController.java` | `devCode` 仅 dev-mode 回传 |
| T4 | `sharehub-svc-platform/…/platform/iam/AuthController.java` | fail-closed；配了口令时角色只由 `sharehub.admin.role` 决定，`in.role()` 不再受理（record 字段保留，待前端改完再删） |
| T5 | `sharehub-app/…/config/TokenStoreConfig.java` | 键名 `powerbank.` → `sharehub.`；memory 档接入 `token-ttl` |
| T6 | `sharehub-common/…/auth/store/MemoryTokenStore.java` | TTL + 滑动续期 + 惰性过期（与 `LocalCacheTokenStore` 同语义） |
| T7 | `application.yml` | `seed.enabled` `true`→`false`；新增 `dev-mode.enabled` 与 `auth.otp.*` 三项 |
| T8 | 🆕 `SecurityHotfixTest`(3) · `OtpServiceTest`(7) · `MemoryTokenStoreTest`(5) | 新增 15 个用例 |
| T9 | `deploy/tencent/README.md` | 环境变量表补 4 行 |

**测试结果**：`mvn -o -B test` → **Tests run: 133, Failures: 0, Errors: 0**（原 118 + 新增 15）。

**两处按 P6 改了已测试功能**（原测试依赖旧的不安全行为）：

| 文件 | 改动 | 理由 |
|---|---|---|
| `ApiTestSupport` | `@SpringBootTest(properties = "sharehub.dev-mode.enabled=true")` | 16 个集成测试按 7 种角色免密登录；真实账号登录要等 A3 的凭据库。生产姿态由 `SecurityHotfixTest` 专门守（它刻意关掉 dev-mode） |
| `SmokeTest` | 同上 + `sharehub.seed.enabled=true` | 它自带 `@SpringBootTest`，且断言 `total==48`、`CAB1000` 吃的就是演示种子 |

**dev-mode 下保留「前端传角色」**：`AuthController` 的免密分支仍按 `in.role()` 发 token —— 多角色回归测试在 A3 之前只能这样跑；生产走不到该分支（dev-mode 默认关，且配了口令时口令分支优先）。

**本次顺带修的、原清单没列的缺陷**：`TokenStoreConfig` 的开关键名与配置文件不一致（`powerbank.` vs `sharehub.`），导致 token-store 切换开关**完全失效** —— 这正是「令牌永不过期」的根因。

## 7.1 上线后果与决定（2026-09-23 用户定：**方案 A，照常上线**）

**现网 C 端登录此前靠的就是 `000000` 这个后门**。本次修好后：验证码变成真随机码，而后端**没有任何短信通道**
（`NotifySendServiceImpl` 里是 `TODO(接入层)：调用渠道商投递`，只落 `notify_log` 不投递），接口也不再回传验证码 ——
**所以上线后生产环境的 C 端将无法登录**，直到短信通道接好。

| 选项 | 结论 |
|---|---|
| **A 照常上线** | ✅ **已选**。公网上「任何人可登录任意手机号」比「暂时登不进」严重得多；C 端演示暂停到短信通道接好 |
| B 演示号白名单（`otp.demo-phones` 用固定码） | 未采纳 |
| C 演示机开 dev-mode | 未采纳 —— 等于把后门原样留着 |

**由此产生的优先级变化**：短信通道（[v4/14](./v4/14-执行任务清单.md) 的 K7 组件 `neargo-notify` / N4）
从「马上要用」升为**C 端生产可用的前置**。在它落地前，C 端只能在开了 dev-mode 的本机环境联调。

**发布前必查**：`SHAREHUB_ADMIN_PASSWORD` 必须非空，否则运营端会被彻底锁死（fail-closed 的预期行为）。

---

## 8. 遗留（不在 B1 范围）

| 项 | 归属 |
|---|---|
| OTP 落库 + 真实短信通道（现为内存，重启丢失、多实例不共享） | A3 · N4 |
| 真实员工账号与凭据库（现仍是单一 admin 口令闸） | A3 |
| 前端：删 `ops-web/lib/auth.ts:27` 的 `role:"ADMIN"`、登出调后端、401 跳登录 | **W1，需与本次同窗口上线** |
| **短信通道**（没有它 C 端生产登录不了，见 §7.1） | `neargo-notify` / N4 —— 已升为 C 端生产可用的前置 |
| 测试仍连开发库 `pb_core`（未用 H2） | B2 |
| `OtpService`/`MemoryTokenStore` 的单测暂放在 `sharehub-app`（svc-core 与 common 无 test 依赖） | B2 |
