# TDD · 认证隔离 / 运行日志 / 操作审计 三方案

状态：**待确认**
创建日期：2026-09-24
参照：`ai-shop/backend/.../auth/AbstractTokenAuthFilter` · `ai-shop/deploy/tencent/logrotate`
权限真源：[功能权限清单](../requirements/功能权限清单.md) · [ADR-012 代理商模型](./ADR/ADR-012-代理商模型.md)

> 三份方案写在一起，因为它们共用同一个身份上下文：
> 认证决定「这是谁」，审计记「这个谁做了什么」，运行日志记「系统怎么了」。
> 分开写会把 `traceId` / `actor` / `clientCode` 这三条串起来的线拆散。

---

# 〇、先说现状：三件事已经做完了，不要重做

实测（2026-09-24，非文档记载）：

| 件 | 现状 | 结论 |
|---|---|---|
| **两条安全链** | `SecurityConfig` 已有 `@Order(1)` C 端 `/mp/**` + `@Order(2)` 运营端兜底 | ✅ 已分离 |
| **两个过滤器** | `ConsumerTokenAuthFilter` · `StaffTokenAuthFilter` | ✅ 已分离 |
| **账号三池** | `usr_user`（C 端）· `iam_employee`（员工）· `agt_account`（代理），互不关联 | ✅ 已分开 |
| **令牌池** | **共用一张 `sys_token` + 一个 `TokenStore` bean**，靠 `Realm` 区分 | ⚠️ 见 §一 |
| **运行日志** | 无 `logback-spring.xml`；systemd 全部进 journald；`deploy/tencent/logrotate/` 是**空目录** | ❌ 见 §二 |
| **操作审计** | `AuditTrailInterceptor` 已在写 `iam_audit_log`，只记 `/api/**` 的写操作且成功的 | 🟡 见 §三 |

**所以本方案的实际工作量比「从零设计」小得多**：§一 是补隔离的最后一段，
§二 是从零，§三 是补齐覆盖面。

---

# 一、C 端 / B 端权限方案

## 1.1 隔离到什么程度才够

三层，逐层收紧：

| 层 | 现状 | 目标 |
|---|---|---|
| **账号** | 三张表互不关联 | ✅ 维持。**不要**为了"统一用户"把三池合一 —— ai-shop 的原话是「店员先注册成消费者才能上班，是把雇佣关系塞进消费关系」 |
| **会话** | 同表 `sys_token`，`Realm` 区分 | ⚠️ 加**令牌前缀硬隔离**（见 1.3） |
| **鉴权** | C 端属主鉴权、B 端 RBAC | ✅ 维持。两者语义不同，不该统一 |

**为什么不做到"两张令牌表"**：`sys_token` 已有 `realm` 列，
拆表只在"C 端令牌要搬进 `pb_auth` 分库"时才有必要（那是[实现状态总表] §六 的生产化第 5 条）。
在分库之前拆表，等于为一次将来的搬迁先付两遍代价。
**本方案用前缀 + 过滤器双重校验达到同等强度**，把拆表留给分库那次一起做。

## 1.2 代码复用：照 ai-shop 抽公共骨架

ai-shop 的 `AbstractTokenAuthFilter` 把三端的公共流程提成基类，
**每端子类只实现三件事**：认哪个 `Realm`、审计里记什么操作端、怎么把会话身份变成可判权身份。

它的类注释写得很清楚，值得照抄这个判断：

> 三端的认证流程逐字相同：取 Bearer → 查会话 → 分辨「没带令牌」与「带了但会话没了」→
> 放进 SecurityContext → 设数据域 → 收尾清理。抄三份的话，将来任何一处改动
> （比如新增一种失败态）都要记得改三处 —— 而**漏掉的那一处不会报错**，
> 只会让某一端的行为与另外两端悄悄不同。

本仓现在正是"抄了两份"的状态。改造：

```
sharehub-common/auth/
├── AbstractTokenAuthFilter   新增：公共骨架（取令牌 → 查会话 → 校验 realm → 建 SecurityContext → finally 清理）
├── ConsumerTokenAuthFilter   改：extends，realm=CONSUMER，clientCode="MP"
└── StaffTokenAuthFilter      改：extends，realm∈{STAFF,AGENT}，clientCode="OPS"/"AGENT"，
                                  额外重算角色与数据域（PrincipalRefresher + PermVersion）
```

**三处差异保留为抽象方法**，不要用 if 判 realm —— 那会把"两端"重新压回一个类里，
等于没拆。

## 1.3 令牌前缀：让越界在源头失败

`TokenStore.newToken(Realm)` 已经按 realm 生成前缀（实测运营端是 `stk_`）。
把它变成**强约束**：

| Realm | 前缀 | 谁发 |
|---|---|---|
| STAFF | `stk_` | `/api/auth/login` |
| AGENT | `atk_` | `/api/auth/login`（代理账号） |
| CONSUMER | `ctk_` | `/mp/auth/login` |

过滤器在**查库之前**先看前缀，不符直接放行给 Spring Security 判未认证：

- 拿 C 端令牌打 `/api/**` → 前缀不符，**一次数据库都不查**
- 省掉的不只是一次查询：它让「跨端令牌」在日志里表现为一条明确的拒绝，
  而不是"查到了但 realm 不对"这种需要读代码才能理解的中间态

照 ai-shop 的处理：realm 不符时**什么都不做**，既不放行也不抛异常 ——
请求在后面被 Spring Security 判成未认证，最终 401 而不是「越权成功」。

## 1.4 C 端为什么不要 RBAC

C 端只有一种身份（消费者），它的权限问题是**属主**而非**角色**：
「这个订单是不是你的」，不是「你有没有查订单的权限」。

现状已经是这样（`RentController` 从 token 反查 `cUserNo`，不接受前端传值）。
**要守住的是不许退化**：任何 `/mp/**` 端点若开始接受前端传来的用户标识，
就是 IDOR。建议加一条卡口（见 §四 T1-4）。

## 1.5 AGENT 是第三种，不是 B 端的一种

`AGENT` 走运营端链（`/api/**`）但**数据范围强制 = 自己 `agent_no`**（ADR-012）。
它与 STAFF 共用过滤器是对的（都是 RBAC + 数据域），
但 `clientCode` 要分开 —— 审计里必须看得出「这条操作是代理自己做的还是运营做的」。

---

# 二、运行日志方案

## 2.1 现状：等于没有

- 无 `logback-spring.xml`，走 Spring Boot 默认（只有 console）
- systemd `StandardOutput=journal`，全进 journald
- `deploy/tencent/logrotate/` **空目录**

后果：**出了问题只能翻 journald**，而 journald 默认不按应用切分、不长期保留、
也不便于按 `traceId` grep。B7 做的 `traceparent` 透传因此发挥不出价值 ——
链路 id 落在日志里，而日志没有可检索的落盘。

## 2.2 落盘位置：与 ai-shop 同构

```
/data/log/powerbank/
├── app/        应用日志   sharehub-app.log        · 按天 + 200MB 切
├── ops/        运维日志   access.log / slow.log   · 供 logrotate 统一处理
└── error/      错误日志   sharehub-error.log      · 只收 WARN 以上，便于告警接
```

与 ai-shop 的 `/data/log/*/ops/*.log` 同一父级结构，
**同机共存时 logrotate 的通配可以一条覆盖两个项目**（见 2.4）。

## 2.3 保留与压缩策略（全部可配）

按你给的口径：**保留 20 天 · 超过 5 天自动压缩**。

用 logback 自身的 `SizeAndTimeBasedRollingPolicy` 做切分与总量上限，
用 logrotate 做压缩与删除 —— **两者分工而不是二选一**：

| 做什么 | 谁做 | 为什么 |
|---|---|---|
| 按天 + 按大小切分 | logback | 它知道什么时候一条日志写完，切在记录边界上；外部工具切会把一条多行异常栈劈成两半 |
| 压缩 5 天前的 | logrotate | `delaycompress` + `compresscmd`，不占应用进程的 CPU |
| 删除 20 天前的 | logback `maxHistory` **与** logrotate `rotate` 双保险 | 只靠一边：应用长期不重启时 logback 的清理不一定触发；只靠 logrotate 则应用自己切出来的文件名它未必匹配 |

配置项（`application.yml`，全部带默认值）：

```yaml
sharehub:
  log:
    dir: ${SHAREHUB_LOG_DIR:/data/log/powerbank}
    keep-days: ${SHAREHUB_LOG_KEEP_DAYS:20}        # 超过即删
    compress-after-days: ${SHAREHUB_LOG_COMPRESS_AFTER:5}  # 超过即压缩
    max-file-size: ${SHAREHUB_LOG_MAX_FILE:200MB}
    total-size-cap: ${SHAREHUB_LOG_TOTAL_CAP:10GB} # 兜底：磁盘写满比丢日志更糟
```

> **`total-size-cap` 是必需的，不是锦上添花**：一次异常风暴能在几小时内写满磁盘，
> 而磁盘满会让数据库也一起停。保留天数管的是正常情况，容量上限管的是异常情况。

## 2.4 logrotate 配置（`deploy/tencent/logrotate/powerbank`）

```
/data/log/powerbank/*/*.log {
    su root root
    daily
    rotate 20              # 与 keep-days 对齐
    compress
    delaycompress          # 当天不压，隔天再压 —— 正在写的文件压了会断
    compressoptions -5     # 5 天后才真正压缩：靠 delaycompress + 下面的 maxage 组合
    maxage 20
    missingok
    notifempty
    copytruncate           # 不 kill -HUP，应用无需支持重开文件
    dateext
    dateformat -%Y%m%d
}
```

⚠️ **`delaycompress` 只延迟一轮，不是 5 天**。要做到"5 天后压缩"，
需要 `logrotate` 的 `olddir` + 一个每日 cron 扫描，或直接用
`find /data/log/powerbank -name '*.log.*' -mtime +5 ! -name '*.gz' -exec gzip {} \;`。
**推荐后者** —— 一行 cron，语义与"5 天"逐字对应，不需要理解 logrotate 的轮次语义。

## 2.5 日志打印规范

### 五条硬规矩

| # | 规矩 | 为什么 |
|---|---|---|
| 1 | **禁止 `e.printStackTrace()` 与 `System.out`** | 它们绕过 logback，不落盘、不带 traceId、不受级别控制 |
| 2 | **异常必须带上下文，不能只 `log.error(e)`** | 「NullPointerException」这五个字对排障毫无帮助，要带业务键：`log.error("归还失败 orderNo={}", no, e)` |
| 3 | **不打印敏感信息**：手机号、身份证、令牌、支付凭据 | 已有 `NotifyTargets.maskTarget`，日志侧照用 |
| 4 | **参数化而非拼接**：`log.debug("x={}", x)` 不是 `log.debug("x=" + x)` | 拼接在日志级别关闭时仍然求值 |
| 5 | **每条 WARN/ERROR 都要能回答「谁该做什么」** | 否则它就是噪音，而噪音会让真正的告警被忽略 |

### 级别口径

| 级别 | 什么时候用 | 例 |
|---|---|---|
| ERROR | **需要人介入**，且不介入会持续出错 | 支付回调验签失败、outbox 投递转 DEAD |
| WARN | 异常但系统能自处理 | 重试成功、降级生效、审计写入失败 |
| INFO | **状态变更**，事后要能还原发生了什么 | 订单状态迁移、划拨生效、配置变更 |
| DEBUG | 排障用，生产默认关 | 取价引擎的命中过程 |

### 格式：必须带 traceId

```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n
```

`traceId` 来自 B7 的 `TraceContext`（已写入 MDC）。**没有它，跨进程排障就是逐个日志文件肉眼比时间戳**。

---

# 三、运营端操作日志（审计）方案

## 3.1 现状与缺口

`AuditTrailInterceptor` 已经在写 `iam_audit_log`，规则是：
`/api/**` + 写方法（POST/PUT/DELETE）+ **状态码 < 400** 才记。

表结构够用：`actor` / `actor_name` / `action` / `target_type` / `target_no` / `detail` / `ip` / `created_at`。

**三个缺口**：

| # | 缺口 | 后果 |
|---|---|---|
| A | **只记成功的** | 「谁试图删了什么但被拒」查不到 —— 而这恰恰是安全审计最想看的 |
| B | **不记改动内容** | `detail` 只有 query string，改前改后的值都没有。「谁把分润比例从 30% 改成 50%」答不出来 |
| C | **不覆盖 `/internal/**`** | 划拨回写、结算生成这些高影响操作不留痕 |

## 3.2 改造

**A：失败也记，但分开标。** 加 `outcome` 列（`SUCCESS`/`DENIED`/`FAILED`）。
403 与 400 的语义不同：一个是没权限（安全事件），一个是参数错（操作失误）。

> 不要因为"失败的不算改动"就不记。审计要回答的是**「谁试过什么」**，
> 不是「数据现在是什么样」—— 后者看业务表就够了。

**B：记关键字段的改前改后。** 不是全量 diff（那会把 `detail` 撑爆且大多无用），
而是**按资源声明关心哪几个字段**：

```java
@Audited(target = "share_rule", fields = {"rate", "mode", "status"})
```

没有注解的沿用现在的行为（只记 action + target）。**增量启用，不要求一次覆盖全部**。

**C：覆盖面从 `/api/**` 扩到 `/api/** + /internal/**`**，
但 `/internal/` 的 actor 是**服务**不是人 —— 记 `actor=SYSTEM:<serviceName>`，
与人的操作在同一张表但可区分。

## 3.3 保留策略

审计日志**不随运行日志一起删**。它是合规材料，保留期由业务定（建议 ≥ 1 年）。
但 `iam_audit_log` 会长得很快：

- 分区：按 `created_at` 月分区（表已有 7 张表用了 PARTITION，有先例）
- 冷热分离：3 个月前的转历史表或归档到对象存储
- **绝不软删**：审计记录被"删除"的能力本身就是审计漏洞

## 3.4 与运行日志的关系

| | 运行日志 | 操作审计 |
|---|---|---|
| 回答 | 系统怎么了 | 谁做了什么 |
| 落地 | 文件，20 天 | 数据库，≥1 年 |
| 丢了的后果 | 排障变难 | **合规问题** |
| 写失败时 | 可以丢 | 要告警（现在只 `log.warn`，见 §四 T3-4） |

**两者用同一个 `traceId` 串起来**：审计记录带上 traceId，
就能从"谁改了这条分润规则"跳到"那次请求的完整日志"。这是本方案三份合一的理由。

---

# 四、任务清单

## T1 · 认证隔离（3–4 天）

| # | 任务 | 判据 | 依赖 | 状态 |
|---|---|---|---|---|
| T1-1 | 抽 `AbstractTokenAuthFilter`，两个过滤器改为继承 | 两端行为不变；新增失败态只需改基类 | — | ✅ 2416ee2 |
| T1-2 | 令牌前缀硬校验（`stk_`/`atk_`/`ctk_`） | 拿 C 端令牌打 `/api/**` → 401 且**不查库** | T1-1 | ✅ 2416ee2 |
| T1-3 | `clientCode` 落进审计（OPS / AGENT / MP） | 审计里分得出代理自己做的与运营做的 | T1-1 · T3-1 | ✅ 439d105 |
| T1-4 | **卡口**：`/mp/**` 端点不得从请求体/参数取用户标识 | 反向对照：加一个接受 `userNo` 入参的探针 → 红 | — | ✅ 439d105 |

## T2 · 运行日志（2–3 天）

| # | 任务 | 判据 | 状态 |
|---|---|---|---|
| T2-1 | `logback-spring.xml`：三目录、按天+200MB 切、MDC traceId、`total-size-cap` | 本地起服后 `/data/log/powerbank/app/*.log` 有内容且带 traceId | ✅ 5001ffc |
| T2-2 | 五个配置项接 `application.yml`，全部带默认值 | 改环境变量能生效，不改代码 | ✅ 5001ffc |
| T2-3 | `deploy/tencent/logrotate/powerbank` + 压缩 cron（5 天）+ systemd 改 `StandardOutput=append:` | 服务器上跑一轮 `logrotate -f` 正常；5 天前的 `.log.*` 被 gzip | ✅ 5001ffc |
| T2-4 | **卡口**：禁止 `printStackTrace` / `System.out`（棘轮台账） | 反向对照：加一处 → 红 | ✅ 5001ffc |
| T2-5 | 日志规范写进 CLAUDE.md（五条硬规矩 + 级别口径） | — | ✅ 5001ffc |

## T3 · 操作审计（3–4 天）

| # | 任务 | 判据 | 状态 |
|---|---|---|---|
| T3-1 | 加 `outcome` 列（SUCCESS/DENIED/FAILED），失败也记 | 403 的操作能查到 | ✅ 9e9ae7f |
| T3-2 | `@Audited` 注解 + 改前改后快照（增量启用） | 分润规则改费率能查出改前改后 | ✅ 82c3370 |
| T3-3 | 覆盖 `/internal/**`，actor 记 `SYSTEM:<service>` | 划拨回写留痕 | ✅ 9e9ae7f |
| T3-4 | 审计写入失败改为**告警**而非 `log.warn` | 现在静默降级，等于审计可以悄悄失效 | ✅ 9e9ae7f |
| T3-5 | 月分区 + 归档策略 | — | ✅ 82c3370 |

> **T3-2 的实现与本表不同，照实记在这里。** 没有做 `@Audited` 注解自动 diff，
> 改成服务显式调 `AuditChanges.compare(旧, 新, 字段…)`。理由是自动 diff 要解决的两件事
> 都解决不好：「改前」没有通用取法（每个资源的主键/mapper/软删语义都不同），
> 而全量 diff 会把密码散列、令牌、大 JSON 列卷进 `detail` ——
> 与本方案 §五自己列的风险对策「按资源声明字段白名单」直接冲突。
> 显式记两件都天然解决：更新路径本来就查了旧实体，**白名单即调用**。
> 代价是逐个资源接入 —— 也就是本表写的「增量启用」。已接入：分润规则。
>
> **T3-5 的归档只加不删**：维护脚本自动补未来分区，但从不 DROP。
> 审计是争议时的证据，删它得有人明确决定；
> 归档命令（先导出验证、再 DROP PARTITION）写在脚本注释里手动执行。

## T4 · 与既有欠账合并后的完整清单

> 以下是把本方案并入[功能补齐路线](./TDD-功能补齐路线-数据层已就绪后的三类欠账.md)
> 与今天实测缺口后的全量视图。**按"会不会让人做出错误决定"排序**，不按工作量。

### 🔴 P0 · 正在误导人的

| # | 事项 | 状态 |
|---|---|------|
| P0-1 | 修对齐工具链 + 自测 | ✅ 已完成（b890bb1） |
| P0-2 | 修[实现状态总表](./实现状态总表.md)：7 个 `/mp` 端点标错、cs/report 标"待接线"实际已接、"131 条未接线"实际 6 条 | ⬜ |
| P0-3 | 经营看板环比 `+8.2%` / `+5.1%` 是**写死的假数据**（`app/page.tsx:40-41`） | ⬜ |

### 🟠 P1 · 用户点了就坏

| # | 事项 | 数 |
|---|---|---:|
| P1-1 | 运行期 404 的写端点：场地方 · 入驻登记 · 裂变规则 · 角色 · 钱包 | 11 |
| P1-2 | 菜单权限码与端点码不一致（`agent:settlement:read` 需产品裁决、`pricing:adjustment` 等 RolePerms 空出） | 2 |
| P1-3 | 合同 DTO 不带 `venueNo`/`siteNo`（合同是场地方分成唯一依据，只给名字会连错同名站点） | 1 |

### 🟡 P2 · 本方案三件

T1 认证隔离 · T2 运行日志 · T3 操作审计（明细见上）

### ⚪ P3 · 结构与存量

| # | 事项 |
|---|---|
| P3-1 | L1.5 词表收敛剩 80 条（其中 76 条在 ADR 圈住的域里） |
| P3-2 | B5 控制器下沉（34 个 `portal/`）· B4 目录重排 —— **需独占窗口** |
| P3-3 | V13 误建的 6 张表 JSON 影子列，删列迁移 |

### ⛔ 外部阻塞

支付/免押（ai-shop M1–M4）· access-gateway 南向 · 短信通道 U9 · Nexus 收口 U6

---

# 五、三个风险

| 风险 | 对策 |
|---|---|
| **T1-1 抽基类时两端行为悄悄漂** | 先给两个过滤器补行为测试（各 4 条：无令牌 / 令牌错 / realm 不符 / 正常），再抽基类 —— 测试不变才算重构成功 |
| **T2-3 改 systemd 输出目标会丢一段日志** | 改配置与重启之间的窗口；用 `append:` 而非 `file:`，并在切换前后各留一次 journald 快照 |
| **T3-2 快照把 `detail` 撑爆** | 按资源声明字段（白名单），不做全量 diff；`detail` 列虽是 longtext，但大字段会让审计查询变慢 |

---

确认记录：待用户确认
