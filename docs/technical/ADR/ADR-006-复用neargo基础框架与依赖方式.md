# ADR-006 复用 ai-neargo 基础框架与依赖方式

状态：已接受（2026-07-11 用户确认）· 依赖方式=私仓坐标 · groupId=ai.neargo.powerbank
· **分发方式拟被 [ADR-022](./ADR-022-共享构件发布到自建Nexus.md) 修订**（2026-09-23，待确认）：Codeup 私仓未落地，改为自建 Nexus；并拆出 `neargo-build-parent` / `neargo-bom`

## 背景
用户要求 powerbank「技术栈参考 ai-neargo，基础层框架能力共用与依赖」。ai-neargo 已有成熟的 8 个 commons + auth-core（Java21/Boot4.0），提供响应/异常/ID/多租户/鉴权/事件/审计/配置等 L1 能力。

## 决策（已定部分）
- powerbank **复用 neargo L1 commons，不重写**：`neargo-common-{core,web,data,security,mq,i18n,api,config}` + `neargo-auth-core`。
- 沿用 neargo 全部工程约定：`Result<T>`、`BaseEntity`、`BaseRepository`、`TenantContext`/`AuthHeaders`、`DomainEventPublisher`、`OperateLog`、`RestClient`。
- 复用其**设计模式**：`platform-device-ops`(PF10 设备注册/心跳/三层 OTA/alert_event)、`mqtt-realtime-topics`(EMQX5 + MQTT5 + topic 文法 + JWT/ACL)。
- powerbank 只新增自己的**业务域**（柜机租借/计费/免押/分账/工单），neargo 无这些。

## 已定：依赖分发方式 = A 私仓坐标依赖
neargo commons 发布到云效 Codeup 私有 Maven 仓库，powerbank 为**独立仓库**，按坐标依赖 `ai.neargo:neargo-common-*` + `neargo-auth-core`（引 neargo BOM 统一版本）。powerbank 独立发版，不进 neargo reactor。

否决：B 并入 monorepo（与 neargo 发版耦合、独立性弱）；C submodule（版本漂移，仅过渡）。

## 已定：groupId = `ai.neargo.powerbank`
powerbank 视为 neargo 生态子项目，父 pom `ai.neargo.powerbank:powerbank-parent`，包名 `ai.neargo.powerbank.*`；仅依赖 `ai.neargo:*` 基础包，不反向被 neargo 依赖。

## 前置任务（阻塞 powerbank 起步）
1. **neargo commons 发私仓**：推动 neargo 侧把 `neargo-common-*` + `neargo-auth-core` release 到 Codeup 私仓（可能触发 neargo 一次版本发布）。powerbank 父 pom 引其 BOM。
2. commons 若需为 powerbank 增强（如设备指令幂等/长连接会话工具），优先向 neargo 提 PR 回馈，避免分叉。

## 影响
- powerbank 父 pom 继承 `spring-boot-starter-parent`(4.0.x)，`<dependencyManagement>` import neargo BOM + MyBatis-Plus BOM，锁 JDK 21（maven-enforcer，与 neargo 一致）。
