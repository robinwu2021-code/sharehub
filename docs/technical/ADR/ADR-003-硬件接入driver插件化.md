# ADR-003 硬件接入 Driver 插件化

状态：已接受（2026-07-11）

## 背景
硬件供应商接入方式混合：部分提供开放平台 API/Webhook，部分需 TCP/MQTT 私有协议直连。业务层不能感知差异。

## 决策
access-gateway 内定义 **DeviceDriver SPI**，每供应商一个实现（独立模块），统一到「指令集 + 事件集」模型：
- 指令：EJECT_ANY / EJECT_SLOT / LOCK / UNLOCK / REBOOT / QUERY_SLOTS / LOCATE / VOICE / OTA_PUSH
- 事件：ONLINE / OFFLINE / HEARTBEAT / SLOT_REPORT / RENT_CONFIRMED / RETURNED / FAULT / OTA_RESULT

长连型 driver 实现 encode/decode；API 型 driver 实现 invoke/parseWebhook。验签、SN 映射归 driver。

可靠性：commandId 幂等（Redis SETNX）、下发确认+超时重试+终态失败回调、会话映射 Redis 共享支持多实例。

## 备选与否决
- 每供应商独立网关服务：隔离好但重复建设、运维翻倍 → 否决。
- 只支持 API 型（依赖供应商云）：无法覆盖协议直连需求（已确认两者都要）→ 否决。

## 影响
新增供应商 = 新增 driver 模块 + 平台后台注册配置，业务代码零改动。driver 是 TDD 与联调的独立单元。
