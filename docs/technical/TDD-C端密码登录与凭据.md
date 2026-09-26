# TDD-C端密码登录与凭据

状态：**已实现**（阶段 1 + 阶段 2，2026-09-26）
关联需求：[`功能清单-分级矩阵.md`](../requirements/功能清单-分级矩阵.md) **OPS-SYS-07 登录设置**（L1，「OTP / 密码 / Apple / Google 开关，按国家可配，下发 `/mp/auth/methods`」）
       · [`C端功能清单.md`](../requirements/C端功能清单.md) §1 C-AC-01 登录授权
关联现状：[`C端对齐现状-17模块.md`](C端对齐现状-17模块.md) §一 模块 1
创建日期：2026-09-26

---

## 1. 需求摘要

c-app 的**密码登录 / 忘记密码 / 改密**三个入口 UI 完整，后端三处一致如实 400。
目标是让这三个入口真的通，且受 **OPS-SYS-07 的按国家开关**管辖。

验收标准：
- 运营台把某国 `password_enabled` 拨开 → 该国用户能用密码登录；拨回去 → 端上入口消失、**后端也拒**
- 忘记密码：OTP 验证通过后由用户自设新口令
- 改密：校验旧口令
- 口令**绝不明文落库、绝不进日志**；错误文案不区分「号不存在」与「口令错」

---

## 2. 当前架构分析

### 2.1 ⚠️ 先纠正一处口径：**不需要新建 `pb_auth` 分库**

`ConsumerAuthController` 现在的 400 文案是「凭据库 pb_auth 未建」。**这个理由已经过期**：

| 事实 | 出处 |
|---|---|
| `cred_credential` **已建**，2026-09-26 落在 `pb_core` | 迁移 `V114__staff_credential_base.sql` |
| 它**本来就是按 realm 参数化**的：`realm VARCHAR(16) COMMENT 'STAFF/AGENT'`，表注释写着「员工与代理共用，realm 区分」，唯一键 `uk_realm_subject(realm, subject_no)` | 同上 |
| `CredentialService` 的每个方法第一个参数都是 `realm` | `platform/cred/service/CredentialService.java` |
| **「先在 pb_core 起一张平行凭据表、auth-core 落地时再迁数据」是已记录的决定** | [`docs/api/README.md`](../api/README.md) §口令 |
| `pb_pii / pb_auth` 分库是**架构级单独立项**（D4），且是「当前唯一一条没有任何卡口盯着的债」 | [`待办-状态机与领域缺口-执行计划.md`](待办-状态机与领域缺口-执行计划.md) D4 |

所以本方案 **= 给既有凭据表加一个 `realm=CONSUMER`**，不动分库。
分库该不该做是另一个议题（D4），**它不是密码登录的前置**；把两件事绑在一起，
会让一个一天的功能等一个架构立项。

### 2.2 相关现有模块

| 模块 | 路径 | 可复用程度 |
|---|---|---|
| `CredentialService` | `svc-platform/platform/cred/` | **直接复用**：BCRYPT、失败计数、锁定（锁定期内口令正确也拒）、`must_change` 全在 |
| `sys_login_setting` | 表建于 `V5`，`password_enabled TINYINT DEFAULT 0` | **直接复用**：⚠️ 当前**没有任何一处读它** —— 运营台那个开关拨了等于没拨 |
| `OtpService` / `OtpGate` | `svc-core/user/consumer/`，`app/config/OtpGateConfig` | **直接复用**：忘记密码的 OTP 走同一套（同一份频控与有效期） |
| `ConsumerAuthService` + 策略 | `svc-core/user/consumer/` | **扩展**：加一个 `PasswordStrategy`，与现有 5 个策略同形 |
| `IdentifierNormalizer/Hasher` | `common/identity/` | **直接复用**：手机号归一与 hash 已于 9-26 统一（ADR-030 §4.1） |

### 2.3 影响范围

- 新增 3 个端点（`/mp/auth/methods` · `/mp/auth/password/change`；`password/reset` 从 400 改成可用）
- `ConsumerAuthService.login` 加一道「登录方式是否开启」门禁 —— **改到了已测功能**，见 §5
- c-app 登录页的页签改为由服务端下发决定

---

## 3. 方案设计

### 3.1 方案选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A（推荐）复用 `cred_credential` 加 `realm=CONSUMER`** | 零新表；口令策略（锁定/算法/阈值）只有一份；表与服务本来就为多 realm 设计 | 与 auth-core 的最终形态仍差一次迁移（但那是**已记录的既定取舍**） | ✅ 采用 |
| B 新建 `pb_auth` 分库 + 库内凭据表 | 与目标架构一致 | 架构级立项（D4）：跨库事务、连接池、迁移工具链、回滚预案都要定；而密码登录一条都不依赖它 | ❌ 与本需求解耦，另立项 |
| C 在 `usr_user` 上加口令列 | 改动最小 | 口令策略变成两份（员工一份、消费者一份），必然分叉；`cred_credential` 的锁定/计数全要重写一遍 | ❌ |

### 3.2 分两阶段，第一阶段单独就能消除死路

**阶段 1：把开关接通（requirement OPS-SYS-07）**

> 即使阶段 2 不做，阶段 1 也让用户不再撞 400 —— 因为 `password_enabled` 默认 0，
> 页签会自己消失。**先做它，风险与收益都最好。**

- 新增 `GET /mp/auth/methods?country=`（permitAll）→ 读 `sys_login_setting`，
  命中国家行，否则回落 `country='*'` 默认行。出参：
  `{otp, password, apple, google, forceRealName, otpExpireSec}`
- `ConsumerAuthService.login` **服务端强制**：`grantType` 对应的开关关着 → 拒。
  只靠端上隐入口是不够的，客户端照样能直接发请求
- c-app 登录页页签由该接口驱动；`auth/forgot`、`me/change-pwd` 两个入口同样按它显隐

**阶段 2：CONSUMER realm 的凭据**

- `ConsumerCredentialGate`（端口，声明在 `svc-core/user/consumer/`）
  + `ConsumerCredentialGateConfig`（装配在 `sharehub-app`，委托 `CredentialService`）
  —— **理由与 `OtpGate` 完全相同**：`svc-core` 不能依赖 `svc-platform`（`ArchitectureTest`
  的零包循环硬规则），下沉到 `common` 会撞「零业务依赖」守卫 G4，
  只有 `sharehub-app` 同时看得见两边
- `PasswordStrategy implements ConsumerLoginStrategy`，`grantType()="password"`：
  归一手机号 → 按 `usr_identity` 找 `cUserNo` → `gate.verify("CONSUMER", cUserNo, pwd)`
- **注册带口令**：`RegisterReq.password` 现在被丢弃，改为「有则建凭据」
- **忘记密码**：`POST /mp/auth/password/reset` = 验 OTP → 自设新口令
- **改密**：`POST /mp/auth/password/change` = 校验旧口令（已登录态）

### 3.3 核心接口

```java
// 端口（svc-core）—— 参数第一位是 realm，与 CredentialService 同形，便于将来直连
public interface ConsumerCredentialGate {
    /** 校验。主体没有凭据时返回 null —— 调用方据此回「请用验证码登录」 */
    Check verify(String cUserNo, String rawPassword);
    /** 自设口令（注册 / 忘记密码 OTP 通过后）。已存在则覆盖 */
    void setPassword(String cUserNo, String rawPassword);
    /** 本人改密，校验旧口令 */
    void changePassword(String cUserNo, String oldPassword, String newPassword);
    record Check(boolean ok, String reason) {}
}
```

**⚠️ `CredentialService` 需要一个新方法 `setPassword(realm, subjectNo, raw)`**：
现有 `resetPassword` 是**服务端生成**一次性口令（对员工是对的：注释写着
「让调用方定初始口令，它多半会定成 123456」）。而消费者忘记密码必须自设。
做法是**新增方法而不是改 `resetPassword`** —— 后者有测试在用，加法不动它（P6）。

### 3.4 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `sharehub.auth.consumer-password.min-length` | 8 | 口令最短长度 |
| `sys_login_setting.password_enabled` | **0**（已有列） | 按国家开关，运营台可改 |

口令策略（锁定阈值/时长/BCRYPT）**沿用 `CredentialConfig` 既有配置**，不新增 —— 两套一定分叉。

---

## 4. 测试策略

| 场景 | 断言 |
|---|---|
| 开关关着 + 端上直接发 `grantType=password` | **拒**（不是登录成功） |
| 开关开着 + 口令正确 | 登录成功，落到同一个 `cUserNo`（与 OTP 登录同一个账号） |
| 口令错 N 次 | 锁定；**锁定期内口令正确也拒** |
| 该号没有凭据 | 回「请用验证码登录」，且**不泄露号存不存在** |
| 忘记密码：OTP 没过 | 改不动口令 |
| 忘记密码：OTP 过了 | 新口令可登录、**旧口令立刻失效** |
| 改密：旧口令错 | 拒且不改 |
| `/mp/auth/methods` 无该国行 | 回落 `*` 默认行 |
| 口令不进日志 | 扫 `LoggingConventionTest` 的敏感信息规则 |

负对照（逐条拆掉保护确认变红）：去掉服务端开关门禁 / 去掉锁定期判断 / 忘记密码不验 OTP。

---

## 5. 风险与注意事项

1. **⚠️ 会改到已测功能 `ConsumerAuthService.login`**（加开关门禁）。现有测试：
   `ConsumerRentFlowTest` / `ConsumerPhoneNormalizationTest` / `ConsumerPhoneIdentityTest`
   等都走这条路。默认 `otp_enabled=1`，所以门禁对 OTP 路径无行为变化；
   **但测试库若没有 `sys_login_setting` 的 `*` 默认行，OTP 登录会全红** ——
   迁移里必须幂等插入默认行（`otp=1, password=0`）。按 P6 需你确认。
2. **手机号探测**：密码登录失败与「号不存在」必须同一句文案、同一耗时量级
   （代理侧 `AgentLoginServiceImpl` 已有这个处理，照它做）。
3. **与 D4 的关系**：本方案在 `pb_core` 落 `realm=CONSUMER`，分库时随 `cred_credential`
   整表迁移，**不增加 D4 的工作量**（多一个 realm 的行，不是多一张表）。
4. **PDPL**：口令属凭据不属 PII，注销时随账号删除 —— 要在注销流程里加删凭据这一步。
5. 阶段 1 上线后运营台那个开关**第一次真的有效**了 —— 拨错会让一国用户登不进来，
   建议上线时确认默认行与各国行的取值。

---

## 6. 实现任务

**阶段 1（已实现，2026-09-26）**
- [x] 迁移 `V126`：幂等插入 `sys_login_setting` 的 `country='*'` 默认行（`otp=1, password=0`）
- [x] `GET /mp/auth/methods`（permitAll）+ 按国家回落
- [x] `ConsumerAuthService.login` 加开关门禁（服务端强制），经 `LoginMethodGate` 端口
- [x] c-app：登录页页签 / 忘记密码 / 改密入口按接口显隐
- [x] 测试 9 条 + 负对照 2 条；`check:drift` 六检查器全绿；`contract.json` 已重生成
- [x] `/mp/auth/methods` 登记进 `known-mp-unscoped.txt`（换谁登录都返回同一份）

> 实现时多修掉一处：`ConsumerLoginReq` 第 5 位原是 `regionCode`，**全仓无人读、也无人发**
> （c-app 发的是 `countryCode`，那个名字在 record 里不存在，被静默丢弃）。
> 顶掉它换成 `country`(ISO)，而不是并排再加一个 —— 两个都像「国家」的字段一定有人填错。

**阶段 2（已实现，2026-09-26）**
- [x] `CredentialService.setPassword(realm, subjectNo, raw)`（新增，`resetPassword` 未动）
- [x] `ConsumerCredentialGate` + `ConsumerCredentialGateConfig`（照 `OtpGate` 的理由与形状）
- [x] `PasswordStrategy`（`grantType="password"`），**不建户**：查不到身份就拒
- [x] `RegisterReq.password` 受理（`passwordStored` 如实回）；`password/reset` = 验 OTP + 自设；
      改密落在 `POST /mp/user/password`（**不是** `/mp/auth/*`，那整段 permitAll）
- [x] 迁移 `V128`：`cred_realm` 词表补 `CONSUMER`（列注释是本仓判定合法值的裁定依据）
- [x] 测试 10 条 + 负对照 3 条；全量 675 用例 BUILD SUCCESS；六个检查器全绿
- [x] 探测防护：三种失败同一句文案 + 无凭据时烧一次 BCrypt 对齐耗时

> **注销「删凭据」没有做，是有意的**：清除作业本身还不存在（`UserLogoffServiceImpl`
> 的 `TODO(清除作业)`），而冷静期内用户可以撤销 —— 申请时就删口令会把人锁在自己的
> 冷静期里、连撤销都登不进来。义务已写进那个 TODO 的注释，等清除作业落地时一并做。
> 没有在端口上留一个没人调的 `remove()`：那正是这轮一直在清的「看起来做了」。

### ⚠️ 实现时撞出的一个真缺陷（本方案之外）

`CredentialServiceImpl.verify` 原本是 `@Transactional`。C 端口令连错 5 次后**正确口令照样登得进去** ——
因为 `ConsumerAuthService.login` 也是 `@Transactional`，口令错时它往外抛 `BizException`，
把 `verify` 刚累加的 `failed_count` **一起回滚了**，库里查出来永远是 0，锁永远上不了。
代码、注释、员工侧的测试看起来都没问题（员工侧那条路没有外层事务）。
改成 `REQUIRES_NEW`，失败计数独立提交。**那条用例最初就是红的**，不是补上去凑数的。

---
确认记录：2026-09-26 用户确认（含 §5 的两处 P6 确认）
