# TDD 补充 — 认证鉴权实现细节（数据权限拦截器 · C 端统一登录策略）

> 状态：草稿（待确认）· 创建 2026-07-12
> 下钻自：[TDD-认证鉴权.md](./TDD-认证鉴权.md)（§3.2 数据权限接线、§4.2 登录策略）
> 关联：[权限管理方案 §4.2/§5](./权限管理方案.md) · [db-design §二/§六](./db-design.md) · [功能权限清单 §二](../requirements/功能权限清单.md) · [ADR-012 代理商](./ADR/ADR-012-代理商模型.md)
> 两部分：**A. 数据权限 `DataScopeInterceptor`（运营端「能看哪些数据」）** · **B. C 端统一登录 5 策略（App/小程序/H5 归一）**。

---

# Part A · 数据权限拦截器（DataScopeInterceptor）

## A1. 目标与选型

**目标**：运营端（STAFF/AGENT）查询时，按当前登录人的**数据范围**自动向 SQL 追加归属条件（`agent_no`/`region_id`/`site_no`/…），与租户隔离同机制、防漏过滤。`AGENT` 强制只见自己 `agent_no`（ADR-012 红线）。

**选型**：复用 MyBatis-Plus 的 **`DataPermissionInterceptor` + `MultiDataPermissionHandler`**（表感知，回调给到 `Table` + 原 `where`，返回 JSqlParser `Expression` AND 进 WHERE）。不自己解析 SQL。

```java
// 注册顺序（MP 官方推荐）：租户 → 数据权限 → 分页
@Bean
MybatisPlusInterceptor mybatisPlusInterceptor(DataScopeHandler handler) {
  var it = new MybatisPlusInterceptor();
  it.addInnerInterceptor(new TenantLineInnerInterceptor(powerbankTenantHandler())); // tenant_id
  it.addInnerInterceptor(new DataPermissionInterceptor(handler));                    // ★ 数据权限
  it.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
  return it;
}
```

## A2. 数据范围模型（登录时解析、随会话缓存）

数据范围 = **角色级范围（`iam_data_scope`）** ∪ **身份级强制约束（AGENT realm 的 agent_no）**。多角色取并集，含 `ALL` 即放行。

```java
enum ScopeDim { ALL, REGION, SITE, LOCATION, VENUE, AGENT, SELF }   // 归属链维度

record ScopeRule(ScopeDim dim, Set<String> refs) {}                 // 一条：dim IN refs

record EffectiveDataScope(boolean all, List<ScopeRule> rules) {     // 多条 OR 组合
  static EffectiveDataScope ALL = new EffectiveDataScope(true, List.of());
}
```

**解析（登录时，`DataScopeResolver`）**：
```java
EffectiveDataScope resolve(Principal p) {
  if (p.hasPermission("*") || p.roles().contains("ADMIN")) return EffectiveDataScope.ALL;
  var rules = new ArrayList<ScopeRule>();
  // 1) iam_data_scope 用 subject_type(ROLE/EMPLOYEE) 统一承载：角色级默认 + 员工级覆盖（并集），DDL 已实现
  var rows = dataScopeMapper.bySubjects(ROLE, p.roleNos(), EMPLOYEE, p.employeeNo());
  for (var ds : rows) {
    if (ds.scopeType() == ALL) return EffectiveDataScope.ALL;       // 任一条 ALL → 全放行
    rules.add(new ScopeRule(ds.scopeType(), ds.refs()));            // REGION/SITE/LOCATION/VENUE（scope_refs JSON）
  }
  // 2) 身份级强制：AGENT realm → 只见自己 agent_no（覆盖式硬约束，ADR-012）
  if (p.realm() == AGENT) rules.add(new ScopeRule(AGENT, Set.of(p.agentNo())));
  // 3) 客服 SELF（经手）：如需
  return new EffectiveDataScope(false, mergeSameDim(rules));        // 同维度 refs 合并
}
```
- 结果存会话（Redis），随 `TenantContext.dataScope()` 承载，避免每请求查库。
- **AGENT 是覆盖式**：即便前端传 `agent_no`，以会话 `agentNo` 为准。

## A3. 表→锚点列注册表（关键）

不同表带的归属列不同，拦截器需知道**每表在各维度上用哪一列过滤**。集中注册（启动装配），**未注册的表视为全局表、放行不过滤**（如 `dict_*`/`tenant`/`iam_*`）。

```java
// DataScopeTableRegistry：tableName → (ScopeDim → 锚点列名)
register("dev_cabinet",  Map.of(AGENT,"agent_no", REGION,"region_id", LOCATION,"location_no", SITE,"site_no"));
register("loc_site",     Map.of(AGENT,"agent_no", REGION,"region_id", VENUE,"venue_no", SITE,"site_no"));
register("loc_location", Map.of(AGENT,"agent_no", REGION,"region_id", SITE,"site_no"));
register("ord_rent",     Map.of(AGENT,"agent_no", REGION,"region_id", LOCATION,"location_no", SITE,"site_no", SELF,"c_user_no"));
register("wo_order",     Map.of(AGENT,"agent_no", REGION,"region_id", LOCATION,"location_no", SITE,"site_no"));
register("share_record", Map.of(AGENT,"payee_no")); // dimension=AGENT 时 payee_no=agent_no
// dev_powerbank / dev_slot 随 cabinet；如无直接锚点则 A6 子查询兜底
```

> **依赖 db-design 的补充**（见 A7）：`ord_rent`/`wo_order` 现无 `agent_no`/`site_no` 列，需**冗余落列**（建单时从 cabinet/location 带出）——否则 AGENT 查订单/工单要走子查询，性能差。首期建议冗余。

## A4. Handler 实现（拼 SQL 表达式）

```java
@Component
class DataScopeHandler implements MultiDataPermissionHandler {
  public Expression getSqlSegment(Table table, Expression where, String msId) {
    var scope = TenantContext.dataScope();
    if (scope == null || scope.all()) return where;                 // 全放行 / 无上下文（系统操作）
    if (DataScopeContext.isSkipped()) return where;                 // 显式豁免（后台任务/事件消费）

    var anchors = registry.get(table.getName());
    if (anchors == null) return where;                              // 未注册 = 全局表，放行

    // 该表命中的 scope 规则 → 逐条建 (col IN refs)，OR 组合
    List<Expression> ors = new ArrayList<>();
    for (var rule : scope.rules()) {
      String col = anchors.get(rule.dim());
      if (col == null) continue;                                    // 本表没有此维度锚点 → 跳过该条
      ors.add(inExpr(table.getAlias(), col, rule.refs()));          // col IN ('a','b')
    }
    if (ors.isEmpty()) return and(where, FALSE);                    // 注册表但无一命中 → 1=0 兜底(拒绝, 防越权)
    return and(where, orAll(ors));                                  // where AND ( r1 OR r2 ... )
  }
}
```
- `inExpr` 用 JSqlParser 建 `InExpression(Column, ParenthesedExpressionList<StringValue>)`；`refs` 空 → `1=0`。
- **`1=0` 兜底是安全默认**：注册为受控表却匹配不到任何锚点，宁可查空也不放行（配置错误由 A8 测试兜住）。
- 别名安全：用 `table.getAlias()` 拼列，兼容 join 查询。

## A5. 生效范围与豁免

| 场景 | 是否过滤 | 机制 |
|------|---------|------|
| STAFF 非 ALL（OPS/BD 区域、CS SELF）| ✓ | 会话 scope rules |
| **AGENT** | ✓ **强制 agent_no** | realm 覆盖式规则 |
| ADMIN / ALL 角色 | ✗ | `scope.all()` 短路 |
| C 端（CONSUMER）| ✗（本拦截器）| C 端走 service 层显式 `c_user_no` 属主（[TDD §4.4](./TDD-认证鉴权.md)），不复用此拦截器 |
| 后台任务/事件消费/域间 internal | ✗ | `DataScopeContext.executeWithoutScope(() -> ...)` 显式豁免 |
| 跨租户超管（口子）| ✗ | 另经审计放行 |

```java
// 系统操作豁免（无登录人上下文的定时任务/事件消费必须豁免，否则查空）
DataScopeContext.executeWithoutScope(() -> orderMapper.selectOverdue());
```

## A6. 无锚点表的子查询兜底（可选，能冗余就冗余）

若某表确实无法冗余锚点列（如历史 `pay_order` 只有 `order_no`），AGENT 维度可退化为子查询：
```sql
-- pay_order 无 agent_no 时
order_no IN (SELECT order_no FROM ord_rent WHERE agent_no = ?)
```
由 `registry` 对该表登记 `AGENT → SUBQUERY(resolver)`，Handler 命中则建子查询 `Expression`。**首期尽量冗余落列避免子查询**（A7）。

## A7. 对 db-design 的补充（落地前置）

> 以下均**已并入 [ddl/](./ddl/) + [db-design.md](./db-design.md)（2026-07-12）**：
1. **`iam_data_scope` 统一 subject 粒度**：`subject_type(ROLE/EMPLOYEE)` + `subject_no` 一张表同时承载**角色级默认 + 员工级覆盖**（"张三管迪拜/阿布扎比"），`resolve()` 取并集——无需另建 `iam_employee_scope`。`scope_type` 补齐 `ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF`，`scope_refs JSON` 多值。
2. **`ord_rent` / `wo_order` 冗余归属列**：`site_no` 已有；增 `agent_no`（可空），建单/开单时从 `dev_cabinet`/`loc_location` 带出，供 AGENT 维度直接过滤，避免子查询。加索引 `(tenant_id, agent_no)`。

## A8. 测试策略（Part A）

- **单元**：`DataScopeResolver`（多角色并集、任一 ALL 短路、AGENT 覆盖）；`DataScopeHandler` 对各注册表生成的 SQL 片段（含 join 别名、空 refs→1=0、未注册表放行）。
- **集成（`@MybatisPlusTest`）**：
  - AGENT 登录 → `select * from ord_rent` 实际执行带 `and agent_no = ?`，**只返回本代理订单**；伪造 agent_no 参数无效。
  - OPS(REGION=迪拜) → 只见迪拜 `dev_cabinet`。
  - ADMIN → 无附加条件（全量）。
  - 定时任务豁免路径不带 scope 条件。
- **必测红线**：① AGENT 硬过滤不可绕过；② 未注册表不误伤；③ 系统任务豁免不查空。

---

# Part B · C 端统一登录 5 策略（App / 小程序 / H5 归一）

## B1. 归一契约

所有策略实现同一接口，产出**规范身份 `ResolvedIdentity`**；差异只在「校验凭据 + 取 openid/sub/phone」，之后 upsert 建户 + 签发同一 Bearer 完全统一（[TDD §4.1](./TDD-认证鉴权.md)）。

```java
interface ConsumerLoginStrategy {
  GrantType grantType();                       // WECHAT_MINIAPP/WECHAT_OAUTH/PHONE_OTP/APPLE/GOOGLE
  ResolvedIdentity authenticate(LoginReq req); // 校验 + 规范身份；失败抛 401
}

record ResolvedIdentity(
  Provider provider,        // WECHAT_MP / WECHAT_OA / APPLE / GOOGLE / PHONE
  String providerUid,       // 渠道内唯一：微信 openid / apple sub / google sub / phone 哈希
  String unionKey,          // ★ 跨渠道归并键：微信 unionid（有则用）；否则 provider+uid
  String phone,             // phone_otp 有；微信可经 getPhoneNumber 补；否则空
  String nickname, String avatar,   // 首建户填充，可空
  Map<String,Object> raw    // 渠道原始返回（风控/审计）
) {}
```

## B2. 身份归并与建户（`usr_identity` 新表 + upsert）

**问题**：`usr_user` 现仅单列 `openid` + `UK(tenant_id,openid)`，无法承载「一个人多渠道登录」（小程序 openid ≠ 公众号 openid，靠 unionid 才是同一人）。
**方案**：新增身份绑定表 `usr_identity`（1 用户 : N 渠道身份），`usr_user` 不再以 openid 为唯一键。

```
usr_identity  (子域 usr_，pb_core)          -- 对 db-design §六 的补充
  id, c_user_no, tenant_id,
  provider   VARCHAR(16)  -- WECHAT_MP/WECHAT_OA/APPLE/GOOGLE/PHONE
  provider_uid VARCHAR(128) -- openid / apple.sub / google.sub / hash(phone)
  union_key  VARCHAR(128)  -- 微信 unionid；其它 = 'APPLE:'+sub 等
  bound_at
  UK(tenant_id, provider, provider_uid)      -- 同渠道同 uid 唯一
  INDEX(tenant_id, union_key)                -- unionid 归并查找
```
> `usr_user` 调整：`openid`/`unionid` 列保留为"主渠道"冗余展示，**删除 `UK(tenant_id,openid)`**（改由 `usr_identity` 保证唯一）。

**upsert（`ConsumerAuthService.upsertByIdentity`）**：
```java
UsrUser upsertByIdentity(String tenantNo, ResolvedIdentity id) {
  // 1) 同渠道同 uid 已存在 → 直接命中
  var idt = identityMapper.find(tenantNo, id.provider(), id.providerUid());
  if (idt != null) return userMapper.byNo(idt.cUserNo());
  // 2) 靠 union_key 归并（小程序↔公众号 同 unionid → 同一用户）
  var merged = identityMapper.findByUnionKey(tenantNo, id.unionKey());
  String cUserNo = (merged != null) ? merged.cUserNo() : createNewUser(tenantNo, id); // 3) 否则新建
  identityMapper.insert(new UsrIdentity(cUserNo, tenantNo, id.provider(), id.providerUid(), id.unionKey()));
  if (id.phone() != null) piiService.upsertPhone(cUserNo, id.phone());  // 手机落 pb_pii(KMS)
  return userMapper.byNo(cUserNo);
}
```
- **归并前提**：微信开放平台已把小程序与公众号绑到同一主体（否则 unionid 不互通，退化为各自独立账户）——见 §10 待确认。
- 账户融合（phone 账户与微信账户事后合并）属 **P2 account-linking**，MVP 不做。

## B3. 五个策略实现

> 外部校验（微信 API / Apple JWKS / Google JWKS）封装在策略内；密钥/AppID 走 Nacos + KMS，**可租户级**（MVP 单 MAIN）。均处理：网络失败→503、验签失败→401、凭据单次性（防重放）。

### B3.1 `PhoneOtpStrategy`（App + H5 普通浏览器）
```java
ResolvedIdentity authenticate(LoginReq r) {
  String phone = E164.normalize(r.phone(), r.regionCode());     // 统一 E.164
  otpService.verify(SceneEnum.LOGIN, phone, r.otp());           // auth-core OTP：Redis 校验+单次失效+错误计数
  String uid = Hashing.sha256(tenantSalt + phone);              // 明文入 pii，uid 用确定性哈希做查找键
  return new ResolvedIdentity(PHONE, uid, "PHONE:" + uid, phone, null, null, Map.of());
}
```
- **发码** `POST /mp/auth/otp`（permitAll）：按 phone 60s/次、日限，H5 加图形验证/滑块防刷；短信网关 MENA（是否复用 neargo，待确认）。

### B3.2 `WechatMiniappStrategy`（小程序）
```java
ResolvedIdentity authenticate(LoginReq r) {
  var s = wechat.code2session(cfg(tenant).mpAppId, cfg(tenant).mpSecret, r.jsCode()); // {openid,unionid,session_key}
  redis.setex(sessionKeyOf(s.openid()), s.sessionKey(), 2h);    // 供 getPhoneNumber 解密（可选）
  return new ResolvedIdentity(WECHAT_MP, s.openid(),
      s.unionid() != null ? s.unionid() : "WXMP:" + s.openid(), null, r.nickname(), r.avatar(), raw(s));
}
```

### B3.3 `WechatOauthStrategy`（H5 微信内 / 公众号网页授权）
```java
ResolvedIdentity authenticate(LoginReq r) {
  assertState(r.state());                                        // 防 CSRF/重放（登录页下发、回调校验）
  var t = wechat.oauthAccessToken(cfg.oaAppId, cfg.oaSecret, r.code()); // {openid,unionid,access_token}
  var info = r.scope().userinfo() ? wechat.userinfo(t) : null;  // snsapi_userinfo 时取昵称头像
  return new ResolvedIdentity(WECHAT_OA, t.openid(),
      t.unionid() != null ? t.unionid() : "WXOA:" + t.openid(), null,
      info?.nickname(), info?.avatar(), raw(t));                 // ★ 与小程序同 unionid → 归并同一用户
}
```

### B3.4 `AppleStrategy`（App iOS / Web）
```java
ResolvedIdentity authenticate(LoginReq r) {
  var jwt = jwtVerifier(APPLE_JWKS)                             // JWKS 缓存
      .requireIssuer("https://appleid.apple.com")
      .requireAudience(cfg.appleClientId)                       // bundleId / serviceId
      .requireNonce(r.nonce())                                  // 防重放
      .verify(r.identityToken());
  String sub = jwt.subject();
  return new ResolvedIdentity(APPLE, sub, "APPLE:" + sub, null,
      r.fullName(), null, Map.of("email", jwt.claim("email"))); // 姓名仅首次授权返回，及时落库
}
```

### B3.5 `GoogleStrategy`（App Android / Web）
```java
ResolvedIdentity authenticate(LoginReq r) {
  var jwt = jwtVerifier(GOOGLE_JWKS)
      .requireIssuer("https://accounts.google.com")
      .requireAudience(cfg.googleClientId)
      .verify(r.idToken());
  String sub = jwt.subject();
  return new ResolvedIdentity(GOOGLE, sub, "GOOGLE:" + sub, null,
      jwt.claim("name"), jwt.claim("picture"), Map.of("email", jwt.claim("email")));
}
```

## B4. 统一装配与签发

```java
class ConsumerAuthService {
  Map<GrantType, ConsumerLoginStrategy> strategies;             // 启动注册
  LoginVO login(LoginReq req) {
    var id   = strategies.get(req.grantType()).authenticate(req);   // ← 端差异全在此
    var user = upsertByIdentity(req.tenantNo(), id);                // ← 统一建户/归并
    guard.assertNotBlacklisted(user);                               // 风控闸
    var tok  = tokenIssuer.issuePair(user.getCUserNo(), Realm.CONSUMER,
                 Map.of("channel", id.provider().channel(), "tenant", req.tenantNo())); // access+refresh
    audit.loginSuccess(user, id.provider(), req.clientIp());
    return new LoginVO(tok.access(), tok.refresh(), tok.expiresIn(), user.getCUserNo(), user.isNew());
  }
}
```
- **续期** `POST /mp/auth/refresh`：refresh_token 换新 access；App 长效、H5 短效（§5 三端矩阵）。
- **登出** `POST /mp/auth/logout`：`RevokeService` 吊销当前 token 会话。
- Token 校验之后完全统一（`ConsumerTokenAuthFilter` + 属主鉴权），三端无分叉。

## B5. 三端 grantType 路由（回顾）

| 端 | 可用 grantType | Token 存储 | CORS |
|----|---------------|-----------|------|
| App | `phone_otp` / `apple` / `google` | 原生安全存储 | 无 |
| 小程序 | `wechat_miniapp` | `wx.setStorage` | 无（域名白名单在小程序后台）|
| H5(微信内) | `wechat_oauth` | `localStorage` | ✓（H5 域名白名单）|
| H5(普通浏览器) | `phone_otp`（扫码未装 App 降级主路径）/ `google`/`apple`(Web) | `localStorage` | ✓ |

## B6. 测试策略（Part B）

- **单元**：每策略 authenticate（mock 微信 code2session/oauth、Apple/Google JWKS）——验签通过/失败/过期/nonce 不符/aud 不符；`E164.normalize`；`upsertByIdentity` 三分支（命中/unionid 归并/新建）。
- **归并集成**：先 `wechat_miniapp` 登录建户 → 再 `wechat_oauth` 同 unionid 登录 → **命中同一 `c_user_no`**（不重复建户）；不同 unionid → 独立用户。
- **端到端**：三种 grantType 各登录 → 拿 Bearer → 同一 `/mp/trade/orders` 均可访问且只见本人数据。
- **必测**：① unionid 归并；② OTP 单次失效+防刷；③ Apple/Google 验签严格（拒伪造 token）；④ 黑名单用户登录被拒；⑤ 三端登录后业务链路一致。

## B7. 对 db-design 的补充（落地前置，汇总）

> 均**已落地** ddl/ + db-design.md（2026-07-12）：

| 变更 | 表 | 说明 |
|------|----|------|
| **新增** | `usr_identity` | 多渠道身份绑定 + unionid 归并（B2）|
| 调整 | `usr_user` | 去 `UK(tenant_id,openid)`→改普通索引，唯一性移交 `usr_identity`；openid/unionid 降为主渠道冗余 |
| 统一粒度 | `iam_data_scope` | `subject_type(ROLE/EMPLOYEE)` 一表统角色/员工级；scope_type 补 `SITE/VENUE/AGENT/SELF`（A7）|
| 冗余列 | `ord_rent` / `wo_order` | 增 `agent_no`（`site_no` 已有），AGENT 数据权限过滤（A7）|

---

## 待确认（合并 A/B）

1. ~~归属列冗余 vs 子查询~~ → **已定冗余**（ord_rent/wo_order 加 agent_no，已落 ddl）。
2. ~~数据范围绑定粒度（角色 vs 员工）~~ → **已定统一**：`iam_data_scope.subject_type(ROLE/EMPLOYEE)` 一表并集（DDL 已实现）。
3. ~~首期数据权限维度~~ → **已定：全维度首期一起做**（ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF，2026-07-12 用户确认）。注册表 A3 需覆盖各维度锚点列（`dev_cabinet`/`loc_site`/`loc_location`/`ord_rent`/`wo_order` 均已具备 region_id/site_no/location_no/agent_no）。
4. **unionid 归并前提**：确认微信开放平台已绑定小程序+公众号同主体；未绑定则各自独立账户，是否可接受。
5. **OTP 短信网关**：MENA 手机号 OTP 走哪家（复用 neargo？），发码防刷策略（图形/滑块/频控阈值）。
6. **账户融合（account-linking）**：phone 账户与微信账户事后合并是否纳入 P2。
7. **未注册表默认策略**：确认「未注册=全局放行、注册但无锚点=1=0 拒绝」的默认符合预期。
