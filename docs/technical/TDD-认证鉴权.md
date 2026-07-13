# TDD — 认证鉴权（Spring Security 实现）

> 状态：草稿（待确认）· 创建 2026-07-11
> **运营端已落地**：as-built 权威见 [运营端权限方案.md](./运营端权限方案.md)（与代码逐一对齐）。本文为设计目标态（5 链等），当前 MVP 为 2 链——差异以运营端方案 §1 为准。
> 关联需求/方案：[权限管理方案.md](./权限管理方案.md)（概念）· [功能权限清单.md](../requirements/功能权限清单.md)（权限码 SSOT）· [api/README §一](../api/README.md)（路径分层/可信头）
> 关联架构：[architecture.md §4/§5](./architecture.md) · [db-design §八 pb_auth](./db-design.md) · [ADR-007](./ADR/ADR-007-租户隔离键与命名映射.md)
> 技术基线：**Spring Boot 4.0.x + Spring Security 7.x（随 Boot 4.0）**，复用 `neargo-auth-core` + `neargo-common-security`（不重写认证内核）。

---

## 1. 目标与边界

**一句话**：用 Spring Security 的**多 `SecurityFilterChain` 按路径前缀分链**，把「运营端（员工/代理）」和「C 端（消费者）」各收敛为**一条统一过滤链**；C 端把 **App / 小程序 / H5** 三端在**登录策略层归一**，Token 校验与业务鉴权三端完全一致。

- **做**：过滤链拓扑、各链认证过滤器、`@PreAuthorize` 功能鉴权、C 端统一登录/统一 Token、C 端三端归一、401/403 统一响应、上下文透传。
- **不做**（归属其它域/文档）：权限码矩阵（→[功能权限清单](../requirements/功能权限清单.md)）、设备 MQTT/TCP 鉴权（→[TDD-access-gateway](./TDD-access-gateway.md)，独立进程独立链）、nearpay 回调验签（→trade）。
- **可编码实现细节**（下钻）：数据权限 `DataScopeInterceptor` 与 C 端 5 登录策略见 [TDD-认证鉴权-实现细节.md](./TDD-认证鉴权-实现细节.md)。

---

## 2. 过滤链拓扑（`app-modulith` 一个进程，5 条链按 `@Order`）

Spring Security 按 `securityMatcher` + `@Order` 逐链匹配，**第一条命中的链生效**。顺序：内部/回调 → OpenAPI → C 端 → 运营端（默认兜底最后）。

```
请求 ─┬─ /internal/** /gw/** /notify/**  → ①internalChain  (内网ACL + 验签, 不建会话)
      ├─ /openapi/**                     → ②openapiChain   (AppKey 签名 + 限流)
      ├─ /mp/**                          → ③consumerChain  ★C端统一 (Bearer, CORS, App/小程序/H5)
      └─ /api/**  (+其余)                → ④opsChain       ★运营端统一 (Bearer, @PreAuthorize)
```

| # | 链 | securityMatcher | 认证方式 | 授权 | 会话 |
|---|----|-----------------|---------|------|------|
| ① | internalChain | `/internal/** /gw/** /notify/**` | 网络隔离 + 供应商/渠道验签过滤器 | 域间信任 | STATELESS |
| ② | openapiChain | `/openapi/**` | AppKey 签名（ts+nonce+sign）| `openapi_app.scopes` | STATELESS |
| ③ | **consumerChain** | `/mp/**` | **Bearer（auth-core realm=CONSUMER）** | 属主鉴权（无 RBAC）| STATELESS |
| ④ | **opsChain** | `/api/**` + 默认 | **Bearer（auth-core realm=STAFF/AGENT）** | **RBAC `@PreAuthorize`** | STATELESS |

> access-gateway 是**独立进程**，自带一条 `/gw/**` 验签链（见 TDD-access-gateway），不在此进程内。

### 2.1 配置骨架

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity            // 开启 @PreAuthorize（默认 prePost）
class SecurityConfig {

  @Bean @Order(10)
  SecurityFilterChain internalChain(HttpSecurity http, VendorVerifyFilter verify) throws Exception {
    return baseline(http.securityMatcher("/internal/**", "/gw/**", "/notify/**"))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())   // 由网络ACL + verify 过滤器把关
        .addFilterBefore(verify, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean @Order(20)
  SecurityFilterChain openapiChain(HttpSecurity http, OpenApiSignFilter sign) throws Exception {
    return baseline(http.securityMatcher("/openapi/**"))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .addFilterBefore(sign, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean @Order(30)               // ★ C 端统一
  SecurityFilterChain consumerChain(HttpSecurity http, ConsumerTokenAuthFilter c,
                                    ApiExceptionHandlers ex, CorsConfigurationSource cors) throws Exception {
    return baseline(http.securityMatcher("/mp/**"))
        .cors(co -> co.configurationSource(cors))                 // H5 浏览器跨域必需
        .authorizeHttpRequests(a -> a
            .requestMatchers("/mp/auth/login", "/mp/auth/otp",     // 登录/发码
                             "/mp/nearby/**", "/mp/public/**").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(c, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(e -> e.authenticationEntryPoint(ex.entryPoint()).accessDeniedHandler(ex.denied()))
        .build();
  }

  @Bean @Order(40)               // ★ 运营端统一（默认兜底）
  SecurityFilterChain opsChain(HttpSecurity http, StaffTokenAuthFilter s,
                               ApiExceptionHandlers ex) throws Exception {
    return baseline(http.securityMatcher("/api/**"))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/auth/login", "/api/auth/otp").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(s, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(e -> e.authenticationEntryPoint(ex.entryPoint()).accessDeniedHandler(ex.denied()))
        .build();
  }

  // 各链公共基线：无状态、关 csrf/表单/basic（纯 Token API）
  private HttpSecurity baseline(HttpSecurity http) throws Exception {
    return http.csrf(CsrfConfigurer::disable)
               .formLogin(FormLoginConfigurer::disable)
               .httpBasic(HttpBasicConfigurer::disable)
               .sessionManagement(m -> m.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  }
}
```

> **csrf 关闭是安全的**：全部走 `Authorization: Bearer`，不依赖 Cookie，无 CSRF 面（见 §5.3）。若 H5 改用 Cookie 承载 Token，则该链需单独开启 CSRF Token。

---

## 3. 运营端统一处理（opsChain）

### 3.1 认证：`StaffTokenAuthFilter`（复用 commons `OpaqueTokenAuthFilter`）
每请求一次，`Authorization: Bearer` → `SessionStore`（Redis）查会话 → 构建 `Authentication` 写入 `SecurityContext`，并写 `TenantContext`（租户/区域/数据范围）。

```java
class StaffTokenAuthFilter extends OncePerRequestFilter {
  protected void doFilterInternal(req, resp, chain) {
    String token = bearer(req);
    Session s = sessionStore.get(token);                     // realm ∈ {STAFF, AGENT}
    if (s == null || s.realm() == CONSUMER) { chain.doFilter(); return; }  // 未认证→交给 entryPoint
    // ★ 权限由服务端反查，绝不信客户端 X-Roles（ops-web 是 SPA，会自填该头）
    Set<String> perms = permCache.load(s.userId());          // iam_role_permission 展开(含通配) + Redis 缓存
    var authorities = perms.stream().map(SimpleGrantedAuthority::new).toList();
    var auth = new StaffAuthentication(s.userId(), s.employeeNo(), s.roles(), authorities);
    SecurityContextHolder.getContext().setAuthentication(auth);
    TenantContext.set(s.tenantNo(), s.regionId(), s.dataScope());  // 供数据权限拦截器 + RestClient 透传
    try { chain.doFilter(req, resp); } finally { TenantContext.clear(); }
  }
}
```

> **可信头红线**：ops-web 的 `http-client.ts` 会自填 `X-Roles/X-User-Id/X-Merchant-Id`。opsChain **只认 Bearer Token 反查的权限**，客户端 X-Roles **仅回显不参与鉴权**；`X-Merchant-Id`(租户)也以会话为准校验一致，不一致拒。**边缘入口剥离客户端可信头**（common-security 网关/BFF 职责；直连时以会话覆盖）。

### 3.2 授权：`@PreAuthorize` + 通配权限校验器
权限码带通配（`*` / `device:*`），`hasAuthority` 是精确匹配不支持通配 → 用 SpEL bean **`@perm`** 统一判定（语义与前端 `can()` 一致）：

```java
@Component("perm")
class PermChecker {
  boolean can(String code) {
    var perms = StaffAuthentication.current().permCodes();
    return perms.contains("*")                       // 超管
        || perms.contains(code)                      // 精确
        || perms.contains(module(code) + ":*");      // device:* ⊇ device:cabinet:read
  }
}
```
```java
// 接口层统一用法（每个写接口必挂，纵深防御第二道）
@PostMapping("/api/trade/refunds/{no}/audit")
@PreAuthorize("@perm.can('order:refund:audit')")
Result<Void> auditRefund(...) { ... }
```
- **权限码常量**集中 `powerbank-common-api`（与前端 `lib/permissions.ts` 同源，避免手抖）。
- **数据权限**不塞进 authority：`DataScopeInterceptor`(MyBatis 内部拦截器) 读 `TenantContext.dataScope()` 拼 `agent_no/region_id/site_no` 条件（[权限管理方案 §4.2](./权限管理方案.md)）。`AGENT` 登录会话强制注入 `agent_no`，硬过滤。

### 3.3 两种部署形态，同一套 `@PreAuthorize`
| 形态 | opsChain 认证过滤器 | 说明 |
|------|--------------------|------|
| **MVP 模块化单体**（ops-web 直连 `/api/**`）| `StaffTokenAuthFilter`（Bearer 反查）| 域内自认证，X-Roles 不可信 |
| **拆分后（真 BFF 前置）** | `RequestHeaderAuthenticationFilter`（pre-auth，读 BFF 注入的可信头）| BFF 已认证，网关剥离客户端头 → 域信任 X-User-Id/X-Roles |

切换只换过滤器 Bean（profile），`@PreAuthorize`/`@perm` 与业务代码零改。

---

## 4. C 端统一处理（consumerChain）—— App / 小程序 / H5 归一

### 4.1 归一原则
> **只有「登录换取身份」因端而异；登录后一切统一。** 三端登录 → 各自 `LoginStrategy` 解析出规范身份 `(openid|unionid|phone)` → upsert `usr_user`(openid×tenant) → auth-core 签发**同一种 Bearer Token**(realm=CONSUMER) → 之后 `ConsumerTokenAuthFilter` + 属主鉴权**三端完全一致**。

```
App(手机OTP/Apple/Google) ┐
小程序(wx.login code)      ├─→ POST /mp/auth/login {grantType,...} ─→ LoginStrategy ─→ ResolvedIdentity
H5(微信网页授权/手机OTP)   ┘                                                              │
                                                    upsert usr_user(UK tenant×openid, 或 tenant×phone; unionid 归并)
                                                              │
                                          auth-core TokenIssuer.issue(userNo, CONSUMER) ─→ Bearer Token
                                                              │
        之后所有 /mp/** 请求 ── Authorization: Bearer ──→ ConsumerTokenAuthFilter（三端同一）
```

### 4.2 统一登录端点与策略
```java
// 统一入口：grantType 分发，端无关
@PostMapping("/mp/auth/login")
Result<LoginVO> login(@RequestBody @Valid LoginReq req) {   // {grantType, tenantNo, payload...}
  return Result.ok(consumerAuthService.login(req));
}

interface ConsumerLoginStrategy {
  String grantType();
  ResolvedIdentity authenticate(LoginReq req);   // 解析出 openid/unionid/phone + channel
}
class ConsumerAuthService {
  Map<String, ConsumerLoginStrategy> strategies;              // 按 grantType 注册
  LoginVO login(LoginReq req) {
    ResolvedIdentity id = strategies.get(req.grantType()).authenticate(req);   // 端差异全封装在此
    UsrUser u = userService.upsertByIdentity(req.tenantNo(), id);              // 统一建户/归并
    String token = tokenIssuer.issue(u.getCUserNo(), Realm.CONSUMER, claims(u, id.channel()));
    return new LoginVO(token, ttl, u.getCUserNo(), u.isNew());
  }
}
```

**三端 × 登录方式矩阵**（strategy 实现）：

| 端 | grantType | payload | openid 换取 | 备注 |
|----|-----------|---------|------------|------|
| **App** | `phone_otp` | phone + otp | — | MENA 主路径（无微信）|
| App | `apple` | identityToken | 校验 Apple 公钥 | iOS |
| App | `google` | idToken | 校验 Google 公钥 | Android |
| **小程序** | `wechat_miniapp` | js_code | `code2session`→openid/unionid | 海外微信中国用户 |
| **H5（微信内）** | `wechat_oauth` | code | 网页授权→openid/unionid | 公众号/分享打开；unionid 与小程序归并同一 `usr_user` |
| **H5（普通浏览器）** | `phone_otp` | phone + otp | — | **扫柜机码未装 App 的降级主路径**（MENA 重要）|
| H5 | `google`/`apple` | web OAuth code | 校验 | 海外 Web |

> `code2session`/OAuth/Apple/Google 校验属**渠道适配**，封装在各 strategy 内，不进 Security 内核。`发码` 走 `POST /mp/auth/otp`（permitAll + 图形验证/频控防刷）。

### 4.3 认证：`ConsumerTokenAuthFilter`（三端同一）
```java
class ConsumerTokenAuthFilter extends OncePerRequestFilter {
  protected void doFilterInternal(req, resp, chain) {
    Session s = sessionStore.get(bearer(req));
    if (s == null || s.realm() != CONSUMER) { chain.doFilter(); return; }
    var auth = new ConsumerAuthentication(s.cUserNo(), List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
    SecurityContextHolder.getContext().setAuthentication(auth);
    TenantContext.set(s.tenantNo(), s.regionId(), DataScope.self(s.cUserNo()));  // C端天然 SELF
    try { chain.doFilter(req, resp); } finally { TenantContext.clear(); }
  }
}
```

### 4.4 授权：属主鉴权（无 RBAC）
C 端**不用权限码**，只保证「只能碰自己的数据」——两道：

```java
// (1) 列表/我的：服务端强制加属主条件，忽略前端任何 c_user_no 传参
list(query.setCUserNo(ConsumerContext.currentUserNo()));

// (2) 单资源：属主断言（防横向越权 IDOR）
@GetMapping("/mp/trade/orders/{orderNo}")
Result<OrderVO> detail(@PathVariable String orderNo) {
  var o = orderService.get(orderNo);
  ConsumerContext.assertOwner(o.getCUserNo());   // 非本人 → 403 Result
  return Result.ok(vo(o));
}
```
- `ConsumerContext.currentUserNo()/tenantNo()` 从 `SecurityContext` 读，**唯一可信来源**。
- 敏感字段（手机/证件在 `pb_pii`）经 `Sensitive` 脱敏返回。

---

## 5. C 端三端统一的横切要点（App / 小程序 / H5）

| 维度 | App | 小程序 | H5 | 统一口径 |
|------|-----|--------|-----|---------|
| **Token 传输** | `Authorization: Bearer`（原生存储）| `Authorization: Bearer`（`wx.setStorage`）| `Authorization: Bearer`（`localStorage`）| **统一请求头 Bearer**，服务端一套过滤器 |
| **CORS** | 无（非浏览器）| 无（微信域名白名单在小程序后台配）| **需要**（浏览器跨域）| 仅 consumerChain 开 CORS，`allowedOrigins`=H5 域名白名单 |
| **CSRF** | 无 | 无 | 无（Bearer 非 Cookie）| 全链关闭 CSRF；不用 Cookie 承载 Token |
| **续期** | refresh_token 长效 | 静默 `wx.login` 重登 | 短 TTL + 静默续期/重登 | Token TTL + refresh 由 auth-core 统一签发，端各取所需 |
| **多端并发** | ✓ | ✓ | ✓ | Redis 每 Token 一会话，默认允许多端在线；可配按 realm 踢下线 |
| **推送标识** | APNs/FCM/UniPush token | 订阅消息 openid | — | 登录后单独 `POST /mp/user/push-token` 绑定，不入 Security |

**H5 安全额外注意**（浏览器环境）：
- Token 存 `localStorage` → 面临 **XSS** 风险：以 **CSP + 输出转义 + 依赖审计** 兜底；不把长效 refresh_token 放前端，H5 用短 TTL。
- **CORS 白名单**收敛到已知 H5 域名，`allowCredentials=false`（Bearer 不需要带 Cookie）。
- 微信网页授权 `redirect_uri` 域名需在公众号后台白名单；`state` 防 CSRF/重放。

---

## 6. 统一响应与上下文透传

### 6.1 401/403 统一 `Result` JSON
`formLogin/httpBasic` 关闭后，未认证/无权不再跳登录页，统一返回包（对齐 commons `Result`/`ErrorCode`，ar/en i18n）：
```java
@Component
class ApiExceptionHandlers {
  AuthenticationEntryPoint entryPoint() {              // 401 未认证
    return (req, resp, e) -> writeJson(resp, 401, Result.error(401, msg("auth.unauthenticated")));
  }
  AccessDeniedHandler denied() {                        // 403 无权（含 @PreAuthorize 拒绝、属主断言）
    return (req, resp, e) -> writeJson(resp, 403, Result.error(403, msg("auth.forbidden")));
  }
}
```
> 与 `neargo-common-web.ServerExceptionHandler` 分工：Security 层异常在 entryPoint/deniedHandler 处理；业务异常仍走 `@RestControllerAdvice`。

### 6.2 SecurityContext → TenantContext → 域间透传
- 过滤器链：`TokenAuthFilter`（写 SecurityContext + TenantContext）先于业务；`@Async`/线程池用 `DelegatingSecurityContextExecutor` + `TenantPropagationInterceptor` 传播。
- 域间 `RestClient` 调 `/internal/**`：`ClientHttpRequestInterceptor` 从 `TenantContext` 注入可信头（X-User-Id/X-Merchant-Id/X-Region-Id），下游 internalChain 信任（内网 + 头透传）。

---

## 7. 依赖与关键配置

- `spring-boot-starter-security` + `neargo-common-security`（`OpaqueTokenAuthFilter`/`SessionStore`/`AuthHeaders`/`TenantContext*`）+ `neargo-auth-core`（`TokenIssuer`/`OTP`/`RevokeService`）。
- 凭据/会话：`pb_auth.cred_credential`（`realm`=STAFF/AGENT/CONSUMER，独立 KMS，仅 auth-core 访问）；会话在 Redis（`SessionStore`）。
- 配置项（Nacos，零硬编码）：Token TTL / refresh TTL（分 realm）、OTP 频控、CORS `allowedOrigins`(H5)、并发登录策略、权限缓存 TTL。

---

## 8. 测试策略

- **单元**：`PermChecker.can()` 通配匹配（`*`/`device:*`/精确/未命中）；各 `LoginStrategy` 身份解析（mock 微信/Apple/Google）；`upsertByIdentity` 的 openid×tenant 建户 + unionid 归并。
- **切片 `@WebMvcTest + spring-security-test`**：
  - opsChain：`@WithMockStaff(perms=...)` → `@PreAuthorize` 命中/拒绝（403 Result）；无 Token → 401 Result。
  - consumerChain：Bearer 有效/无效；属主断言（他人 orderNo → 403）；permitAll 端点匿名可达。
- **集成**：三端登录（三种 grantType）→ 拿 Token → 同一 `/mp/trade/orders` 均可访问且只见本人数据；ops-web 直连伪造 `X-Roles=ADMIN` 但 Token 为 VIEWER → **仍按 VIEWER 拒**（可信头红线回归）。
- **必测场景**：① 伪造 X-Roles 不提权；② C 端横向越权拦截（IDOR）；③ 三端登录归一到同一 usr_user（unionid）；④ 401/403 统一 Result；⑤ AGENT 数据范围硬过滤。

---

## 9. 实现任务

- [ ] `SecurityConfig`：5 条 `SecurityFilterChain`（internal/openapi/consumer/ops）+ `baseline`。
- [ ] opsChain：`StaffTokenAuthFilter`（复用 `OpaqueTokenAuthFilter`）+ `PermChecker(@perm)` + 权限缓存 + `@EnableMethodSecurity`。
- [ ] consumerChain：`ConsumerTokenAuthFilter` + `ConsumerContext`（currentUserNo/assertOwner）+ CORS。
- [ ] C 端统一登录：`/mp/auth/login` + `/mp/auth/otp` + `ConsumerAuthService` + 5 个 `ConsumerLoginStrategy`（phone_otp/apple/google/wechat_miniapp/wechat_oauth）+ `usr_identity` 归并表 + `upsertByIdentity`（unionid 归并）。详见 [实现细节 Part B](./TDD-认证鉴权-实现细节.md)。
- [ ] `ApiExceptionHandlers`（401/403 统一 Result，i18n）。
- [ ] internalChain `VendorVerifyFilter` / openapiChain `OpenApiSignFilter`（可迭代）。
- [ ] `powerbank-common-api` 权限码常量（与前端 `lib/permissions.ts` 同源）。
- [ ] 单元/切片/集成测试（§8 必测场景）。

---

## 10. 待确认

1. **MVP 认证落点**：ops-web/C 端**直连 `app-modulith` 自认证**（本文 MVP 形态），还是先起一个薄 BFF 做边缘认证？（影响 opsChain 用 `StaffTokenAuthFilter` 还是 `RequestHeaderAuthenticationFilter`）
2. **H5 Token 载体**：`Authorization` 头 + `localStorage`（本文推荐，一套代码/无 CSRF）vs `HttpOnly Cookie` + CSRF Token（防 XSS 窃取更强，但需单独开 CSRF 且与 App/小程序分叉）。
3. **unionid 归并策略**：小程序 openid 与公众号 H5 openid 靠 unionid 归并同一 `usr_user`，需确认微信开放平台已绑定同主体（否则各自独立账户）。
4. **多端并发登录**：允许 App+H5 同时在线（本文默认允许），还是同 realm 单点互踢？
5. **OTP 渠道**：MENA 手机号 OTP 走哪家短信网关（是否复用 neargo）；小程序/H5 发码防刷（图形验证/滑块）。
6. **权限缓存失效**：角色改权限后，已登录员工权限缓存的失效方式（改角色即失效会话 / TTL 到期）。
