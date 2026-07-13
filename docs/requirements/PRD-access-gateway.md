# PRD — 设备接入网关（access-gateway）

> 状态：草稿（待确认）· 创建 2026-07-11
> 关联：[功能矩阵.md §1](./功能矩阵.md) · [architecture.md §6](../technical/architecture.md) · [ADR-003](../technical/ADR/ADR-003-硬件接入driver插件化.md)
> 对应 TDD：[TDD-access-gateway.md](../technical/TDD-access-gateway.md)

---

## 1. 背景与目标

powerbank 要对接**不同硬件供应商**的充电宝柜机，各家通信方式与协议不同（有的提供开放云 API，有的只给设备私有协议直连）。access-gateway 是**南向统一接入层**：把多供应商的异构接入，收敛为平台内一套**统一指令 + 统一事件**，让上层业务（trade 借还、ops 运维）完全不感知供应商差异。

**一句话目标**：新增一家供应商 = 实现一个 driver + 后台注册配置，**业务代码零改动**。

## 2. 用户故事 / 场景

- 作为**用户**，扫码借充电宝时，系统能不管柜机是哪家供应商，都在 3 秒内弹出。
- 作为**运维**，能远程弹出/锁仓/重启任意供应商的柜机，并看到指令是否成功。
- 作为**平台**，接入新供应商时不停机、不改订单/运维逻辑，只配置上线。
- 作为**系统**，柜机的心跳、仓位、借还、故障事件能实时、可靠、幂等地进入平台。

## 3. 范围

### 3.1 做（In Scope）
1. **三种南向接入**：TCP 私有协议直连（Netty）、MQTT 直连（EMQX）、HTTP 云 API + Webhook 回调。
2. **供应商 driver 插件**：每供应商一个实现，映射统一指令/事件；后台注册与配置（密钥、回调、参数）。
3. **统一指令下发**：见 §4.1，含队列、超时重试、幂等、下发确认。
4. **统一事件上行**：见 §4.2，解析为标准 `DeviceEvent` 经 `DomainEventPublisher` 发布。
5. **会话管理**：设备连接↔SN 绑定、在线态判定、多网关实例会话共享。
6. **验签与安全**：供应商回调验签、密钥管理、设备鉴权（MQTT JWT + ACL）。
7. **报文留痕**：上下行原始报文脱敏落库，支持排障回放。
8. **OTA 指令通道**：承接 ops 的 OTA 任务下发与结果回收（三层模型由 ops 管，网关只管下发/回报通道）。

### 3.2 不做（Out of Scope，归属其它域）
- 设备台账（柜机/仓位/充电宝 CRUD、生命周期）→ **ops 域**（网关只认 SN，不持台账写权威）。
- 订单/计费/免押/退款 → **trade 域**（网关不碰资金）。
- 工单/告警的业务处置 → **ops 域**（网关只上报故障事件，ops 决定是否开单）。
- OTA 版本/投放/任务状态账本 → **ops 域**（网关是执行通道）。

## 4. 功能需求

### 4.1 统一指令集（北向 → 设备）
| 指令 type | 语义 | 关键参数 |
|-----------|------|---------|
| `EJECT_ANY` | 弹出任一可用充电宝（借出）| cabinetNo |
| `EJECT_SLOT` | 弹出指定仓位 | cabinetNo, slotIndex |
| `LOCK` / `UNLOCK` | 锁/解锁仓位 | cabinetNo, slotIndex |
| `REBOOT` | 重启柜机 | cabinetNo |
| `QUERY_SLOTS` | 查询全仓位状态 | cabinetNo |
| `LOCATE` | 定位（响铃/闪灯）| cabinetNo |
| `VOICE` | 播报语音 | cabinetNo, textOrCode |
| `OTA_PUSH` | 下发固件升级 | cabinetNo, artifactUrl, version |

**验收**：每条指令返回全局唯一 `commandId`；调用方可查询状态（PENDING/SENT/ACKED/CONFIRMED/TIMEOUT/FAILED）。

### 4.2 统一事件集（设备 → 平台）
| 事件 type | 语义 |
|-----------|------|
| `ONLINE` / `OFFLINE` | 上下线（含 LWT 掉线补发）|
| `HEARTBEAT` | 心跳（含固件版本、信号、温度等 metrics）|
| `SLOT_REPORT` | 仓位状态上报（在仓充电宝、锁态、电量）|
| `RENT_CONFIRMED` | 借出确认（弹出成功，带充电宝 SN + 仓位）|
| `RETURNED` | 归还确认（带充电宝 SN + 仓位 + 电量）|
| `FAULT` | 故障上报（故障码）|
| `OTA_RESULT` | 升级结果（成功/失败/回滚）|

**验收**：事件解析为标准 `DeviceEvent` 并发布；`RENT_CONFIRMED`/`RETURNED` 能关联到 `commandId`/`orderNo`（借还闭环）。

### 4.3 供应商接入配置
- 后台注册供应商：`vendorCode`、`accessMode`(TCP/MQTT/HTTP_API)、API 基址、密钥（KMS）、回调验签方式、driver 参数。
- 支持租户级差异配置（同供应商不同租户不同密钥/商户）。
- SN 映射规则：供应商设备标识 ↔ 平台 `cabinetNo`。

### 4.4 会话与在线态
- TCP/MQTT 连接建立后完成 SN 绑定；心跳维持；默认 **90s** 无心跳判离线（可配）。
- 多网关实例：`SN → 实例` 映射存 Redis，指令跨实例路由转发。
- 掉线补发：MQTT 走 LWT；TCP 由心跳超时扫描。

### 4.5 报文留痕
- 上下行原始报文异步落 `gw_message_log`（脱敏），按 SN/时间检索回放；DEBUG 短 TTL、异常长留存。

## 5. 非功能需求（验收标准）

| 维度 | 指标 |
|------|------|
| **幂等** | 同 `commandId` 重复下发不重复弹出（Redis SETNX 去重）；同一设备事件重复上报按业务键去重 |
| **可靠性** | 指令下发有 ACK/事件确认；超时可配重试 N 次；终态失败回调业务方 |
| **实时性** | 借出指令端到端 P95 < 3s；事件上行到发布 P95 < 1s |
| **在线判定** | 心跳超时判离线延迟 ≤ 心跳间隔 + 30s |
| **可用性** | 网关多实例无状态（会话在 Redis）；单实例宕机设备自动重连另一实例 |
| **安全** | 供应商回调必验签；MQTT 设备 JWT + topic ACL；报文脱敏；密钥 KMS |
| **可扩展** | 新增供应商 driver 不改业务代码、不停机（配置热加载）|
| **可观测** | 指令成功率、设备在线率、事件延迟、driver 错误率 可监控告警 |

## 6. 边界与约束
- 网关**只认 SN，不写设备台账**；台账/生命周期在 ops，网关上报事件由 ops 落库。
- 网关**不碰资金**；`RENT_CONFIRMED`/`RETURNED` 只发事件，计费/请款在 trade。
- 供应商私有协议文档由各供应商提供；driver 实现依赖协议文档，属逐供应商联调任务。

## 7. 依赖
- 复用 neargo commons：`Result`/`IdGenerator`/`DomainEventPublisher`/`Sensitive`/`auth-core`(设备 JWT)/`NacosConfigImporter`。
- 基础设施：EMQX 5（MQTT 直连供应商）、Redis（会话/幂等/影子）、MySQL `pb_gateway`（配置/日志）、Kafka（事件，拆分后）。

## 8. 里程碑
| 批次 | 内容 |
|------|------|
| GW-a | 框架 + DeviceDriver SPI + HTTP 云对接型 driver（1 家）+ 指令/事件闭环 + 报文留痕 |
| GW-b | TCP 私有协议型（Netty）+ 会话管理 + 幂等重试 + 多实例会话共享 |
| GW-c | MQTT 直连型（EMQX + JWT/ACL）+ OTA 下发通道 |

## 9. 待确认
1. 首批供应商名单与各自接入型（决定先做哪个 driver）。
2. 借出确认口径：以 `RENT_CONFIRMED` 事件为准，还是指令 ACK 即算成功？（本文：以事件为准，更可靠）
3. 心跳间隔与离线阈值默认值（本文 90s，随供应商调）。
4. 报文留痕保留周期与合规要求。
