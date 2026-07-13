# TDD-backend-bootstrap（后端 Maven 骨架 · 点亮 ops-web 真实后端）

状态：已确认（2026-07-12 用户直接指派「后端搭 Maven 骨架，让 ops-web 的 .env 能真正切到后端」）
关联需求：docs/api/README.md（端点目录）· docs/technical/architecture.md · ADR-001/006/007/010
创建日期：2026-07-12

## 1. 需求摘要
搭起 powerbank 服务端 Maven 骨架，跑出一个可零基础设施启动的 Spring Boot 应用，实现 ops-web
`lib/api/http.ts` 调用的全部运营端端点，返回符合 **powerbank 自身 API 契约**的响应，使 ops-web 把
`.env.local` 置 `NEXT_PUBLIC_USE_MOCK=0` + `NEXT_PUBLIC_API_BASE=http://localhost:8080` 后，页面由 mock
切换到真实后端且数据正常显示。

**验收标准**
- `mvn -pl powerbank-app spring-boot:run`（JDK21）零外部依赖启动，端口 8080。
- ops-web 关 mock 后：登录 → 工作台/设备/订单/工单/场所/财务/用户/营销/代理/员工/供应商 全部有数据。
- 响应为 `{code:0,msg:"ok",data:...}`；分页为 `{records,total,page,size}`。

## 2. 当前架构分析
- ops-web 已具备唯一切换点 `lib/api/index.ts`（`USE_MOCK?mockApi:httpApi`）+ `http-client.ts`（拆 `Result<T>`）。
- neargo commons（`ai.neargo:neargo-common-*` 1.0.0-SNAPSHOT）已在本机 `~/.m2`，`neargo-parent`（继承
  spring-boot-starter-parent 4.0.7、java 21、enforcer、MyBatis-Plus BOM）可直接作父 pom。
- **契约差异（关键）**：neargo `Result` 为 `{code,message,data}`、`PageResult` 为 `{total,list}`；而
  powerbank 契约（docs/api §1.1/1.4）+ ops-web 期望 `{code,msg,data}` 与 `{records,total,page,size}`。
  → 骨架的**对外响应用 powerbank 自有响应型**，不直接回 neargo 的 `Result/PageResult`。

## 3. 方案设计
### 方案选型
| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A 独立 modulith app + 仅依赖 neargo-common-core（推荐）| 零基础设施即起、对齐 ADR-001 合并模块化单体、遵 ADR-006 站 neargo 地基 | 重 commons（data/security/tenant）延后 | ✅ 采用 |
| B 全量引 neargo commons（security/data/auth-core）| 一步到位 | 需 H2/Redis/安全链，启动即依赖基础设施，拖慢「点亮 ops-web」目标 | ❌ 真域落地时再引 |
| C 完全脱离 neargo 自造响应栈 | 最简 | 背离 ADR-006，后续要返工 | ❌ |

### 模块设计
- 新增 `backend/pom.xml` = `ai.neargo.powerbank:powerbank-parent`（packaging pom，父=neargo-parent，relativePath 空走 .m2）。
- 新增 `backend/powerbank-app`（Boot 应用，端口 8080）。包按域划分（modulith）：
  `common`（响应型/分页/CORS/统一包装与异常）、`seed`（内存种子，镜像 ops-web mock 数据量）、
  `dto`（记录类，字段镜像 ops-web `lib/types.ts`）、
  `ops`/`trade`/`user`/`agent`/`platform`/`gateway`（各域 Controller）。
- 复用：`neargo-common-core`（ErrorCode/IdGenerator 备用）；工程约定继承 neargo-parent。

### 核心接口（对外响应契约，powerbank 自有）
- `ApiResult<T>{ int code; String msg; T data; }`，`code=0/msg="ok"` 成功；`ResponseBodyAdvice` 自动包裹域内 Controller 返回值。
- `PageData<T>{ List<T> records; long total; int page; int size; }`，`PageData.paginate(all,page,size)`。
- 端点严格对齐 `lib/api/http.ts`（`/api/ops/**`、`/api/trade/**`、`/api/platform/**`、`/api/user/**`、
  `/api/agent/**`、`/internal/gw/vendors`、`/internal/user/credit/blacklist`）。

### 配置项（零硬编码）
- `server.port=8080`（application.yml）。
- `powerbank.cors.allowed-origins`（默认 `http://localhost:3000`）→ `WebCorsConfig` 读取。
- ops-web `.env.local`：`NEXT_PUBLIC_USE_MOCK=0` + `NEXT_PUBLIC_API_BASE=http://localhost:8080`。

## 4. 测试策略
- 单元/集成：`@SpringBootTest` + MockMvc 冒烟——上下文加载、`GET /api/ops/dashboard` 返回 `code=0` 且
  `data.currency=AED`、`GET /api/ops/cabinets?page=1&size=10` 返回 `records` 非空且 `total>0`。
- 手动 E2E：ops-web 关 mock 后逐模块目视。

## 5. 风险与注意事项
- 构建须 JDK21（neargo-parent enforcer 锁 [21,22)）；本机默认 mvn 跑 JDK26，需 `JAVA_HOME=openjdk@21`。
- 对外契约锚定 powerbank（`msg`/`records`），勿误用 neargo `message`/`list`。
- 骨架为**内存种子无持久化/无鉴权**；真域落地再引 data/security/tenant commons 与 DDL（docs/technical/ddl）。
- `/internal/gw/vendors` 归属 access-gateway，骨架暂由 app 代管，标注 TODO 拆分。

## 6. 实现任务
- [x] backend 父 pom + powerbank-app pom
- [x] common：ApiResult / PageData / 统一包装 advice / 异常处理 / WebCorsConfig
- [x] dto 记录类镜像 types.ts
- [x] seed 内存数据（镜像 mock 数据量）
- [x] 各域 Controller 对齐 http.ts 端点
- [x] application.yml + 冒烟测试
- [x] ops-web .env.local + 更新 .env.local.example
- [x] JDK21 构建 + 启动 + 端点验证

---
确认记录：2026-07-12 用户直接指派实施（契约与结构由既有 docs/api + ADR 锚定，无待决分叉）。
</content>
</invoke>
