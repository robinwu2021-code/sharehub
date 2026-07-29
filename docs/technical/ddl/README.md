# DDL 建表脚本索引

> 字段级建表脚本，对齐 [db-design.md](../db-design.md)（**v2 全量重整 2026-07-29**）/ [系统领域模型.md](../系统领域模型.md)。MySQL 8 / MariaDB · InnoDB · utf8mb4。
> 约定：`<x>_no` 业务键 UK；跨域逻辑引用只建索引不建物理 FK；`tenant_id` 默认 `MAIN`（休眠口子）；金额 `DECIMAL(18,2)` + `currency`；append 表按月分区。

## 文件清单

### v1（2026-07-12，63 表）
| 文件 | 覆盖域 |
|------|----------|
| [pb_core-loc-agt-iam.sql](./pb_core-loc-agt-iam.sql) | 场所 `loc_`(场地方/站点/点位/合同) · 代理 `agt_` · 认证权限 `iam_`（+内置角色种子）|
| [pb_core-device-gateway.sql](./pb_core-device-gateway.sql) | 设备 `dev_`(机柜/仓位/充电宝/影子/心跳/OTA/告警) · 网关 `gw_`(供应商/绑定/指令/报文) |
| [pb_core-trade-finance.sql](./pb_core-trade-finance.sql) | 订单 `ord_` · 计费 `price_` · 支付 `pay_`(nearpay 引用) · 账务 `acct_` · 分润 `share_` · 结算 `stl_` |
| [pb_core-user-ad-workorder.sql](./pb_core-user-ad-workorder.sql) | 用户 `usr_`/`coupon_` · 广告 `ad_` · 工单 `wo_` · 平台 `notify_`/`dict_`/`md_` |

### v2（2026-07-29，新增 68 表 + 改造 6 表）
> 依据：运营端 **98 菜单叶** + C端 **17 模块** + `ops-web/lib/types` **70 实体**的字段级反查。v1 写于运营端 73 项菜单之前，落后约 35 项能力。

| 文件 | 表数 | 覆盖 |
|------|:---:|----------|
| [pb_core-v2-platform-system.sql](./pb_core-v2-platform-system.sql) | 18 | 系统设置 16 叶所需：`sys_*`(业务规则/登录设置/应用版本/税率/参数) · `md_*`(银行/问题/多国家市场) · `notify_log`/`notify_blacklist` · `pay_channel`(+scope) · `openapi_app` · `iam_staff_perf` · 补漏 `tenant`/`tenant_config`/`iam_dept`/`iam_menu` |
| [pb_core-v2-ops-alarm.sql](./pb_core-v2-ops-alarm.sql) | 17 | **告警管理新模块** `dev_alarm`(+notice/code/rule) · `dev_code_batch` 设备编码 · `inv_*` 库存调拨 · `loc_*`(BD CRM/门店 Onboarding/生命周期) · `agt_*`(划拨/分润/辖域) · `wo_sla_rule` |
| [pb_core-v2-trade-user.sql](./pb_core-v2-trade-user.sql) | 33 | 订单售后 `ord_*`(异常/投诉/退款/预约/押金) · `price_schedule`/`price_plan_scope` · 财务 `recon_*`/`fin_invoice`/`stl_settlement_detail` · 用户风控 `usr_blacklist`/`usr_free_whitelist` · 充值三表 · 营销 `mkt_*` · 客服 `cs_*` · C端专属 8 表 · 补漏 `mbr_plan` |
| [pb_core-v2-alter.sql](./pb_core-v2-alter.sql) | 2 | **存量 6 表改造**（`ord_rent`/`usr_credit`/`stl_withdrawal`/`pay_order` 的 ALTER + `dev_alert→dev_alarm` 迁移草稿）+ **`pb_pii`/`pb_auth` 正式建库**（v1 只有注释骨架）|

**`pb_core` 合计 131 表** · `pb_pii` 1 · `pb_auth` 1 = **133**。

## 执行顺序

```
1. CREATE DATABASE pb_core;
2. 跑 v1 四个脚本          （无物理 FK，彼此顺序不敏感）
3. 跑 v2 三个新表脚本       （同上）
4. 跑 pb_core-v2-alter.sql （含 pb_pii / pb_auth 建库，必须最后）
```

## 注意事项

- **ALTER 不幂等**：MySQL 8 无 `ADD COLUMN IF NOT EXISTS`，`pb_core-v2-alter.sql` 重复执行会报 1060 Duplicate column。请用 Flyway/Liquibase 管版本，或先查 `information_schema.COLUMNS`。
- **数据迁移是注释草稿**：`dev_alert → dev_alarm`、`usr_credit.blacklisted → usr_blacklist` 两处迁移 SQL 已写好但**默认注释掉**，核对数据后再手动放开；`RENAME`/`DROP TABLE` 一律不自动执行，旧表保留一个版本周期。
- **后端另有一份 schema.sql**：`backend/powerbank-app/src/main/resources/schema.sql` 自建了 `usr_user`/`usr_identity`/`iam_*`/`sys_token`/`iam_menu`，启动时 `spring.sql.init.mode=always` 执行。**两处定义需保持一致，以本目录为准**（后端那份缺 `iam_menu.name_en`/`group_name`/`phase` 等列）。
- **每个 v2 脚本末尾附「与 db-design 的差异说明」**，记录建表时发现的规格自相矛盾与所做判断（共 18 条），供回写主文档时逐条拍板。已回写的见 db-design §十三。
