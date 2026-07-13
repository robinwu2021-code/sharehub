# DDL 建表脚本索引

> 字段级建表脚本，对齐 [db-design.md](../db-design.md) / [系统领域模型.md](../系统领域模型.md)。MySQL 8 · InnoDB · utf8mb4。
> 约定：`<x>_no` 业务键 UK；跨域逻辑引用只建索引不建物理 FK；`tenant_id` 默认 `MAIN`（休眠口子）；金额 DECIMAL(18,2)；append 表按月分区。

| 文件 | 覆盖库/域 |
|------|----------|
| [pb_core-loc-agt-iam.sql](./pb_core-loc-agt-iam.sql) | 场所 `loc_`(场地方/站点/点位/合同) · 代理 `agt_` · 认证权限 `iam_`（+内置角色种子）|
| [pb_core-device-gateway.sql](./pb_core-device-gateway.sql) | 设备 `dev_`(机柜/仓位/充电宝/影子/心跳/OTA/告警) · 网关 `gw_`(供应商/绑定/指令/报文) |
| [pb_core-trade-finance.sql](./pb_core-trade-finance.sql) | 订单 `ord_` · 计费 `price_` · 支付 `pay_`(nearpay 引用) · 账务 `acct_` · 分润 `share_` · 结算 `stl_` |
| [pb_core-user-ad-workorder.sql](./pb_core-user-ad-workorder.sql) | 用户 `usr_`/`coupon_` · 广告 `ad_` · 工单 `wo_` · 平台 `notify_`/`dict_`/`md_`；末尾附 `pb_pii`/`pb_auth` 骨架 |

**执行顺序**：先建 `pb_core` 库 → 依次跑 4 个脚本（无物理 FK，顺序不敏感）→ 再建 `pb_pii`/`pb_auth`（独立库+KMS）。
覆盖 3 库中的 `pb_core` 全域 + pii/auth 骨架；约 45 张表。
