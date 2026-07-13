# TDD — 分层后端实现 · 任务清单（权限/业务分离 + SecurityUtils 上下文）

> 状态：任务蓝图（2026-07-12）· 先梳理，后执行
> 目标：基于 [api/README](../api/README.md) + [db-design](./db-design.md) + [ddl/](./ddl/README.md) + [系统领域模型](./系统领域模型.md)，把后端从「Controller 直连内存/Mapper」重构为**规范分层**，且**权限与业务彻底分离**；业务取上下文经 `SecurityUtils` 静态工具。
> 现状：只有 `loc` 有 Service 层；其余域业务逻辑塞在 Controller（未分层）；无 SecurityUtils / 数据权限拦截器 / IdGenerator；多数域仍内存种子。鉴权 MVP 已有（[评审-遗留任务](./评审-遗留任务.md)）。

---

## 一、原则（不可违背）
1. **逐层单一职责**：`Controller`（薄：路由+`@PreAuthorize`授权+参数校验+编排）→ `Service`（全部业务规则）→ `Mapper/Entity`（数据）。Controller 不写业务，Service 不碰 HTTP/Security API。
2. **权限与业务分离**：
   - **授权**只在 Controller 层 `@PreAuthorize("@perm.can('码')")` + 框架（过滤链/数据权限拦截器）。**Service 不判功能权限**。
   - Service 只经 `SecurityUtils` 取**上下文**（我是谁 / 租户 / 数据范围 / agent_no）用于**数据过滤与审计**，不感知 Spring Security。
   - **数据权限**是横切拦截器（MyBatis），非每个 Service 手写 `where`。
3. **上下文经 `SecurityUtils`**：业务任何地方 `SecurityUtils.currentUserNo()/role()/agentNo()/tenantId()/dataScope()`，不 import `SecurityContextHolder`。
4. **对外契约不变**：`ApiResult{code,msg,data}` + `PageData{records,total,page,size}`，与 ops-web `http.ts` 对齐。业务键 `<x>_no` 用 `IdGenerator`。

## 二、目标分层与包结构
```
HTTP → SecurityFilterChain(认证:StaffTokenAuthFilter 写 StaffPrincipal)
     → Controller  @PreAuthorize 授权 + @Valid 校验 + 调 Service
     → Service     业务规则; 取 SecurityUtils.ctx; 发领域事件
     → Mapper      MyBatis-Plus; DataScopeInterceptor 自动拼 agent_no/region/site 条件
     → MariaDB pb_core
横切: support/SecurityUtils · support/StaffPrincipal · config/DataScopeInterceptor
      · aspect/@OperateLog 审计 · support/IdGenerator · config/PowerbankTenantLineHandler
      · common/{ApiResult,PageData,GlobalExceptionHandler}
```
每域包：`<domain>/{controller, service, service/impl, mapper, entity, dto}`。
横切包：`support/`（SecurityUtils/IdGenerator/上下文）、`auth/`、`config/`、`aspect/`、`common/`。

## 三、SecurityUtils / 上下文设计（T0.2 核心）
```java
// StaffTokenAuthFilter 认证成功后放入 SecurityContext 的 principal
record StaffPrincipal(String userNo, String username, String role,
                      Set<String> perms, String tenantId, String agentNo, DataScope dataScope) {}

// 业务层唯一上下文入口（静态；内部读 SecurityContext，业务不碰 Security）
final class SecurityUtils {
  static StaffPrincipal current();          // 无登录抛未认证
  static Optional<StaffPrincipal> currentOrNull();
  static String currentUserNo();  static String username();  static String role();
  static String tenantId();       // MVP 恒 MAIN
  static String agentNo();        // AGENT 强制自己；非代理为空
  static DataScope dataScope();   // ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF + refs
  static boolean hasPerm(String code);      // = PermChecker 语义（通配）
  static <T> T runAsSystem(Supplier<T> s);  // 系统任务绕过数据权限（如 seeder/定时）
}
// C 端并行：ConsumerContext.currentUserNo()/assertOwner(no)（Phase 2）
```
- `DataScopeInterceptor` 读 `SecurityUtils.dataScope()`，对已注册"锚点列"的表自动追加条件；未注册表放行；`AGENT` 强制 `agent_no=自己`。
- `@OperateLog` 切面读 `SecurityUtils.username()` 作 actor 落 `audit_log`。

---

## 四、任务清单

### Phase 0 · 横切地基（阻塞所有域，先做）
- [ ] **T0.1 StaffPrincipal 富主体** + `StaffTokenAuthFilter` 改为放 `StaffPrincipal`（含 perms/tenantId/agentNo/dataScope）。
- [ ] **T0.2 `support/SecurityUtils`**（§三 API）+ `PermChecker` 复用 `SecurityUtils.hasPerm`。
- [ ] **T0.3 `support/IdGenerator`**（业务键 `<x>_no`：前缀+ULID/雪花）；替换各域手拼编号。
- [ ] **T0.4 `config/PowerbankTenantLineHandler`**（MyBatis-Plus，tenant_id 自动注入，MVP 恒 MAIN；全局表放行）。
- [ ] **T0.5 `config/DataScopeInterceptor`**（MyBatis 内部拦截器 + 锚点列注册表；AGENT/REGION/SITE/VENUE/SELF；`runAsSystem` 绕过）。设计首期全维度（记忆已定）。
- [ ] **T0.6 全量 DDL 入库**：`ddl/*.sql` 建 `pb_core` 全表（现仅 loc 4 表）；应用 `spring.sql.init` 或迁移脚本；各域可落库。
- [ ] **T0.7 `iam_` 权限落库 + `RolePerms` 改从库**：`iam_role/iam_role_permission/iam_data_scope` 种子；`PermCache`（角色→权限码，Redis/内存缓存）取代 `RolePerms` 静态 Map（唯一权威）。
- [ ] **T0.8 审计切面** `aspect/OperateLogAspect`（`@OperateLog` → `audit_log` WORM，actor 取 SecurityUtils）；覆盖 create/update/delete/audit/intervene/refund/command。
- [ ] **T0.9 统一 BaseEntity/审计字段填充**（created_at/updated_at/version/deleted + tenant_id）`MetaObjectHandler`；抽 `AbstractCrudService<E,D>` 复用分页/upsert/实体↔DTO。

### Phase 1 · 逐域逐层（每域"四件套"，替换内存种子 → DB；顺序按依赖）
> 每域 DoD 见 §五。顺序：

| 序 | 域 | 内容 | 依赖 |
|----|----|------|------|
| 1 | **loc**（补全）| 现有 Service 补 venue/contract 写 + 抽 CRUD 基类 + 数据权限接入 | T0 |
| 2 | **agt** 代理 | agent/account 实体+Mapper+Service+Controller；AGENT 数据范围锚点 | T0.5 |
| 3 | **iam** 员工权限 | employee/dept/role/role_perm/data_scope 落库；角色维护 API；替换静态 RolePerms | T0.7 |
| 4 | **dev** 设备 | cabinet/slot/powerbank/shadow/ota/alert；远程指令编排（转 gateway 占位）| T0 |
| 5 | **gw** 网关 | vendor/vendor_config（KMS 占位）；driver 注册 | T0 |
| 6 | **trade** 订单/计费/支付 | ord_rent 状态机 + price_plan + PaymentPort(Stub) + pay 引用 | T0 |
| 7 | **finance** 账务分润 | acct 复式 + share_rule/record + stl_settlement/withdrawal + 审核编排 | 6 |
| 8 | **user** 用户 | usr_user/credit/wallet/coupon；风控拉黑 | T0 |
| 9 | **wo** 工单 | wo_order 状态机 + dispatch/sla/handle；告警联动占位 | 4 |
| 10 | **ad/platform** | ad_*（P2 建位）+ notify_template/dict/region | T0 |

### Phase 2 · C 端 / 南向 / 开放（另一轨，可后置）
- [ ] **consumerChain**：`/mp/**` 链 + `ConsumerTokenAuthFilter` + `ConsumerContext(currentUserNo/assertOwner)` 属主鉴权（无 RBAC）。
- [ ] **C 端统一登录**：`/mp/auth/login` + 5 策略（phone_otp/apple/google/wechat_miniapp/wechat_oauth）+ `usr_identity` unionid 归并。
- [ ] **access-gateway 鉴权**：TCP 首帧 / MQTT JWT+ACL / HTTP driver.verify 验签。
- [ ] **openapi**：AppKey 签名 + scope + 限流。

### Phase 3 · 收尾
- [ ] 单元/切片/集成测试（每域 Service 业务规则 + 鉴权矩阵 + 数据权限）。
- [ ] `powerbank-common-api` 抽权限码常量/DTO 契约（前后端同源）。
- [ ] ops-web 各页对齐真域（错误态/校验/分页），去内存残留。

---

## 四·执行进展 & 阻塞（2026-07-12）
**已按分层落地（entity/mapper/service/impl/controller/seeder）**：`loc`（早前）+ **`agt`/`dev`/`wo`**（本轮）；`wo` 含独立 `WoStateMachine`（非法迁移拒）。编译通过、seeder 落库（agt 9 / dev 48 / wo 64）。OpsController 场所/设备/工单端点已薄化转 Service。
**✅ 阻塞已解除（2026-07-12 晚）**：先前 `DataPermissionInterceptor` 破坏一切带参查询（`No value specified for parameter 2`）的问题，已由权限对话**重写 `auth/DataScopeHandler`** 修复——`MultiDataPermissionHandler` 现只返回**追加条件段**（null 或纯 append），不再返回原 where（旧 bug：原 where 被 MP 二次 AND → 占位符翻倍/参数错位）。实测全通：`?keyword=` 分页、`selectOne` 详情、工单派单（状态机 CREATED→DISPATCHED）、下发指令、代理商 upsert（新建 AG010）均 `code:0`。业务代码无需改。
**端到端集成测试已交付（2026-07-12，全绿 26/26）**：`src/test/`
- 测试数据 fixture：`resources/fixtures/operator-daily.json`（MENA/Dubai/AED；固定业务键 upsert 幂等可重复）。
- 基类 `support/ApiTestSupport`（`@SpringBootTest(RANDOM_PORT)` + JDK HttpClient，login/get/post/okData 助手，载 fixture）。
- `scenario/OperatorDailyFlowTest`（7 步叙事，多角色）：登录→/me 校验权限→工作台(AED)→设备巡检(列/筛 OFFLINE/详情含仓位)→远程 REBOOT 指令→取 CREATED 工单派单+重复派单被状态机拒(400 非法迁移)→BD 建代理商/站点/点位(幂等)+关键词复查→财务审核提现。
- `scenario/RbacMatrixTest`（9）：401 未认证 / 403 越权(VIEWER 派单·OPS 建站·FINANCE 下指令·CS 审提现) / 200 有权(OPS 指令·FINANCE 提现·ADMIN 全通)。
- `scenario/DataScopeFlowTest`（2）：AGENT(AG002) 仅见自己 `loc_site`、ADMIN 见全量；数据范围+关键词叠加仍隔离。
- **坑（已在测试内注释）**：`@PreAuthorize` 在请求体反序列化**之后**执行 → 越权测 create-site 须传**完整可反序列化 Site**（原始 int 字段齐全），否则先 500(null→int) 测不到 403。
**继续**：`user/trade/finance/platform` + C 端 `/mp` 按同「四件套」复制。

## 五、每域"四件套" DoD（Definition of Done）
一个域完成 = 全部满足：
1. `entity/*`（MP 实体，镜像 ddl）+ `mapper/*`（BaseMapper）。
2. `service/XxxService`（接口）+ `service/impl/XxxServiceImpl`（业务规则；取 `SecurityUtils` 上下文；发领域事件；**不判功能权限**）。
3. `controller/XxxController`（薄；每写接口 `@PreAuthorize("@perm.can('码')")`；`@Valid`）。
4. 内存种子迁移到 DB（seeder 幂等或 DDL data）；DataScopeInterceptor 锚点列已注册。
5. 审计：写操作挂 `@OperateLog`。
6. 测试：Service 业务单测 + Controller 鉴权切片测（该角色 200 / 越权 403）。
7. ops-web 对应模块联调通过（USE_MOCK=0）。

## 六、执行顺序与依赖
`Phase 0 全部` → `loc/agt/iam`（打通分层+权限+数据范围样板）→ `dev/gw/trade/finance/user/wo/ad`（复制样板）→ `Phase 2/3`。
**Phase 0 是硬前置**：SecurityUtils/数据权限/审计/DDL 未就绪前不逐域铺开，避免返工。

## 七、验收（整体）
- 分层：Controller 零业务（无 stream/selectPage/new Entity）；业务全在 Service。
- 权限分离：Service 内 0 处 `@PreAuthorize`/`SecurityContextHolder`；授权全在 Controller；上下文全走 SecurityUtils。
- 数据权限：AGENT 登录只见自己 `agent_no` 数据（拦截器实测）；系统任务 `runAsSystem` 绕过。
- 全域落 MariaDB，重启存活；ops-web 全模块联调通过；鉴权矩阵 + 数据范围测试绿。

## 八、待确认（不阻塞开工）
1. `IdGenerator` 用 ULID 还是雪花（对齐 neargo `IdGenerator`）？
2. `iam_role_permission` 落库后，内置角色是否仍 seed 静态映射（本文：seed 入库，静态 RolePerms 退役）？
3. 领域事件先 `DomainEventPublisher` 进程内（Logging）还是直接接 Kafka？（本文：先进程内）
