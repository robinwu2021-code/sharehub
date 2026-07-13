# neargo commons 上推 PR 说明 —— RBAC(`@perm`) + 数据权限引擎

> 面向 neargo commons 维护者评审。来源：powerbank 沉淀的两项**通用**权限能力，neargo 现缺。
> 变更仓库：`ai-neargo`（`commons/neargo-common-security`、`commons/neargo-common-data`）。
> 参考消费方：powerbank（已依赖并跑通，29 测试全绿）。设计依据：powerbank `docs/technical/权限能力上推清单.md`、`权限SPI契约与模块落位方案.md`。

---

## 1. 背景与动机

neargo commons 已有会话/Token/OTP/realm 分链的完整基座（`SessionStore`/`OpaqueTokenAuthFilter`/`Session(roles+storeScope)`/`Realm`/`TrustedHeadersRequestWrapper`/auth-core），但**缺两层通用能力**：
1. **RBAC 功能鉴权**：`OpaqueTokenAuthFilter` 只把 `roles` 当 authorities，**无权限码层**、无通配 `hasPerm`、无 `@perm`。
2. **通用数据权限引擎**：`common-data` 只有租户 `NeargoTenantLineHandler`；`Session.storeScope` 只是**数据**，**无"范围→SQL"的执行引擎**。

本 PR 补齐这两层，**纯增量、SPI 化、不依赖任何 app 的 IAM 表**。

---

## 2. 变更清单

### 2.1 `neargo-common-security` 新增 `ai.neargo.common.security.rbac`
| 类 | 说明 |
|----|------|
| `AuthSubject`(record) | 解析入参：realm(String)/subjectId/roles/tenantId/attributes（realm 无关，attributes 承载 app 扩展）|
| `PermissionResolver`(SPI) | 主体 → 权限码集合。**app 用自己的角色-权限表实现**并注入 |
| `PermissionCarrier`(接口) | principal 实现之，`@perm` 直接读其权限码 |
| `Permissions`(util) | 通配匹配（`*`/`device:*`/`device:cabinet:*`；精确 或 pattern 去尾前缀）|
| `PermChecker`(bean `perm`) | `@PreAuthorize("@perm.can('码')")`；读 principal(PermissionCarrier 优先，否则 authorities)|
| `RbacAutoConfiguration` | 注册 `@perm` + 默认 `PermissionResolver`(roles 即 perms)，均 `@ConditionalOnMissingBean` |

`AutoConfiguration.imports` 追加 `...rbac.RbacAutoConfiguration`。无新增依赖（复用 spring-security-core provided）。

### 2.2 `neargo-common-data` 新增 `ai.neargo.common.data.scope`
| 类 | 说明 |
|----|------|
| `DataScopeSpec`(record)+`Rule` | 通用数据范围；**dim 用 String** 便于 app 扩展维度（通用 ALL/REGION/DEPT/SELF；app 加 AGENT/SITE 等）|
| `DataScopeContext` | ThreadLocal 承载当前范围 + 豁免开关（**仿 `TenantContext`**）。app 认证过滤器 `set`/请求末 `clear` |
| `DataScopeResolver`(SPI) | 主体 → `DataScopeSpec`。app 实现 |
| `DataScopeTableRegistry` + `DataScopeRegistrar`(SPI) | 表→(维度→列)；app 经 registrar 注册自有表 |
| `DataScopeHandler` | MyBatis-Plus `MultiDataPermissionHandler`；读 `DataScopeContext` 注入 SQL。**只返回追加段/null**（避免 MP 二次 AND 致占位符翻倍）|
| `DataScopeAutoConfiguration` | 注册上述 bean（收集所有 `DataScopeRegistrar`）+ 默认 resolver(ALL)|

`AutoConfiguration.imports` 追加 `...scope.DataScopeAutoConfiguration`。复用 common-data 已有 mybatis-plus/jsqlparser，**不引 spring-security**（handler 走 ThreadLocal 而非 SecurityContext）。app 仍自行把 `new DataPermissionInterceptor(dataScopeHandler)` 加进其 `MybatisPlusInterceptor`（控制拦截器顺序）。

---

## 3. 关键设计决策（评审重点）

1. **不依赖 app 业务表**：RBAC/数据范围的**数据来源全经 SPI**（`PermissionResolver`/`DataScopeResolver`/`DataScopeRegistrar`）。commons 只定契约 + enforcement，**永不 import IAM 表**。
2. **默认可用 + 可覆盖**：`@ConditionalOnMissingBean` 提供退化默认（roles 即 perms、全放行 ALL），app 提供实现即覆盖 → **对现有 neargo app 零影响**（不实现 SPI 就是旧行为）。
3. **datascope 走 ThreadLocal 而非 SecurityContext**：与 `NeargoTenantLineHandler` 读 `TenantContext` 一致，**避免 common-data 依赖 spring-security**。app 过滤器把主体范围 `set` 进来。
4. **维度 dim 用 String 不用 enum**：让各 app 扩展维度（powerbank 的 AGENT/SITE）无需改 commons。
5. **`DataScopeHandler` 返回契约**：`MultiDataPermissionHandler` 只能返回"追加段"或 null；返回原 where 会被 MP 再 AND 一次 → WHERE 重复、占位符翻倍（`No value specified for parameter`）。已按此实现（powerbank 曾踩坑，已修）。
6. **与 storeScope 的关系**：`DataScopeSpec` 可与 `SessionClaims.STORE_SCOPE` 对齐（app 在登录时把 resolver 结果落 storeScope claim / 或过滤器直接 set）。本 PR 先出引擎，storeScope 承载方式留 app 决定。

---

## 4. 兼容性 / 影响

- **纯增量**：新增两个子包 + 两条 auto-config；未改任何现有类。
- **默认零影响**：现有 neargo app 不实现 SPI → 默认 bean 生效（roles 即 perms、全放行），行为不变；不加 `DataPermissionInterceptor` 则 datascope 完全不参与。
- **依赖**：security 无新增；data 无新增（复用已有 MP/jsqlparser）。

---

## 5. 如何验证

- 单测：`Permissions.matches` 通配；`DataScopeHandler.getSqlSegment`（注册表命中→`col IN(refs)`、未注册→null、ALL/豁免→null、无命中→`1=0`）。
- 集成参考：powerbank 消费方——`LoginUser implements PermissionCarrier`、`PermissionService implements PermissionResolver+DataScopeResolver`、过滤器 `DataScopeContext.set(...)`、`DataScopeRegistration implements DataScopeRegistrar`；`@PreAuthorize("@perm.can(...)")` + AGENT 数据范围硬过滤均实测通过（29 测试全绿）。
- 本机 install：`mvn -o -pl commons/neargo-common-security,commons/neargo-common-data -am install`（JDK21）。

---

## 6. 后续（本 PR 不含）

- `SecurityUtils`（当前主体读取门面）+ 口径B（`PermVersion`/`PrincipalRefresher` 会话即时刷新）——powerbank 本地已有，可作下一 PR（U3）。
- Ehcache/MySQL 会话后端（powerbank 已有，可选补进 neargo `SessionStore` 家族）。
- IAM 管理（角色/菜单/数据范围 CRUD + 表）**不上推 commons**（有表业务，属各 app 或独立 `neargo-iam` 业务模块，见 powerbank ADR-015 / 权限能力上推清单 §4.1）。
