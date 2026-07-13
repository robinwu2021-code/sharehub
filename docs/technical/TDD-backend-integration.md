# 方案与开发计划 · 后端完成 + 前后端联调

> 状态：**已完成（2026-07-12）** —— 后端 API 全覆盖、JDK21 构建运行、ops-web 切后端联调实测通过。
> 本次补齐：Ops `saveSite`/`savePoint`（写端点）+ 修 ops-web 创建路径尾斜杠（Boot4 不匹配 `/x/`）致 404。Gw(供应商)/Platform(员工角色审计) 由并行会话已补。
> 验收结果：`mvn package` 绿；:8080 `/actuator/health` UP；全 GET/POST 端点返 `{code,msg,data}`（curl 实测 saveSite→ST999/savePoint→LOC230/saveAgent→AG010/saveVendor/dispatch/blacklist）；ops-web(:3000,USE_MOCK=0) 实机站点页显示后端 ST999、无 Mock 徽章、网络命中 :8080、CORS 200。
> 关联：[TDD-backend-bootstrap](./TDD-backend-bootstrap.md) · [系统领域模型](./系统领域模型.md) · [ddl/](./ddl/README.md) · [api/README](../api/README.md)

## 一、现状盘点（backend/ 来自既有对话）
- Maven：`powerbank-parent`（继承 `neargo-parent` → Boot 4.0.x + Java21 + enforcer 锁 JDK21）→ 单模块 `powerbank-app`（modulith）。
- 依赖：`neargo-common-core`（纯库）+ spring-boot-starter-web/validation/actuator + lombok。**neargo commons 已装于本机 `~/.m2`**（可解析）。
- 已实现：`ApiResult{code,msg,data}` + `ApiResponseWrapper`(ResponseBodyAdvice 自动包裹) + `PageData{records,total,page,size}` + `Kw`(关键词) + `WebCorsConfig`(:3000→:8080) + 内存 `SeedData`（19 域全量种子，镜像 ops-web mock）。
- 控制器：Ops（看板/设备/指令/工单/站点点位场地方合同）、Trade（订单/干预/分润/分录/结算/提现/审核/计费模板）、User（用户/券/拉黑）、Agent（代理增改查）。
- DTO：`Dto.java` 24 record，字段与 ops-web `lib/types.ts` 一致（Site/RoleRow.dataScope/Contract.siteName 均已对齐）。

## 二、差距（vs ops-web `lib/api/http.ts` 全端点）
| 缺口 | 端点 | 处置 |
|------|------|------|
| 网关供应商 | `GET /internal/gw/vendors`、`POST /internal/gw/vendors/{code}/config` | 新增 `GwController` |
| 平台 | `GET /api/platform/employees`、`/roles`、`/audit-logs` | 新增 `PlatformController` |
| 场所写 | `POST /api/ops/sites/{siteNo}`、`POST /api/ops/locations/{locationNo}` | OpsController 补 saveSite/savePoint |
| 种子写 | vendors/sites/locations 的 upsert | SeedData 补 upsert helper |

## 三、开发计划（分阶段，自动执行）
- **P1 补齐 API（本次）**：新增 Gw/Platform 控制器 + Ops 写端点 + SeedData upsert，使后端覆盖 ops-web 全部端点。
- **P2 构建运行**：`JAVA_HOME=openjdk@21 mvn -q -DskipTests package` → 后台 `java -jar` 起 :8080 → 校验 actuator + 抽样端点 `{code,msg,data}`。
- **P3 联调对接**：ops-web `.env.local` 置 `NEXT_PUBLIC_USE_MOCK=0` + `NEXT_PUBLIC_API_BASE=http://localhost:8080`（http.ts 路径已含 `/api`、`/internal`，BASE 仅取源）→ 浏览器验证多模块真实数据 + 写操作（派单/编辑站点）→ 确认 Header 无「Mock 数据」徽章、网络命中 :8080。
- **P4 持久化（已完成 loc 域，2026-07-12）**：**场所域(loc_)已切 MariaDB 真持久化**。栈：`mybatis-plus-spring-boot4-starter` + `mybatis-plus-extension`(拦截器) + `mysql-connector-j` → MariaDB `pb_core`。实体 `LocSite/LocLocation/LocVenue/LocContract`(MP `@TableName`/`@Version`/`@TableLogic`) + `LocMappers`(BaseMapper) + `MybatisPlusConfig`(分页/乐观锁,`@MapperScan markerInterface=BaseMapper`) + `LocSeeder`(CommandLineRunner 首boot灌种子,幂等) + `LocService`(实体↔DTO,分页,upsert)；OpsController 场所端点改调 LocService。
  - **验收（实测）**：seeder 灌 12 站点入 `loc_site`；API 建 ST312→**重启后存活**、总数 13(非 25，seeder 正确跳过)；ops-web 站点页渲染 DB 数据。
  - **表形态**：为让实体↔DTO 零转换，loc_ 落库用**贴合 API 的去规范化列**(如 site.venue_name/point_count 直存)；`ddl/` 中的规范化 DDL 为目标模型，二者差异已知。其余域(dev/ord/pay/...)仍内存种子，按此模式可增量替换。
  - **坑**：①`mysql-connector-j` 的 `characterEncoding` 要 Java 名 `UTF-8`(非 `utf8mb4`)否则启动即 `Unsupported character encoding`；②boot4 starter 不传递 `mybatis-plus-extension`(内置拦截器所在)，须显式加；③`java -jar` 用 JDK21 二进制(默认 java17 跑 class65 会 UnsupportedClassVersionError)。

## 四、契约与鉴权说明
- 响应：控制器返原始 DTO，`ApiResponseWrapper` 统一包 `{code:0,msg:ok,data}`；ops-web `http-client` 拆 `.data`。
- 分页：`PageData.paginate` 内存切片，`{records,total,page,size}` 对齐前端 `PageResult`。
- 鉴权：骨架期未接 Spring Security（[权限管理方案](./权限管理方案.md) 为后续），ops-web 传的 `X-Roles/X-User-Id` 后端暂忽略；**前端 `can()` 仅体验层**，真实鉴权待 P5 接 auth-core（红线：后端只认 Token 反查权限，不认客户端 X-Roles）。
- CORS：dev 直连（:3000→:8080）；生产同源 nginx 反代 `/api`→后端。

## 五、验收
- 后端：`mvn package` 绿；:8080 起；`/actuator/health` UP；`/api/ops/dashboard`、`/api/platform/roles`、`/internal/gw/vendors` 返 `{code:0,...}`。
- 联调：ops-web `USE_MOCK=0` 下 12 模块列表/详情/写操作正常，数据来自后端（网络面板命中 :8080），无 Mock 徽章。
