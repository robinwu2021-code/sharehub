# TDD — 设备接入网关（access-gateway）

状态：草稿 / 待确认
关联需求：[PRD-access-gateway.md](../requirements/PRD-access-gateway.md)
关联架构：[architecture.md §6](./architecture.md) · [db-design.md §四 pb_core(gw_*)](./db-design.md) · [api/README §四](../api/README.md)
创建日期：2026-07-11

---

## 1. 需求摘要

南向统一接入层：把 TCP/MQTT/HTTP 三种异构供应商接入收敛为**统一指令（8 种）+ 统一事件（8 种）**。核心验收：新增供应商=实现一个 driver + 配置注册，业务零改动；指令幂等（不重复弹出）+ 下发确认 + 超时重试；借出 P95<3s；网关多实例无状态（会话在 Redis）。

## 2. 当前架构分析

- **复用 neargo commons**：`IdGenerator`(commandId)、`DomainEventPublisher`(发事件)、`Sensitive`(报文脱敏)、`neargo-auth-core`(设备 JWT 签发/校验)、`NacosConfigImporter`(网关参数)、`Result`。
- **独立进程**：access-gateway 因长连接与业务解耦，独立部署（architecture §4），不并入 app-modulith。
- **可借鉴 neargo**：`mqtt-realtime-topics`（EMQX5 + topic 文法 + JWT/ACL）直接套用于 MQTT 直连型供应商；`platform-device-ops` 的 OTA 三层模型在 ops 域，网关只做执行通道。
- **数据**：`pb_core(gw_*)` 库（gw_vendor / gw_vendor_config / gw_device_binding / gw_command_log / gw_message_log）；运行态在 Redis。

## 3. 方案设计

### 3.1 方案选型
| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **A 单网关进程 + driver SPI 插件（推荐）** | 统一接入、一处会话/幂等/重试、运维简单 | 单进程需扛多协议 | ✅ 采用 |
| B 每供应商独立网关服务 | 隔离强 | 重复建设、运维翻倍、会话/幂等分散 | ❌ |
| C 业务域内直连供应商 | 少一跳 | 供应商差异侵入业务、无法统一幂等 | ❌ |

### 3.2 分层结构（进程内）

```
┌─ 连接层 Connectors ──────────────────────────────┐
│ TcpServer(Netty)  MqttConnector(EMQX订阅)  WebhookController(HTTP) │
└───────────────┬──────────────────────────────────┘
                ▼
┌─ 会话层 SessionManager ───────────────────────────┐
│ DeviceSession(conn↔SN)  Redis: sn→instance  在线态/心跳 │
└───────────────┬──────────────────────────────────┘
                ▼
┌─ 协议层 DeviceDriver SPI（每供应商一实现，插件注册）─┐
│ encode/decode(长连)  invoke/parseWebhook(云)  verify/resolveSn │
└───────────────┬──────────────────────────────────┘
                ▼
┌─ 指令/事件层 ─────────────────────────────────────┐
│ CommandDispatcher(队列+幂等+重试+确认)              │
│ EventPublisher(标准 DeviceEvent → DomainEventPublisher) │
│ MessageLogger(报文留痕异步落库)                     │
└──────────────────────────────────────────────────┘
```

### 3.3 模块设计
- **新增**（access-gateway 进程，包 `ai.neargo.powerbank.gateway`）：
  - `connector.tcp` / `connector.mqtt` / `connector.http` — 三种连接层。
  - `driver` — `DeviceDriver` SPI + `DriverRegistry`（按 vendorCode 注册/查找）+ 各供应商 driver 子模块。
  - `session` — `SessionManager` + `DeviceSession` + Redis 会话路由。
  - `command` — `CommandDispatcher` + `CommandStore`(Redis 幂等 + gw_command_log) + `RetryScheduler`。
  - `event` — `DeviceEventPublisher`（封装 commons `DomainEventPublisher`）。
  - `log` — `MessageLogger`（异步、脱敏）。
  - `config` — `VendorConfigService`（Nacos + pb_core(gw_*)，热加载）。
- **复用**：commons 全套；EMQX、Redis、MySQL。
- **对外契约**（放 `powerbank-common-api`）：`DeviceCommand` / `DeviceEvent` / `CommandResult` 枚举与 DTO，供 ops/trade 依赖。

### 3.4 核心接口

**DeviceDriver SPI**
```java
public interface DeviceDriver {
    String vendor();
    AccessMode accessMode();                       // TCP / MQTT / HTTP_API
    // 长连型
    byte[] encode(DeviceCommand cmd, SessionInfo s);
    List<DeviceEvent> decode(ByteBuf frame, SessionInfo s);
    // 云对接型
    CommandResult invoke(DeviceCommand cmd, VendorConfig cfg);
    List<DeviceEvent> parseWebhook(WebhookPayload p, VendorConfig cfg);
    // 通用
    boolean verify(WebhookPayload p, VendorConfig cfg);
    String resolveSn(Object rawIdentity);          // 供应商标识 → 平台 cabinetNo
}
```

**指令下发（内部 API `/internal/gw/commands`）**
```java
CommandAck dispatch(DeviceCommand cmd);   // 返回 commandId + 初始状态
CommandStatus query(String commandId);
```

**统一模型（common-api）**
```java
record DeviceCommand(String commandId, String tenantId, String cabinetNo,
                     CommandType type, Map<String,Object> params, String orderNo) {}
record DeviceEvent(String tenantId, String cabinetNo, String sn, EventType type,
                   Map<String,Object> data, Instant occurredAt, String commandId) {}
enum CommandType { EJECT_ANY, EJECT_SLOT, LOCK, UNLOCK, REBOOT, QUERY_SLOTS, LOCATE, VOICE, OTA_PUSH }
enum EventType   { ONLINE, OFFLINE, HEARTBEAT, SLOT_REPORT, RENT_CONFIRMED, RETURNED, FAULT, OTA_RESULT }
```

### 3.5 关键流程

**指令下发（幂等 + 确认 + 重试）**
```
dispatch(cmd)
 1. commandId = IdGenerator; Redis SETNX cmd:{commandId} (幂等，重复直接返回)
 2. 落 gw_command_log(status=PENDING)
 3. 查 SN 会话所在实例; 本地→直发, 异地→转发
 4. driver: 长连 encode→写 channel / 云 invoke→调 API
 5. status=SENT; 启动超时计时器(可配, 默认5s)
 6. 收到 ACK/设备事件 → status=ACKED/CONFIRMED
 7. 超时 → 重试(≤N次) → 仍失败 status=TIMEOUT/FAILED → 发 CommandFailed 事件(业务方转异常流程)
```

**事件上行**
```
连接层收帧/回调 → MessageLogger.async(留痕)
 → driver.decode/parseWebhook → List<DeviceEvent>
 → 幂等去重(业务键) → DeviceEventPublisher.publish (DomainEventPublisher)
 → ops 消费(落台账/影子/告警) · trade 消费(RENT_CONFIRMED/RETURNED 驱动订单)
```

**借出闭环**（与 trade 契约）
```
trade → /internal/gw/commands {EJECT_SLOT, orderNo}
gateway → 设备 → RENT_CONFIRMED(带 orderNo/commandId)
gateway → DeviceEvent → trade: 订单 CREATED→IN_USE, 开始计费
超时无确认 → CommandFailed → trade: 订单转异常 + 退免押
```

### 3.6 会话管理
- TCP：Netty `channelActive` → 首帧鉴权/绑定 SN → `SessionManager.bind(sn, channel)` + Redis `sn→instanceId`(TTL, 心跳续期)。
- MQTT：EMQX 连接 clientId=sn，JWT(auth-core) claim 带 tenant/region，ACL 限 `pb/{region}/{tenant}/device/{sn}/#`；网关订阅 up topic。
- 在线态：心跳刷新 `last_heartbeat_at`；`OfflineScanner`(XXL-Job/定时) 扫描超时→发 OFFLINE；MQTT 掉线由 LWT retain 补 OFFLINE。
- 跨实例：指令目标 SN 不在本实例 → 查 Redis 找实例 → 内部转发（HTTP/消息）。

### 3.7 配置项（零硬编码，`gateway.config` / Nacos / pb_core(gw_*)）
| 配置 | 位置 | 默认 |
|------|------|------|
| 心跳间隔 / 离线阈值 | Nacos `gateway.heartbeat.*` | 30s / 90s |
| 指令超时 / 重试次数 | Nacos `gateway.command.*` | 5s / 3 |
| 报文留痕 TTL / 脱敏字段 | Nacos `gateway.msglog.*` | DEBUG 7d |
| TCP 端口 / MQTT broker / ACL | `gateway.yaml` | — |
| 供应商密钥 / 回调验签 | pb_core(gw_*).gw_vendor_config `[KMS]` | — |

## 4. 测试策略

- **单元**：每 driver 的 encode/decode/verify/resolveSn（给定协议样例帧→期望指令/事件）；CommandDispatcher 幂等（同 commandId 二次 dispatch 不重发）；超时重试计数；OfflineScanner 判定。
- **契约**：DeviceCommand/DeviceEvent 序列化契约测试（ops/trade 依赖）。
- **集成**：
  - HTTP driver：mock 供应商 API + 回放真实 webhook → 事件发布断言。
  - MQTT：对 EMQX（或 `broker.emqx.io` 测试）端到端 connect→pub up→网关收→发事件（参照 neargo `MqttSignalSourceIT`）。
  - TCP：内存 Netty channel 灌帧 → decode→事件；下发→encode 出帧断言。
- **关键场景**（必测）：① 借出指令→RENT_CONFIRMED 闭环；② 指令超时→重试→失败回调；③ 重复弹出幂等；④ 掉线→OFFLINE→重连另一实例；⑤ 回调验签失败拒绝。

## 5. 风险与注意事项
- **供应商协议差异大**：driver 是逐供应商联调，需真机/模拟器；协议文档质量影响工期。
- **重复弹出资损**：幂等是红线，commandId + Redis SETNX + 设备侧幂等双保险。
- **借出确认口径**：以 `RENT_CONFIRMED` 事件为准（PRD 待确认 #2）；仅 ACK 不算成功，防「扣了款没弹出」。
- **多实例转发**：会话路由失效时降级为广播或拒绝，避免指令丢失。
- **EMQX 依赖**：MQTT 型供应商上线前需生产 broker + ACL（neargo 亦为待办）。

## 6. 实现任务
- [ ] common-api：DeviceCommand/DeviceEvent/枚举/CommandResult 契约
- [ ] pb_core(gw_*) 建表（gw_vendor/config/binding/command_log/message_log）
- [ ] DeviceDriver SPI + DriverRegistry + VendorConfigService（热加载）
- [ ] CommandDispatcher（幂等+队列+重试+确认）+ CommandStore
- [ ] DeviceEventPublisher + MessageLogger（异步脱敏）
- [ ] SessionManager + Redis 会话路由 + OfflineScanner
- [ ] connector.http（Webhook）+ 首个 HTTP 云对接 driver（GW-a）
- [ ] connector.tcp（Netty）+ TCP driver + 多实例转发（GW-b）
- [ ] connector.mqtt（EMQX + JWT/ACL）+ OTA 下发通道（GW-c）
- [ ] 单元/契约/集成测试 + 监控指标埋点

---
确认记录：待确认（PRD 待确认项 + §3.1 方案A）
