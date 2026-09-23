# TDD-mass-assignment-hardening（B3 · 实体直接作请求体的批量赋值）

状态：**已实现（底座部分）**（2026-09-23，153 个测试通过）
关联需求：[v4/09 C1](./v4/09-后端代码架构.md) · [v4/14 B3](./v4/14-执行任务清单.md)
创建日期：2026-09-23

---

## 1. 需求摘要

v4/14 B3：「78 处『实体直接作 `@RequestBody`』改 Req / Command，**先改带归属、状态、金额字段的实体**」。

---

## 2. 当前架构分析（实测 2026-09-23）

### 2.1 漏洞在哪

链路是统一的：控制器 `@RequestBody <实体>` → `service.save(body)` → `AbstractCrudService.save`。
**33 个 service 继承 `AbstractCrudService`**，所以问题与修法都有唯一落点：

```java
E current = selectByKey(no);
if (current != null) {
    body.setId(current.getId());                                              // 强制 ✅
    body.setVersion(current.getVersion());                                    // 强制 ✅
    if (body.getTenantId() == null) body.setTenantId(current.getTenantId());  // ⚠️ 只在客户端没传时
    beforeUpdate(body, current);                                              // 默认空实现
    mapper.updateById(body);                                                  // ⚠️ 客户端传什么写什么
}
```

MyBatis-Plus 的 `updateById` **只写非 null 字段** —— 换句话说，**客户端选择传哪些字段，就能改哪些字段**。
只有 `id` 与 `version` 被强制。于是：

| 能改什么 | 后果 |
|---|---|
| `tenantId`（传了就不被覆盖） | **把一行数据搬到别的租户** |
| `deleted` | 绕过归档语义直接软删 / 反删 |
| `createdAt` · `createdBy`（`@TableField(fill = INSERT)`，更新时不填充） | 伪造审计痕迹 |
| `agentNo` · `siteNo` 等归属 | **把资产划到别的代理名下** |
| `status` · `enabled` · `auditStatus` | 跳过审批直接置为已通过 |
| 金额 / 费率 / 额度 | 改计费与分润基数 |

攻击者不需要特殊权限 —— 有该资源 `update` 权限的最低角色即可越权（垂直提权 + 横向越权）。

### 2.2 面有多大（实测）

| 项 | 数 |
|---|---|
| `@RequestBody` 总数 | 155 |
| 其中参数类型是实体（`extends BaseEntity`）的**类型数** | **39** |
| 这些实体里带归属 / 状态 / 金额字段的 | **29** |
| 带**归属**字段的（风险最高） | 3：`AgtAccount` · `AgtCommission` · `PriceRule` |
| 继承 `AbstractCrudService` 的 service | 33 |

端点侧已有一处**局部**防护：更新端点普遍写了
`body.setXxxNo(pathVar); // 路径为准，忽略 body 里的键，防越权改他人账号` ——
说明当时意识到了"改别人的键"，但**其余字段仍是客户端说了算**。

---

## 3. 方案设计

### 3.1 选型：先堵还是先重构

| 方案 | 覆盖 | 代价 | 结论 |
|---|---|---|---|
| **A 只做 Req/Command 重构**（v4/09 C1 原文） | 彻底（白名单：只有声明过的字段能进来） | 约 72 个端点 + 39 个 DTO；控制器全改一遍，而 **B5 包归位还要再动一遍控制器** | ❌ 单独做太慢，且与 B5 撞车 |
| **B 只做集中加固** | 堵住 BaseEntity 层（租户 / 软删 / 审计）+ 各服务显式保护的域字段 | 小 | ❌ 黑名单会随新增字段退化：以后谁给实体加个字段，它**默认就是客户端可写的** |
| **C（推荐）先集中加固止血，再随 B5 增量重构，中间用棘轮托住** | 立即消除现网风险；DTO 迁移可度量、不回头 | 中 | ✅ 采用 |

**为什么 C 而不是 A**：这是**现网在跑**的越权面，先堵住比堵得漂亮重要（同 B1 的取舍）。
**为什么 C 而不是 B**：黑名单单独用会烂掉，所以必须配棘轮 —— 让「实体当请求体」的数量**只减不增**，
DTO 迁移才不会停在半路。

### 3.2 三件事

**① 集中加固 `AbstractCrudService.save`**（一处，保护 33 个 service）

更新分支把 BaseEntity 管的字段**一律**从 `current` 取，不再「客户端没传才取」：

```java
body.setId(current.getId());
body.setVersion(current.getVersion());
body.setTenantId(current.getTenantId());      // 不再判 null —— 传了也不算数
body.setDeleted(current.getDeleted());        // 软删只能走 archive/unarchive
body.setCreatedAt(current.getCreatedAt());    // 审计痕迹不可伪造
body.setCreatedBy(current.getCreatedBy());
```

`updatedAt` / `updatedBy` 不用管：它们是 `fill = INSERT_UPDATE`，`AuditMetaObjectHandler`
在更新时 `strictUpdateFill` 会覆盖客户端传的值。

**② 域字段用已有的 `beforeUpdate(body, current)` 钩子显式保护**

钩子本来就在（默认空实现），不引新机制、类型安全、不用反射：

```java
@Override
protected void beforeUpdate(AgtAccount e, AgtAccount current) {
    e.setAgentNo(current.getAgentNo());   // 归属不可经更新接口变更（划拨走专门的端点）
    e.setStatus(current.getStatus());     // 状态只能经状态机迁移
}
```

范围：**3 个带归属字段的实体必做**；状态 / 金额字段按「是否有专门的迁移入口」逐个判断 ——
有专门入口的（审批、划拨、状态机）一律在此保护，没有专门入口、本来就该在表单里改的（如字典的 `enabled`）不动。

**③ 棘轮：`@RequestBody` 实体类型数只减不增**

新增 `known-entity-request-bodies.txt` 记录当前 39 个类型；测试断言实际集合是台账的**子集**。
新写的端点想再用实体当请求体 → 测试红。DTO 迁移完一个就从台账删一个。

### 3.3 不在本次范围

| 项 | 去向 |
|---|---|
| Req / Command DTO 重构（39 个类型 / 约 72 个端点） | 随 **B5 包归位**同窗口做，避免控制器被动两次 |
| `SeedData` 移出主代码 | 随 **B4 目录重排**（`support/sharehub-seed`）；它本身只是内存数据持有者，写库的 6 个 Seeder 已被 `sharehub.seed.enabled` 门禁挡住（B1 已把默认值改为 `false`） |

---

## 4. 测试策略

| # | 场景 | 断言 |
|---|---|---|
| 1 | 更新时传 `tenantId` 为别的租户 | 库里仍是原租户 |
| 2 | 更新时传 `deleted=1` | 未被软删 |
| 3 | 更新时传 `createdAt` / `createdBy` | 未被改写 |
| 4 | 更新 `AgtAccount` 时传别人的 `agentNo` | 归属不变 |
| 5 | 更新 `AgtAccount` 时传 `status` | 状态不变（只能经状态机） |
| 6 | 正常字段（名称、备注等） | 正常写入 —— **加固不能把功能堵死** |
| 7 | 棘轮 | 台账外的实体请求体 → 失败 |

第 6 条必须有：只验「改不了」不验「还能改」，很容易把接口改成谁都写不进去还以为修好了。

---

## 5. 风险

| 风险 | 对策 |
|---|---|
| 某些业务**本来就靠**更新接口改归属 / 状态 | 逐个确认是否有专门入口再决定保护哪些字段；`beforeUpdate` 是逐服务的，不搞一刀切 |
| 前端依赖「整个实体回传」的写法 | 加固只影响**写入**，读取与响应不变 |
| 台账初值 39 会掩盖问题 | 台账里逐条写清来源，且**只准变短**；B5 迁移时逐条删 |

---

## 6. 实现任务

- [ ] T1 `AbstractCrudService.save` 集中加固（含注释说明每个字段为什么强制）
- [ ] T2 3 个归属实体的 `beforeUpdate` 保护
- [ ] T3 状态 / 金额字段逐个判断并保护
- [ ] T4 `known-entity-request-bodies.txt` + 棘轮测试
- [ ] T5 场景测试 7 条 + 全量回归
- [ ] T6 回填 v4/09 C1、v4/14 B3、实现状态总表

---

确认记录：2026-09-23 用户确认「先集中止血 + 棘轮」+「只保护有专门入口的字段」两项推荐方案。
**同日用户调整方向**：「目前核心是完成架构方案和底层的功能代码以及代码框架，以及脚手架开发，
具体的业务代码，在业务模块梳理完成后再启动」—— B3 据此在**底座/业务的分界线上收口**（见 §8）。

## 7. 实现记录（2026-09-23）

| 任务 | 落点 | 性质 |
|---|---|---|
| T1 | `AbstractCrudService.save` 集中加固：`tenantId` · `deleted` · `createdAt` · `createdBy` 一律从库取 | **底座**（一处保护 33 个 service） |
| T2/T3 | `AgentCommissionServiceImpl.beforeUpdate` 锁 `agentNo`；`AppVersionServiceImpl.beforeUpdate` 锁 `status` | 业务（仅这两处，见 §8） |
| T4 | `known-entity-request-bodies.txt`（39 条）+ `EntityRequestBodyRatchetTest` | **脚手架/卡口** |
| T5 | `MassAssignmentHardeningTest`（5 例）+ 反向对照 | **脚手架/卡口** |

**测试**：`mvn -o -B test` → **153 通过 0 失败**；`arch-guard --strict` 绿。

### 7.1 反向对照救回了一个「永远绿」的测试

第一版用 `MdBank` 做夹具，5 个用例全绿。**把加固还原成有漏洞的写法后，测试照样全绿** ——
说明它什么都没测出来。查下来：`md_` 前缀按设计是**全局表，根本没有 `tenant_id` 列**
（见 `V5__v2_platform_system.sql` 的表尾说明②），所以「租户没被改」不证自明。

改用 `sys_param`（带 `tenant_id` + `deleted` + `version`）并给每条断言加**前置条件**
（基准值非 null，否则测试报错而不是默默通过）。再做反向对照：**5 个用例红了 4 个**，
恢复加固后回到全绿。

> 这一步没有额外要求也该做：**一个永远绿的测试比没有测试更糟** ——
> 它会让后面的人以为这里有防护。只跑一次看到绿就收工，是发现不了的。

另一个顺带记下的坑：夹具清理不能用 `mapper.delete` —— `deleted` 带 `@TableLogic`，
那是**软删**，行还在、唯一键仍被占着，下次 insert 直接撞键。清夹具要绕过 ORM 物理删。

---

## 8. 在「底座 / 业务」分界线上收口（2026-09-23 方向调整）

用户把优先级调为**架构方案 + 底层功能代码 + 代码框架 + 脚手架**，业务代码等业务模块梳理完再启动。
B3 正好横跨这条线，故按性质切分：

| 部分 | 性质 | 本次 |
|---|---|---|
| `AbstractCrudService` 集中加固 | 底座 | ✅ 做完 |
| 棘轮台账 + 两个卡口测试 | 脚手架 | ✅ 做完 |
| `AgtCommission.agentNo` · `SysAppVersion.status` 保护 | 业务（但属止血） | ✅ 做完（仅此两处，都是「有专门迁移入口」的硬判据） |
| 其余 27 个带状态 / 金额字段的实体逐个判定 | **业务** | ⏸ 等业务模块梳理 |
| 39 个实体 → Req/Command DTO 重构 | **业务** | ⏸ 随 B5 包归位 |

**现在的防护水位**：BaseEntity 层（越租户、软删、审计伪造）已**彻底堵死**，对 33 个 service 一致生效；
域字段层只堵了两处最硬的。棘轮保证这个水位**不会再降**，且新端点不许再用实体当请求体。

### 8.1 收口时发现、但未处理的一项

`PriceRule.siteNo` 属于「归属类」字段，但**没有专门的重绑端点** —— 改价格规则适用哪个站点
本来就该在表单里选，所以不适用「从库里保留」的写法。它的真实问题是**另一类**：
更新时**新值没有按数据范围校验**（能不能把规则指到自己看不见的站点上）。
修法是校验而非保留，需要数据范围 API，留待业务模块梳理时一并处理。
