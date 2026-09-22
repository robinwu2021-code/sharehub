# 库表参考（`pb_core`）

> **本文件由脚本生成，不要手改** —— `python3 backend/scripts/gen-db-doc.py` 重生成。
>
> 它只回答「现在到底是什么」。**为什么这么设计**看 [db-design.md](./db-design.md)，两份文档职责不重叠。

| | |
|---|---|
| 表 | **148** |
| 列 | **2330**，其中业务列 **1319**、标准列 1011 |
| 结构来源 | 实际库 `information_schema`（**不是 DDL 文件** —— `IF NOT EXISTS` 落到既有表上是空操作，文件写了不代表库里有） |
| 注释来源 | 业务列中库内 774 列自带；另 43 列库里为空、回落到 `docs/technical/ddl/` 同名列（表格中标 `†`）；仍缺 502 列 |
| 实体映射 | 137 张表有对应 Java 实体，10 张没有 |

## 标准列（每张表都有，下文各表不再重复列出）

这 8 列由 `BaseEntity` + 租户 + 审计约定统一提供，在 148 张表上共占 1011 列。逐表列出来只有噪音，会把真正的业务列淹掉，所以这里讲一次，各表只标注「标准列是否齐备」。

| 列 | 说明 |
|---|---|
| `id` | 主键，`BIGINT AUTO_INCREMENT`，全表统一 |
| `tenant_id` | 租户隔离键；全局表（字典/主数据）没有此列，实体用 `@TableName(excludeProperty="tenantId")` 排除 |
| `created_at` | 创建时间，MyBatis-Plus `INSERT` 自动填充 |
| `created_by` | 创建人；无登录主体时写 `SYSTEM`（与 `NULL`=漏填可区分） |
| `updated_at` | 更新时间，`INSERT_UPDATE` 自动填充 |
| `updated_by` | 更新人，同上 |
| `version` | 乐观锁；追加表（append-only）没有此列 |
| `deleted` | 逻辑删除标记；追加表没有此列 |

## 只有 `pb_core` 落地

设计里还有 `pb_pii`（个人信息隔离）与 `pb_auth`（凭证隔离）两个库，**开发库尚未创建**，相关字段目前仍在 `pb_core` 内。上线前必须拆出去，否则 PDPL 的「个人信息与业务数据物理隔离」这条对不上。

## 无对应实体的表（10）

这些表没有 `@TableName` 指向，要么是纯关联表（由主实体的 mapper 直接操作），要么是**建了但代码还没接**。后者在补业务逻辑时会被漏掉，逐张确认过再删本节。

`dev_alert` · `gw_vendor_device_type` · `iam_data_scope` · `iam_menu` · `iam_permission` · `iam_role` · `iam_role_perm` · `md_device_type` · `ord_charge_ext` · `ord_locker_ext`

## 目录

- **账务（`acct_*`）** — 2 张：`acct_account`、`acct_ledger`
- **广告（`ad_*`）** — 6 张：`ad_advertiser`、`ad_campaign`、`ad_creative`、`ad_impression`、`ad_placement`、`ad_slot`
- **代理商（`agt_*`）** — 5 张：`agt_account`、`agt_agent`、`agt_agent_region`、`agt_assignment`、`agt_commission`
- **其他** — 8 张：`coupon_tpl`、`mbr_benefit`、`mbr_plan`、`openapi_app`、`recon_diff`、`recon_task`、`tenant`、`tenant_config`
- **客服（`cs_*`）** — 3 张：`cs_message`、`cs_session`、`cs_ticket`
- **设备（`dev_*`）** — 14 张：`dev_alarm`、`dev_alarm_code`、`dev_alarm_notice`、`dev_alarm_rule`、`dev_alert`、`dev_cabinet`、`dev_code_batch`、`dev_heartbeat`、`dev_ota_release`、`dev_ota_rollout`、`dev_ota_task`、`dev_powerbank`、`dev_shadow`、`dev_slot`
- **数据字典（`dict_*`）** — 1 张：`dict_item`
- **财务（`fin_*`）** — 2 张：`fin_invoice`、`fin_invoice_item`
- **迁移元数据（`flyway_*`）** — 1 张：`flyway_schema_history`
- **设备网关（`gw_*`）** — 6 张：`gw_command_log`、`gw_device_binding`、`gw_message_log`、`gw_vendor`、`gw_vendor_config`、`gw_vendor_device_type`
- **身份与权限（`iam_*`）** — 10 张：`iam_audit_log`、`iam_data_scope`、`iam_dept`、`iam_employee`、`iam_employee_role`、`iam_menu`、`iam_permission`、`iam_role`、`iam_role_perm`、`iam_staff_perf`
- **库存（`inv_*`）** — 4 张：`inv_stock`、`inv_transfer`、`inv_transfer_item`、`inv_warehouse`
- **场地与点位（`loc_*`）** — 10 张：`loc_contract`、`loc_contract_attach`、`loc_lead`、`loc_lead_follow`、`loc_location`、`loc_site`、`loc_site_lifecycle`、`loc_site_lifecycle_log`、`loc_venue`、`loc_venue_onboarding`
- **主数据（`md_*`）** — 5 张：`md_bank`、`md_device_type`、`md_market_country`、`md_problem`、`md_region`
- **营销（`mkt_*`）** — 5 张：`mkt_campaign`、`mkt_notice`、`mkt_push`、`mkt_referral`、`mkt_referral_rule`
- **通知（`notify_*`）** — 3 张：`notify_blacklist`、`notify_log`、`notify_template`
- **订单（`ord_*`）** — 11 张：`ord_charge_ext`、`ord_complaint`、`ord_deposit`、`ord_event_log`、`ord_exception`、`ord_intervention`、`ord_locker_ext`、`ord_order`、`ord_refund`、`ord_rent_ext`、`ord_reservation`
- **支付（`pay_*`）** — 6 张：`pay_auth`、`pay_channel`、`pay_channel_scope`、`pay_event_log`、`pay_order`、`pay_refund`
- **计价（`price_*`）** — 6 张：`price_ladder`、`price_plan`、`price_plan_item`、`price_plan_scope`、`price_rule`、`price_schedule`
- **分润（`share_*`）** — 2 张：`share_record`、`share_rule`
- **结算（`stl_*`）** — 3 张：`stl_settlement`、`stl_settlement_detail`、`stl_withdrawal`
- **系统配置（`sys_*`）** — 7 张：`sys_app_version`、`sys_biz_rule`、`sys_login_setting`、`sys_outbox`、`sys_param`、`sys_tax_setting`、`sys_token`
- **用户（`usr_*`）** — 22 张：`usr_blacklist`、`usr_consent`、`usr_coupon`、`usr_coupon_issue`、`usr_credit`、`usr_credit_change`、`usr_favorite`、`usr_free_whitelist`、`usr_identity`、`usr_invoice`、`usr_invoice_title`、`usr_logoff`、`usr_membership`、`usr_message`、`usr_notify_pref`、`usr_push_token`、`usr_recharge_order`、`usr_recharge_pkg`、`usr_recharge_pkg_market`、`usr_user`、`usr_wallet`、`usr_wallet_txn`
- **工单（`wo_*`）** — 6 张：`wo_dispatch`、`wo_handle`、`wo_inspection_plan`、`wo_order`、`wo_sla`、`wo_sla_rule`


---

## 账务（`acct_*`）

### `acct_account` — 记账账户

实体 `AcctAccount` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `account_no` | `varchar(36)` | 否 | — | UQ | — |
| `owner_type` | `varchar(16)` | 否 | — | IX | PLATFORM/VENUE/AGENT/DEPOSIT |
| `owner_no` | `varchar(36)` | 是 | — |  | — |
| `acct_type` | `varchar(24)` | 否 | — |  | 科目性质：ASSET/EXPENSE(借增，余额=Σ借−Σ贷) · LIABILITY/EQUITY/REVENUE(贷增，余额=Σ贷−Σ借)。旧值 CASH→ASSET、PAYABLE→LIABILITY |
| `balance` | `decimal(18,2)` | 否 | `0.00` |  | 账户余额（方向已按 acct_type 归一：任何科目的正数都表示「该科目方向上的余额」，不需要调用方再判方向） |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `frozen` | `decimal(18,2)` | 是 | — |  | 约定:金额 |

索引：`idx_acct_owner`(owner_type,owner_no) · `uk_account_no`(account_no) **UNIQUE**

### `acct_ledger` — 复式分录(append)

实体 `AcctLedger` · 业务列 11 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `entry_no` | `varchar(36)` | 否 | — | UQ | — |
| `voucher_no` | `varchar(36)` | 否 | — | IX | 凭证(一笔业务借贷成对) |
| `order_no` | `varchar(36)` | 是 | — | IX | — |
| `account` | `varchar(36)` | 否 | — |  | account_no |
| `direction` | `varchar(8)` | 否 | — |  | DEBIT/CREDIT |
| `amount` | `decimal(18,2)` | 否 | — |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `summary` | `varchar(128)` | 是 | — |  | — |
| `account_no` | `varchar(36)` | 是 | — |  | 既有DDL同名列×1 |
| `biz_no` | `varchar(36)` | 是 | — |  | 既有DDL同名列×1 |
| `biz_type` | `varchar(24)` | 是 | — |  | 既有DDL同名列×1 |

索引：`idx_ledger_order`(order_no) · `idx_ledger_voucher`(voucher_no) · `uk_entry_no`(entry_no) **UNIQUE**


---

## 广告（`ad_*`）

### `ad_advertiser` — 广告主

实体 `AdAdvertiser` · 业务列 3 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `advertiser_no` | `varchar(36)` | 否 | — | UQ | — |
| `name` | `varchar(128)` | 否 | — |  | — |
| `contact` | `varchar(64)` | 是 | — |  | — |

索引：`uk_advertiser_no`(advertiser_no) **UNIQUE**

### `ad_campaign` — 广告活动

实体 `AdCampaign` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `campaign_no` | `varchar(36)` | 否 | — | UQ | — |
| `advertiser_no` | `varchar(36)` | 否 | — | IX | — |
| `budget` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `target` | `longtext` | 是 | — |  | 定向 region/site/scene |
| `start_at` | `date` | 是 | — |  | — |
| `end_at` | `date` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'DRAFT'` |  | — |
| `ad_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `advertiser` | `longtext` | 是 | — |  | db-design 标 JSON |
| `creative` | `longtext` | 是 | — |  | db-design 标 JSON |
| `currency` | `varchar(8)` | 是 | — |  | 既有DDL同名列×23 |
| `targeting` | `longtext` | 是 | — |  | db-design 标 JSON |

索引：`idx_campaign_adv`(advertiser_no) · `uk_campaign_no`(campaign_no) **UNIQUE**

### `ad_creative` — 广告创意

实体 `AdCreative` · 业务列 6 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `creative_no` | `varchar(36)` | 否 | — | UQ | — |
| `campaign_no` | `varchar(36)` | 否 | — | IX | — |
| `media_url` | `varchar(512)` | 是 | — |  | — |
| `duration` | `int(11)` | 是 | — |  | — |
| `mime` | `varchar(32)` | 是 | — |  | — |
| `ad_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |

索引：`idx_creative_campaign`(campaign_no) · `uk_creative_no`(creative_no) **UNIQUE**

### `ad_impression` — 广告曝光(append,月分区)

实体 `AdImpression` · 业务列 10 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `placement_no` | `varchar(36)` | 否 | — | IX | — |
| `cabinet_no` | `varchar(36)` | 是 | — |  | — |
| `played_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `duration` | `int(11)` | 是 | — |  | — |
| `ad_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `delivery_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `impressions` | `int(11)` | 是 | — |  | 约定:整数 |
| `plays` | `int(11)` | 是 | — |  | 约定:整数 |
| `slot_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `stat_date` | `date` | 是 | — |  | 人工定型：曝光按日聚合（db-design：impressions/plays/stat_date），日粒度用 DATE |

索引：`idx_impr_placement_time`(placement_no,played_at)

### `ad_placement` — 广告投放排期

实体 `AdPlacement` · 业务列 8 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `placement_no` | `varchar(36)` | 否 | — | UQ | — |
| `campaign_no` | `varchar(36)` | 否 | — |  | — |
| `creative_no` | `varchar(36)` | 否 | — |  | — |
| `ad_slot_no` | `varchar(36)` | 否 | — | IX | — |
| `schedule` | `longtext` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `ad_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `slot_no` | `longtext` | 是 | — |  | db-design 标 JSON |

索引：`idx_placement_slot`(ad_slot_no) · `uk_placement_no`(placement_no) **UNIQUE**

### `ad_slot` — 广告位

实体 `AdSlot` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `ad_slot_no` | `varchar(36)` | 否 | — | UQ | — |
| `cabinet_no` | `varchar(36)` | 否 | — | IX | — |
| `type` | `varchar(16)` | 否 | `'SCREEN'` |  | SCREEN/BODY |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `position` | `varchar(32)` | 是 | — |  | 约定:枚举/短码 |
| `size` | `varchar(32)` | 是 | — |  | 约定:枚举/短码 |
| `slot_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |

索引：`idx_adslot_cabinet`(cabinet_no) · `uk_ad_slot_no`(ad_slot_no) **UNIQUE**


---

## 代理商（`agt_*`）

### `agt_account` — 代理登录账号

实体 `AgtAccount` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `account_no` | `varchar(36)` | 否 | — | UQ | — |
| `agent_no` | `varchar(36)` | 否 | — | IX | 所属代理 |
| `username` | `varchar(64)` | 否 | — |  | — |
| `cred_ref` | `varchar(64)` | 是 | — |  | pb_auth.cred 引用(realm=AGENT) |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `agent_name` | `varchar(64)` | 是 | — |  | 既有DDL同名列×1 |
| `login_phone` | `varchar(32)` | 是 | — |  | 人工定型：手机号（含国际区号），明文脱敏值；完整明文在 pb_pii |

索引：`idx_agt_account_agent`(agent_no) · `uk_agt_account_no`(account_no) **UNIQUE** · `uk_agt_username`(tenant_id,username) **UNIQUE**

### `agt_agent`

实体 `AgtAgent` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `agent_no` | `varchar(36)` | 否 | — | UQ | — |
| `name` | `varchar(128)` | 否 | — |  | 代理名称 † |
| `contact` | `varchar(64)` | 是 | — |  | 敏感明文落 pii † |
| `region_scope` | `varchar(128)` | 是 | — |  | 辖域(区域数组) † |
| `share_rate` | `decimal(6,4)` | 否 | `0.0000` |  | — |
| `cabinet_count` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/SUSPENDED † |
| `default_share_rate` | `decimal(5,4)` | 否 | `0.0000` |  | 默认分润比例 |
| `settle_account` | `varchar(128)` | 是 | — |  | 结算账户(nearpay 收款方) |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_agent_archived`(tenant_id,archived_at) · `uk_agent_no`(agent_no) **UNIQUE**

### `agt_agent_region` — 代理辖域(多值拆表)

实体 `AgtAgentRegion` · 业务列 2 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `agent_no` | `varchar(36)` | 否 | — | IX | 代理商 |
| `region_id` | `varchar(36)` | 否 | — | IX | 辖区(→md_region.region_id) |

索引：`idx_agent_region_region`(region_id) · `uk_agent_region`(agent_no,region_id) **UNIQUE**

### `agt_assignment` — ä»£ç†è®¾å¤‡/ç‚¹ä½åˆ’æ‹¨è®°å½•(append)

实体 `AgtAssignment` · 业务列 6 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `assign_no` | `varchar(36)` | 否 | — | UQ | ä¸šåŠ¡é”® |
| `agent_no` | `varchar(36)` | 否 | — | IX | ä»£ç†å•† |
| `target_type` | `varchar(16)` | 否 | — | IX | CABINET/LOCATION/SITE |
| `target_no` | `varchar(36)` | 否 | — |  | è¢«åˆ’æ‹¨å¯¹è±¡ä¸šåŠ¡é”® |
| `action` | `varchar(16)` | 否 | — |  | ASSIGN/REVOKE |
| `operator` | `varchar(64)` | 是 | — |  | æ“ä½œäºº(employee_no) |

索引：`idx_assign_agent_time`(agent_no,created_at) · `idx_assign_target`(target_type,target_no) · `uk_assign_no`(assign_no) **UNIQUE**

### `agt_commission` — 代理分润配置

实体 `AgtCommission` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `rule_no` | `varchar(36)` | 否 | — | UQ | 业务键 AC* |
| `agent_no` | `varchar(36)` | 否 | — |  | 代理商 |
| `agent_name` | `varchar(128)` | 是 | — |  | 冗余展示名(写入时快照,不回溯) |
| `dimension` | `varchar(16)` | 否 | `'GMV'` |  | GMV/ORDER_COUNT |
| `rate` | `decimal(5,4)` | 否 | `0.0000` |  | 分润比例 0..1 |
| `fixed_amount` | `decimal(18,2)` | 否 | `0.00` |  | dimension=ORDER_COUNT 时的单均金额 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `mode` | `varchar(16)` | 否 | `'LEDGER'` |  | CHANNEL_SPLIT/LEDGER |
| `effective_at` | `date` | 是 | — |  | 生效日(仅日期) |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/INACTIVE |

索引：`idx_agt_commission_agent`(tenant_id,agent_no,status) · `uk_agt_commission_no`(rule_no) **UNIQUE**


---

## 其他

### `coupon_tpl` — 券模板

实体 `CouponTpl` · 业务列 11 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `tpl_no` | `varchar(36)` | 否 | — | UQ | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `type` | `varchar(16)` | 否 | — |  | CUT/DISCOUNT |
| `value` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `threshold` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `valid_rule` | `longtext` | 是 | — |  | — |
| `stock` | `int(11)` | 否 | `0` |  | — |
| `issued` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `currency` | `varchar(8)` | 是 | — |  | 既有DDL同名列×23 |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`uk_tpl_no`(tpl_no) **UNIQUE**

### `mbr_benefit` — 会员等级权益

实体 `MbrBenefit` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `level` | `varchar(16)` | 否 | — |  | SILVER/GOLD/PLATINUM，由低到高 |
| `name` | `varchar(32)` | 否 | — |  | 等级展示名，与页面徽标共用一份文案 |
| `rent_discount` | `decimal(5,4)` | 否 | `1.0000` |  | 租金折扣；0.9=九折，1=不打折 |
| `free_minutes` | `int(11)` | 否 | `0` |  | 每单免费时长（分钟） |
| `deposit_free` | `tinyint(1)` | 否 | `0` |  | 是否免押金 |
| `monthly_coupons` | `int(11)` | 否 | `0` |  | 每月赠券张数 |
| `points_rate` | `decimal(8,2)` | 否 | `1.00` |  | 消费 1 元累计积分数 |
| `upgrade_points` | `int(11)` | 否 | `0` |  | 升到本级所需累计积分；最低档为 0 |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | — |

索引：`uk_mbr_benefit_level`(tenant_id,level) **UNIQUE**

### `mbr_plan` — 会员方案

实体 `MbrPlan` · 业务列 13 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `plan_no` | `varchar(36)` | 否 | — | UQ | 业务键 |
| `name` | `varchar(128)` | 否 | — |  | 方案名 |
| `name_en` | `varchar(128)` | 是 | — |  | — |
| `name_ar` | `varchar(128)` | 是 | — |  | — |
| `card_type` | `varchar(16)` | 否 | `'MONTHLY'` |  | MONTHLY(包月)/TIMES(次卡)/RIGHTS(权益卡) |
| `price` | `decimal(18,2)` | 否 | `0.00` |  | 售价 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `period_days` | `int(11)` | 是 | — |  | 有效期天数(MONTHLY/RIGHTS) |
| `times_total` | `int(11)` | 是 | — |  | 总次数(TIMES 卡) |
| `rights` | `longtext` | 是 | — |  | 权益：免费时长/折扣率/免押提额 |
| `auto_renew` | `tinyint(1)` | 否 | `0` |  | 是否支持自动续费 |
| `sort_no` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/DISABLED |

索引：`idx_mbr_plan_status`(tenant_id,status,sort_no) · `uk_mbr_plan_no`(plan_no) **UNIQUE**

### `openapi_app` — 开放平台应用

实体 `OpenapiApp` · 业务列 8 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `app_no` | `varchar(36)` | 否 | — | UQ | 业务键(前缀 APP) |
| `name` | `varchar(128)` | 否 | — |  | 应用名称 |
| `app_key` | `varchar(64)` | 否 | — | UQ | 调用方标识(公开) |
| `app_secret_hash` | `varchar(128)` | 是 | — |  | 密钥哈希;**明文落 KMS/vault,不入库** |
| `scopes` | `longtext` | 是 | — |  | 授权范围(权限码数组) |
| `rate_limit` | `int(11)` | 否 | `0` |  | 限流(次/分钟,0=不限) |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/DISABLED |
| `secret_reset_at` | `datetime(3)` | 是 | — |  | 上次密钥重置时间(null=从未重置) |

索引：`idx_openapi_tenant`(tenant_id,status) · `uk_openapi_app_key`(app_key) **UNIQUE** · `uk_openapi_app_no`(app_no) **UNIQUE**

### `recon_diff` — 对账差错

实体 `ReconDiff` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `batch_no` | `varchar(36)` | 否 | — | IX | — |
| `pay_no` | `varchar(36)` | 是 | — | IX | → pay_order.pay_no(单边账时可空) |
| `diff_type` | `varchar(24)` | 否 | — |  | MISSING_LOCAL/MISSING_CHANNEL/AMOUNT_MISMATCH/STATUS_MISMATCH/DUPLICATE |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | 差额 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `detail` | `longtext` | 是 | — |  | — |
| `resolved` | `tinyint(1)` | 否 | `0` |  | — |

索引：`idx_rdiff_batch`(batch_no,resolved) · `idx_rdiff_pay`(pay_no)

### `recon_task` — 对账任务(批次)

实体 `ReconTask` · 业务列 15 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `batch_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `channel` | `varchar(32)` | 否 | — |  | → pay_channel.channel_code |
| `period` | `char(7)` | 否 | — |  | 账期 YYYY-MM |
| `bill_date` | `date` | 否 | — |  | 账单日(仅日期) |
| `nearpay_total` | `decimal(18,2)` | 否 | `0.00` |  | 渠道侧合计 |
| `ledger_total` | `decimal(18,2)` | 否 | `0.00` |  | 账务侧合计 |
| `diff` | `decimal(18,2)` | 否 | `0.00` |  | 差额=nearpay_total-ledger_total |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `status` | `varchar(16)` | 否 | `'MATCHED'` |  | MATCHED/DIFF |
| `handle_status` | `varchar(16)` | 是 | — |  | 处置进度：OPEN/HANDLING/RESOLVED/IGNORED,NULL=已平无差错 |
| `handle_result` | `varchar(16)` | 是 | — |  | 处置结论：VERIFIED_OK/PLATFORM_ERROR/CHANNEL_ERROR/COMPENSATED |
| `handle_note` | `varchar(255)` | 是 | — |  | 处置说明(金额/凭证号/对接人) |
| `handled_by` | `varchar(36)` | 是 | — |  | 处置人 |
| `handled_at` | `datetime(3)` | 是 | — |  | 处置时间 |

索引：`idx_recon_period`(tenant_id,period,status) · `uk_recon_batch_no`(batch_no) **UNIQUE** · `uk_recon_channel_date`(tenant_id,channel,bill_date) **UNIQUE**

### `tenant` — 租户(休眠口子·全局表)

实体 `Tenant` · 业务列 8 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `tenant_no` | `varchar(36)` | 否 | — | UQ | 业务键 T*，业务表 tenant_id 存的就是它 |
| `name` | `varchar(128)` | 否 | — |  | 运营主体名 |
| `brand_name` | `varchar(128)` | 是 | — |  | 品牌名(C端展示) |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/SUSPENDED |
| `plan` | `varchar(32)` | 是 | — |  | 套餐 |
| `quota` | `longtext` | 是 | — |  | 配额(设备数/订单量/坐席) |
| `contact` | `varchar(64)` | 是 | — |  | 联系方式(掩码;明文落 pb_pii) |
| `expire_at` | `datetime(3)` | 是 | — |  | 到期时间 |

索引：`uk_tenant_no`(tenant_no) **UNIQUE**

### `tenant_config` — 租户配置(休眠口子)

实体 `TenantConfig` · 业务列 4 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `tenant_no` | `varchar(36)` | 否 | — | IX | — |
| `category` | `varchar(16)` | 否 | — |  | PAY/BILLING/BRAND/VENDOR |
| `config_key` | `varchar(64)` | 否 | — |  | — |
| `config_value` | `longtext` | 是 | — |  | — |

索引：`uk_tcfg`(tenant_no,category,config_key) **UNIQUE**


---

## 客服（`cs_*`）

### `cs_message` — 客服会话消息(append,月分区)

实体 `CsMessage` · 业务列 5 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `session_no` | `varchar(36)` | 否 | — | IX | — |
| `sender_type` | `varchar(8)` | 否 | — |  | USER/AGENT |
| `sender_no` | `varchar(36)` | 是 | — |  | c_user_no 或 employee_no |
| `content` | `text` | 是 | — |  | — |
| `attach` | `longtext` | 是 | — |  | 图片/文件 URL 列表 |

索引：`idx_csmsg_session`(session_no,created_at)

### `cs_session` — 客服会话

实体 `CsSession` · 业务列 6 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `session_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `agent_name` | `varchar(64)` | 是 | — |  | 客服坐席快照名(不回溯) |
| `last_message` | `varchar(512)` | 是 | — |  | 最后一条消息摘要(列表直出) |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/CLOSED |

索引：`idx_csss_tenant_status`(tenant_id,status,updated_at) · `idx_csss_user`(tenant_id,c_user_no,created_at) · `uk_cs_session_no`(session_no) **UNIQUE**

### `cs_ticket` — 报障受理

实体 `CsTicket` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `ticket_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `order_no` | `varchar(36)` | 是 | — | IX | — |
| `cabinet_no` | `varchar(36)` | 是 | — |  | — |
| `problem_no` | `varchar(36)` | 是 | — |  | → md_problem.problem_no |
| `issue` | `varchar(512)` | 是 | — |  | 用户描述 |
| `channel` | `varchar(16)` | 否 | `'APP'` |  | APP/MP/H5/PHONE/EMAIL |
| `status` | `varchar(16)` | 否 | `'OPEN'` |  | OPEN/PROCESSING/CLOSED |
| `handler_no` | `varchar(36)` | 是 | — |  | employee_no |
| `wo_no` | `varchar(36)` | 是 | — |  | 出口①转工单 |
| `refund_no` | `varchar(36)` | 是 | — |  | 出口②转退款 → ord_refund.refund_no |

索引：`idx_cstk_order`(order_no) · `idx_cstk_tenant_status`(tenant_id,status,created_at) · `idx_cstk_user`(tenant_id,c_user_no,created_at) · `uk_ticket_no`(ticket_no) **UNIQUE**


---

## 设备（`dev_*`）

### `dev_alarm` — 告警记录(聚合根,v1 dev_alert 改造更名)

实体 `DevAlarm` · 业务列 16 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `alarm_no` | `varchar(36)` | 否 | — | UQ | 业务键 ALM* |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `cabinet_no` | `varchar(36)` | 是 | — | IX | 告警机柜(逻辑引用) |
| `site_no` | `varchar(36)` | 是 | — | IX | 站点归属(冗余·数据权限) |
| `agent_no` | `varchar(36)` | 是 | — |  | 归属代理(冗余·数据权限 ADR-012) |
| `vendor_code` | `varchar(32)` | 是 | — |  | 供应商 |
| `alarm_code` | `varchar(32)` | 否 | — | IX | 平台统一码(→dev_alarm_code.code) |
| `vendor_error_code` | `varchar(32)` | 是 | — |  | 厂商原始错误码(归一化前) |
| `level` | `varchar(16)` | 否 | `'WARN'` |  | INFO/WARN/CRITICAL |
| `source` | `varchar(16)` | 否 | `'DEVICE'` |  | DEVICE/OTA/RENT |
| `occurred_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | 发生时刻(列表默认排序) |
| `status` | `varchar(16)` | 否 | `'OPEN'` |  | OPEN/ACKED/CLOSED |
| `wo_no` | `varchar(36)` | 是 | — | IX | 转工单(幂等落在 wo_order.source_ref UK) |
| `remark` | `varchar(512)` | 是 | — |  | 处置备注 |
| `dedup_key` | `varchar(128)` | 是 | — | IX | 去重键(同源重复合并计数) |
| `count` | `int(11)` | 否 | `1` |  | 合并次数 |

索引：`idx_alarm_cabinet`(cabinet_no) · `idx_alarm_code`(alarm_code) · `idx_alarm_dedup`(dedup_key) · `idx_alarm_scope`(tenant_id,agent_no,status,occurred_at) · `idx_alarm_site`(site_no) · `idx_alarm_wo`(wo_no) · `uk_alarm_no`(alarm_no) **UNIQUE**

### `dev_alarm_code` — 告警代码字典(全局)

实体 `DevAlarmCode` · 业务列 8 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `code` | `varchar(32)` | 否 | — | UQ | 平台统一告警码(自然键) |
| `message` | `varchar(256)` | 否 | — |  | 描述(中文) |
| `message_en` | `varchar(256)` | 是 | — |  | 描述(英文) |
| `message_ar` | `varchar(256)` | 是 | — |  | 描述(阿语) |
| `level` | `varchar(16)` | 否 | `'WARN'` | IX | INFO/WARN/CRITICAL |
| `suggestion` | `varchar(512)` | 是 | — |  | 建议处置(预案) |
| `auto_work_order` | `tinyint(1)` | 否 | `0` |  | 是否自动开工单 |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_alarm_code_level`(level) · `uk_alarm_code`(code) **UNIQUE**

### `dev_alarm_notice` — 告警通知流水(append)

实体 `DevAlarmNotice` · 业务列 9 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `notice_no` | `varchar(36)` | 否 | — | UQ | 业务键 AN* |
| `alarm_no` | `varchar(36)` | 否 | — | IX | 所属告警 |
| `channel` | `varchar(16)` | 否 | — |  | SMS/EMAIL/PUSH/WEBHOOK |
| `target` | `varchar(128)` | 是 | — |  | 接收方(存储即脱敏) |
| `sent_at` | `datetime(3)` | 是 | — |  | 发送时刻(空=尚未发生) |
| `status` | `varchar(16)` | 否 | `'SENT'` |  | SENT/FAILED |
| `fail_reason` | `varchar(256)` | 是 | — |  | — |
| `idempotency_key` | `varchar(64)` | 是 | — |  | 触发幂等键 |
| `resend_of` | `varchar(36)` | 是 | — |  | 重发来源 notice_no,NULL=首发 |

索引：`idx_alarm_notice_alarm`(alarm_no) · `idx_alarm_notice_time`(tenant_id,created_at) · `uk_alarm_notice_no`(notice_no) **UNIQUE**

### `dev_alarm_rule` — 告警通知规则

实体 `DevAlarmRule` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `rule_no` | `varchar(36)` | 否 | — | UQ | 业务键 AR* |
| `alarm_code` | `varchar(32)` | 是 | — | IX | 匹配告警码(空=全部) |
| `target` | `varchar(128)` | 是 | — |  | 接收方(角色/手机号/webhook) |
| `channel` | `varchar(16)` | 否 | — |  | SMS/EMAIL/PUSH/WEBHOOK |
| `method` | `varchar(16)` | 否 | `'INSTANT'` |  | INSTANT/DIGEST |
| `quiet_start` | `char(5)` | 是 | — |  | 静默窗口开始 HH:mm |
| `quiet_end` | `char(5)` | 是 | — |  | 静默窗口结束 HH:mm |
| `escalate_minutes` | `int(11)` | 是 | — |  | 未处置 N 分钟后升级(空=不升级) |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/INACTIVE |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_alarm_rule_code`(alarm_code) · `idx_alarm_rule_tenant`(tenant_id,status) · `uk_alarm_rule_no`(rule_no) **UNIQUE**

### `dev_alert` — 设备告警

**无实体** · 业务列 9 · 标准列缺 `updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `alert_no` | `varchar(36)` | 否 | — | UQ | — |
| `cabinet_no` | `varchar(36)` | 是 | — | IX | — |
| `source` | `varchar(16)` | 否 | — |  | DEVICE/OTA/RENT |
| `severity` | `varchar(16)` | 否 | `'WARN'` |  | INFO/WARN/CRITICAL |
| `code` | `varchar(32)` | 是 | — |  | — |
| `message` | `varchar(256)` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'OPEN'` |  | OPEN/ACK/RESOLVED |
| `dedup_key` | `varchar(128)` | 是 | — | IX | — |
| `count` | `int(11)` | 否 | `1` |  | — |

索引：`idx_alert_cabinet`(cabinet_no) · `idx_alert_dedup`(dedup_key) · `uk_alert_no`(alert_no) **UNIQUE**

### `dev_cabinet`

实体 `DevCabinet` · 业务列 17 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `cabinet_no` | `varchar(36)` | 否 | — | UQ | 机柜业务键 † |
| `sn` | `varchar(64)` | 是 | — |  | 设备序列号(全局唯一) † |
| `vendor_code` | `varchar(32)` | 是 | — |  | 供应商 † |
| `model` | `varchar(32)` | 是 | — |  | — |
| `location_no` | `varchar(36)` | 是 | — |  | 归属点位 † |
| `site_no` | `varchar(36)` | 是 | — |  | 归属站点(冗余·随点位，数据范围锚点) |
| `agent_no` | `varchar(36)` | 是 | — |  | 归属代理(冗余·随站点，数据范围锚点) |
| `location_name` | `varchar(128)` | 是 | — |  | — |
| `slot_total` | `int(11)` | 否 | `0` |  | — |
| `available_count` | `int(11)` | 否 | `0` |  | — |
| `online_status` | `varchar(16)` | 否 | `'OFFLINE'` |  | ONLINE/OFFLINE † |
| `status` | `varchar(16)` | 否 | `'IN_STOCK'` |  | IN_STOCK 入库未投放(location_no 空)/DEPLOYED 已投放/FAULT 故障停用/RETIRED 退役(终态) |
| `fw_version` | `varchar(32)` | 是 | — |  | — |
| `last_heartbeat_at` | `varchar(40)` | 是 | — |  | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |
| `device_type` | `varchar(32)` | 是 | — |  | 设备类型 POWERBANK/EV_PILE/LOCKER；容器本身设备无关，靠这列区分 |

索引：`idx_cabinet_archived`(tenant_id,archived_at) · `idx_cabinet_device_type`(tenant_id,device_type,status) · `idx_cab_scope`(tenant_id,agent_no,status) · `uk_cabinet_no`(cabinet_no) **UNIQUE**

### `dev_code_batch` — 设备编码批次

实体 `DevCodeBatch` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `batch_no` | `varchar(36)` | 否 | — | UQ | 业务键 BC* |
| `vendor_code` | `varchar(32)` | 是 | — | IX | 供应商 |
| `code_type` | `varchar(16)` | 否 | `'QR'` |  | QR/SN |
| `range_start` | `varchar(64)` | 否 | — |  | 编码区间起 |
| `range_end` | `varchar(64)` | 否 | — |  | 编码区间止 |
| `total` | `int(11)` | 否 | `0` |  | 批次总量 |
| `bound` | `int(11)` | 否 | `0` |  | 已绑定数量 |
| `produced_at` | `date` | 是 | — |  | 生产日期(仅日期) |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/PARTIAL/BOUND/VOID |

索引：`idx_code_batch_tenant`(tenant_id,status) · `idx_code_batch_vendor`(vendor_code) · `uk_code_batch_no`(batch_no) **UNIQUE**

### `dev_heartbeat` — 心跳遥测(append,按 beat_at 月分区)

实体 `DevHeartbeat` · 业务列 3 · 标准列缺 `tenant_id`/`updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `cabinet_no` | `varchar(36)` | 否 | — | IX | — |
| `metrics` | `longtext` | 是 | — |  | fw/信号/温度/电量 |
| `beat_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |

索引：`idx_hb_cab_time`(cabinet_no,beat_at)

### `dev_ota_release` — OTA 版本

实体 `DevOtaRelease` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `release_no` | `varchar(36)` | 否 | — | UQ | — |
| `fw_type` | `varchar(32)` | 否 | — |  | — |
| `vendor_code` | `varchar(32)` | 是 | — |  | 供应商(逻辑引用 gw_vendor) |
| `fw_version` | `varchar(32)` | 否 | — |  | 固件版本号(如 1.4.2) |
| `version_code` | `int(11)` | 否 | — |  | — |
| `artifact_url` | `varchar(512)` | 是 | — |  | — |
| `checksum` | `varchar(128)` | 是 | — |  | — |
| `mandatory` | `tinyint(1)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'DRAFT'` |  | DRAFT/PUBLISHED/PAUSED/COMPLETED |
| `release_notes` | `text` | 是 | — |  | — |
| `version_col` | `bigint(20)` | 否 | `0` |  | — |
| `lock_version` | `bigint(20)` | 是 | — |  | 约定:整数 |

索引：`uk_ota_release_no`(release_no) **UNIQUE**

### `dev_ota_rollout` — OTA 投放

实体 `DevOtaRollout` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `rollout_no` | `varchar(36)` | 否 | — | UQ | — |
| `release_no` | `varchar(36)` | 否 | — | IX | — |
| `fw_version` | `varchar(32)` | 是 | — |  | 冗余展示(取自 release) |
| `vendor_code` | `varchar(32)` | 是 | — |  | — |
| `scope` | `varchar(16)` | 否 | — |  | DEVICE/LOCATION/ALL |
| `target_ref` | `varchar(36)` | 是 | — |  | — |
| `strategy` | `varchar(16)` | 否 | `'GRAY'` |  | GRAY 灰度/FULL 全量 |
| `progress` | `decimal(5,2)` | 否 | `0.00` |  | 完成百分比 0..100 |
| `forced` | `tinyint(1)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/RUNNING/DONE/ROLLBACK |

索引：`idx_rollout_release`(release_no) · `uk_rollout_no`(rollout_no) **UNIQUE**

### `dev_ota_task` — OTA 逐设备任务

实体 `DevOtaTask` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `task_no` | `varchar(36)` | 否 | — | UQ | — |
| `rollout_no` | `varchar(36)` | 否 | — | IX | — |
| `cabinet_no` | `varchar(36)` | 否 | — | IX | — |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/DOWNLOADING/DOWNLOADED/INSTALLING/SUCCESS/FAILED/ROLLED_BACK |
| `progress` | `int(11)` | 否 | `0` |  | — |
| `previous_version` | `varchar(32)` | 是 | — |  | — |
| `error` | `varchar(256)` | 是 | — |  | — |

索引：`idx_task_cabinet`(cabinet_no) · `idx_task_rollout`(rollout_no) · `uk_ota_task_no`(task_no) **UNIQUE**

### `dev_powerbank` — 充电宝

实体 `DevPowerbank` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `powerbank_no` | `varchar(36)` | 否 | — | UQ | — |
| `sn` | `varchar(64)` | 否 | — | UQ | — |
| `vendor_code` | `varchar(32)` | 是 | — |  | — |
| `battery` | `int(11)` | 是 | — |  | — |
| `cycles` | `int(11)` | 否 | `0` |  | 循环次数(健康度) |
| `health` | `varchar(8)` | 否 | `'OK'` |  | OK/FAULT |
| `status` | `varchar(16)` | 否 | `'IN_STOCK'` |  | IN_STOCK 入库未投放/IN_CABINET 在仓可借/RENTED 借出中/FAULT 故障待修/LOST 丢失待追偿(半终态可回收)/SOLD 买断(终态)/SCRAP 报废(终态) |
| `cabinet_no` | `varchar(36)` | 是 | — | IX | 当前所在机柜 |
| `slot_index` | `int(11)` | 是 | — |  | — |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_pb_cabinet`(cabinet_no) · `uk_powerbank_no`(powerbank_no) **UNIQUE** · `uk_powerbank_sn`(sn) **UNIQUE**

### `dev_shadow` — 设备影子(主 Redis,此为落库快照)

实体 `DevShadow` · 业务列 7 · 标准列缺 `created_at`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `cabinet_no` | `varchar(36)` | 否 | — | UQ | — |
| `slots` | `longtext` | 是 | — |  | 仓位快照 |
| `online` | `tinyint(1)` | 否 | `0` |  | — |
| `snapshot_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `signal` | `int(11)` | 是 | — |  | 信号强度 0..100 |
| `temp` | `decimal(5,2)` | 是 | — |  | 机内温度 ℃ |
| `fault_count` | `int(11)` | 否 | `0` |  | 当前未闭环故障数 |

索引：`uk_shadow_cabinet`(cabinet_no) **UNIQUE**

### `dev_slot` — 仓位

实体 `DevSlot` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `cabinet_no` | `varchar(36)` | 否 | — | IX | — |
| `slot_index` | `int(11)` | 否 | — |  | — |
| `powerbank_no` | `varchar(36)` | 是 | — |  | 在仓充电宝 |
| `lock_status` | `varchar(16)` | 否 | `'LOCKED'` |  | LOCKED/UNLOCKED |
| `health` | `varchar(16)` | 否 | `'OK'` |  | OK/FAULT |
| `device_type` | `varchar(32)` | 是 | — |  | 设备类型，随所属容器 |
| `slot_kind` | `varchar(16)` | 是 | — |  | 槽位形态 BAY(仓位)/CONNECTOR(枪)/CELL(格口) |

索引：`uk_slot`(cabinet_no,slot_index) **UNIQUE**


---

## 数据字典（`dict_*`）

### `dict_item` — 参数字典

实体 `DictItem` · 业务列 13 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `dict_type` | `varchar(32)` | 否 | — | IX | — |
| `dict_key` | `varchar(64)` | 否 | — |  | — |
| `dict_value` | `varchar(128)` | 否 | — |  | — |
| `value_ar` | `varchar(128)` | 是 | — |  | — |
| `sort` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `code` | `varchar(32)` | 是 | — |  | 既有DDL同名列×2 |
| `dict_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `enabled` | `tinyint(1)` | 是 | — |  | 既有DDL同名列×1 |
| `group_code` | `varchar(32)` | 是 | — |  | 人工定型：字典分组码，短码 |
| `label` | `varchar(128)` | 是 | — |  | 既有DDL同名列×1 |
| `label_ar` | `varchar(128)` | 是 | — |  | 人工定型：三语文本列，与 label 同宽 |
| `label_en` | `varchar(128)` | 是 | — |  | 人工定型：三语文本列，与 label 同宽 |

索引：`uk_dict`(dict_type,dict_key) **UNIQUE**


---

## 财务（`fin_*`）

### `fin_invoice` — 发票(运营侧开票)

实体 `FinInvoice` · 业务列 19 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `invoice_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `payee_name` | `varchar(128)` | 否 | — |  | 抬头快照(不回溯) |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `vat_trn` | `varchar(32)` | 是 | — |  | 税号 TRN |
| `status` | `varchar(16)` | 否 | `'DRAFT'` |  | DRAFT/ISSUED/VOID |
| `issued_at` | `datetime(3)` | 是 | — |  | 空=尚未开具 |
| `file_url` | `varchar(512)` | 是 | — |  | — |
| `payee_no` | `varchar(36)` | 是 | — |  | 既有DDL同名列×2 |
| `payee_type` | `varchar(16)` | 是 | — |  | 既有DDL同名列×2 |
| `issued_by` | `varchar(64)` | 是 | — |  | 开票人（服务端取登录态） |
| `void_reason` | `varchar(255)` | 是 | — |  | 作废原因；作废时必填 |
| `voided_at` | `datetime(3)` | 是 | — |  | 作废时间 |
| `voided_by` | `varchar(64)` | 是 | — |  | 作废人（服务端取登录态） |
| `invoice_code` | `varchar(32)` | 是 | — |  | 发票代码 |
| `invoice_number` | `varchar(32)` | 是 | — |  | 发票号码 |
| `source_type` | `varchar(16)` | 是 | — |  | 来源单据类型 ORDER/SETTLEMENT |
| `source_no` | `varchar(36)` | 是 | — |  | 来源单据编号 |

索引：`idx_finv_tenant_status`(tenant_id,status,created_at) · `uk_fin_invoice_no`(invoice_no) **UNIQUE**

### `fin_invoice_item` — 发票明细(发票×订单)

实体 `FinInvoiceItem` · 业务列 5 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `invoice_no` | `varchar(36)` | 否 | — |  | fin_invoice.invoice_no 或 usr_invoice.invoice_no |
| `invoice_side` | `varchar(8)` | 否 | `'OPS'` | IX | OPS(fin_invoice)/USER(usr_invoice) 区分号段来源 |
| `order_no` | `varchar(36)` | 否 | — | IX | — |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | 该单计入开票的金额 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |

索引：`idx_finvi_order`(order_no) · `uk_fin_invoice_item`(invoice_side,invoice_no,order_no) **UNIQUE**


---

## 迁移元数据（`flyway_*`）

### `flyway_schema_history`

**无实体** · 业务列 9 · 标准列缺 `id`/`tenant_id`/`created_at`/`created_by`/`updated_at`/`updated_by`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `installed_rank` | `int(11)` | 否 | — | PK | — |
| `description` | `varchar(200)` | 否 | — |  | — |
| `type` | `varchar(20)` | 否 | — |  | — |
| `script` | `varchar(1000)` | 否 | — |  | — |
| `checksum` | `int(11)` | 是 | — |  | — |
| `installed_by` | `varchar(100)` | 否 | — |  | — |
| `installed_on` | `timestamp` | 否 | `current_timestamp()` |  | — |
| `execution_time` | `int(11)` | 否 | — |  | — |
| `success` | `tinyint(1)` | 否 | — | IX | — |

索引：`flyway_schema_history_s_idx`(success)


---

## 设备网关（`gw_*`）

### `gw_command_log` — 指令日志(幂等)

实体 `GwCommandLog` · 业务列 14 · 标准列缺 `updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `command_id` | `varchar(36)` | 否 | — | UQ | 幂等键 |
| `cabinet_no` | `varchar(36)` | 否 | — | IX | — |
| `type` | `varchar(24)` | 否 | — |  | EJECT_ANY/EJECT_SLOT/LOCK/... |
| `params` | `longtext` | 是 | — |  | — |
| `order_no` | `varchar(36)` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/SENT/ACKED/CONFIRMED/TIMEOUT/FAILED |
| `retries` | `int(11)` | 否 | `0` |  | — |
| `confirmed_at` | `datetime(3)` | 是 | — |  | 约定:时间列(实体用String映射) |
| `operator` | `varchar(64)` | 是 | — |  | 既有DDL同名列×3 |
| `payload` | `longtext` | 是 | — |  | db-design 标 JSON |
| `retry` | `int(11)` | 是 | — |  | 约定:整数 |
| `sent_at` | `datetime(3)` | 是 | — |  | 既有DDL同名列×3 |
| `slot_index` | `longtext` | 是 | — |  | db-design 标 JSON |
| `sn` | `varchar(64)` | 是 | — |  | 既有DDL同名列×3 |

索引：`idx_cmd_cabinet`(cabinet_no) · `uk_command_id`(command_id) **UNIQUE**

### `gw_device_binding` — 设备会话绑定

实体 `GwDeviceBinding` · 业务列 7 · 标准列缺 `tenant_id`/`created_at`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `sn` | `varchar(64)` | 否 | — | UQ | — |
| `cabinet_no` | `varchar(36)` | 是 | — |  | — |
| `instance_id` | `varchar(64)` | 是 | — | IX | 网关实例(会话路由) |
| `session_at` | `datetime(3)` | 是 | — |  | — |
| `bound_at` | `datetime(3)` | 是 | — |  | 既有DDL同名列×1 |
| `raw_identity` | `varchar(128)` | 是 | — |  | 人工定型：供应商原始标识，形态各厂商不一，留宽；进 UK(vendor_code,raw_identity) |
| `vendor_code` | `varchar(32)` | 是 | — |  | 既有DDL同名列×3 |

索引：`idx_binding_instance`(instance_id) · `uk_binding_sn`(sn) **UNIQUE**

### `gw_message_log` — 上下行报文留痕(append,月分区,短 TTL)

实体 `GwMessageLog` · 业务列 9 · 标准列缺 `tenant_id`/`updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `sn` | `varchar(64)` | 否 | — | IX | — |
| `direction` | `varchar(8)` | 否 | — |  | UP/DOWN |
| `vendor_code` | `varchar(32)` | 是 | — |  | — |
| `raw` | `varbinary(2048)` | 是 | — |  | 原始报文(脱敏) |
| `cabinet_no` | `varchar(36)` | 是 | — |  | 既有DDL同名列×6 |
| `event_type` | `longtext` | 是 | — |  | db-design 标 JSON |
| `occurred_at` | `datetime(3)` | 是 | — |  | 既有DDL同名列×1 |
| `parsed_event` | `longtext` | 是 | — |  | db-design 标 JSON |
| `result` | `varchar(32)` | 是 | — |  | 约定:枚举/短码 |

索引：`idx_msg_sn_time`(sn,created_at)

### `gw_vendor` — 硬件供应商

实体 `GwVendor` · 业务列 6 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `vendor_code` | `varchar(32)` | 否 | — | UQ | — |
| `name` | `varchar(128)` | 否 | — |  | — |
| `access_mode` | `varchar(16)` | 否 | — |  | TCP/MQTT/HTTP_API |
| `api_base` | `varchar(256)` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/DISABLED |
| `device_count` | `int(11)` | 是 | — |  | 约定:整数 |

索引：`uk_vendor_code`(vendor_code) **UNIQUE**

### `gw_vendor_config` — 供应商接入配置

实体 `GwVendorConfig` · 业务列 11 · 标准列缺 `version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `vendor_code` | `varchar(32)` | 否 | — | IX | — |
| `secret` | `varchar(512)` | 是 | — |  | [KMS] |
| `callback_url` | `varchar(256)` | 是 | — |  | — |
| `sign_type` | `varchar(32)` | 是 | — |  | — |
| `params` | `longtext` | 是 | — |  | driver 参数 |
| `api_base` | `varchar(256)` | 是 | — |  | 既有DDL同名列×2 |
| `app_key` | `varchar(64)` | 是 | — |  | 既有DDL同名列×1 |
| `app_secret` | `varchar(255)` | 是 | — |  | 人工定型：[KMS] 密文/掩码，非明文（db-design 标 [KMS]） |
| `ip_whitelist` | `longtext` | 是 | — |  | db-design 标 JSON |
| `verify_key` | `varchar(255)` | 是 | — |  | 人工定型：[KMS] 验签密钥，与 app_secret 必须分列（合一则无法单独轮换） |
| `capability_manifest` | `longtext` | 是 | — |  | driver 声明的 commands/events 白名单 |

索引：`uk_vendor_cfg`(vendor_code,tenant_id) **UNIQUE**

### `gw_vendor_device_type` — 供应商支持的设备类型（多值拆表）

**无实体** · 业务列 5 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `vendor_code` | `varchar(32)` | 否 | — | IX | — |
| `device_type` | `varchar(32)` | 否 | — |  | POWERBANK/EV_PILE/LOCKER |
| `protocol` | `varchar(32)` | 否 | — |  | VENDOR_CLOUD(A厂商云)/DIRECT(B直连)/OCPP16J/OCPP201(C标准) |
| `command_family` | `varchar(32)` | 否 | — |  | 指令族 |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | — |

索引：`uk_vendor_device`(vendor_code,device_type) **UNIQUE**


---

## 身份与权限（`iam_*`）

### `iam_audit_log` — 操作审计(append,可按月分区)

实体 `IamAuditLog` · 业务列 8 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `actor` | `varchar(64)` | 是 | — | IX | 操作人 |
| `action` | `varchar(64)` | 否 | — |  | 权限码/动作 |
| `target` | `varchar(128)` | 是 | — |  | 对象业务键 |
| `detail` | `longtext` | 是 | — |  | 脱敏摘要 |
| `ip` | `varchar(45)` | 是 | — |  | — |
| `actor_name` | `varchar(128)` | 是 | — |  | 约定:名称 |
| `target_no` | `longtext` | 是 | — |  | db-design 标 JSON |
| `target_type` | `longtext` | 是 | — |  | db-design 标 JSON |

索引：`idx_audit_actor`(actor) · `idx_audit_tenant_time`(tenant_id,created_at)

### `iam_data_scope`

**无实体** · 业务列 4 · 标准列缺 `tenant_id`/`version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `subject_type` | `varchar(16)` | 否 | — | IX | ROLE/EMPLOYEE(员工级覆盖角色默认，二者并集) † |
| `subject_no` | `varchar(36)` | 否 | — |  | — |
| `scope_type` | `varchar(16)` | 否 | — |  | ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF † |
| `scope_refs` | `varchar(512)` | 是 | — |  | 区域/站点/点位/代理 列表 † |

索引：`uk_scope_subject`(subject_type,subject_no) **UNIQUE**

### `iam_dept` — 组织架构

实体 `IamDept` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `dept_no` | `varchar(36)` | 否 | — | UQ | 业务键 D* |
| `parent_no` | `varchar(36)` | 是 | — |  | 上级部门(自引用,逻辑) |
| `name` | `varchar(64)` | 否 | — |  | — |
| `name_en` | `varchar(64)` | 是 | — |  | — |
| `name_ar` | `varchar(64)` | 是 | — |  | — |
| `leader_no` | `varchar(36)` | 是 | — |  | 负责人(iam_employee.employee_no) |
| `leader_name` | `varchar(64)` | 是 | — |  | 负责人名快照(冗余) |
| `path` | `varchar(512)` | 是 | — |  | 祖先路径 /D1/D3/ 便于子树查询 |
| `sort` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/DISABLED |

索引：`idx_dept_parent`(tenant_id,parent_no,sort) · `idx_dept_path`(tenant_id,path) · `uk_dept_no`(dept_no) **UNIQUE**

### `iam_employee` — 员工

实体 `IamEmployee` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `employee_no` | `varchar(36)` | 否 | — | UQ | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `phone` | `varchar(32)` | 是 | — |  | 敏感明文落 pii |
| `dept_no` | `varchar(36)` | 是 | — | IX | 所属部门 |
| `cred_ref` | `varchar(64)` | 是 | — |  | pb_auth.cred 引用(realm=STAFF) |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/LEFT |
| `email` | `varchar(128)` | 是 | — |  | 人工定型：邮箱 |
| `role_no` | `varchar(36)` | 是 | — |  | 既有DDL同名列×3 |
| `user_id` | `varchar(36)` | 是 | — |  | 人工定型：pb_auth 凭据引用（业务键宽度） |

索引：`idx_emp_dept`(dept_no) · `idx_emp_tenant`(tenant_id) · `uk_employee_no`(employee_no) **UNIQUE**

### `iam_employee_role` — 员工角色映射

实体 `IamEmployeeRole` · 业务列 2 · 标准列缺 `tenant_id`/`version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `employee_no` | `varchar(36)` | 否 | — | IX | — |
| `role_no` | `varchar(36)` | 否 | — |  | — |

索引：`uk_emp_role`(employee_no,role_no) **UNIQUE**

### `iam_menu`

**无实体** · 业务列 14 · 标准列缺 `tenant_id`/`version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `menu_no` | `varchar(36)` | 否 | — | UQ | — |
| `parent_no` | `varchar(36)` | 是 | — | IX | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `name_ar` | `varchar(64)` | 是 | — |  | — |
| `type` | `varchar(8)` | 否 | `'MENU'` |  | DOMAIN(L1)/MODULE(L2)/MENU(L3)/DEEPLINK † |
| `path` | `varchar(128)` | 是 | — |  | 路由(可含 ?tab=/?view=) † |
| `icon` | `varchar(32)` | 是 | — |  | — |
| `sort` | `int(11)` | 否 | `0` |  | — |
| `perm` | `varchar(64)` | 是 | — |  | 权限码(空=跟随父模块) † |
| `visible` | `tinyint(1)` | 否 | `1` |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `group_name` | `varchar(32)` | 是 | — |  | L3 分组小标题(如「资产台账」) |
| `name_en` | `varchar(64)` | 是 | — |  | — |
| `phase` | `tinyint(4)` | 否 | `1` |  | 对外交付批次 1/2/3(分期屏蔽) |

索引：`idx_menu_parent`(parent_no,sort) · `uk_menu_no`(menu_no) **UNIQUE**

### `iam_permission`

**无实体** · 业务列 3 · 标准列缺 `tenant_id`/`version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `code` | `varchar(64)` | 否 | — | UQ | 如 order:refund:audit † |
| `module` | `varchar(32)` | 否 | — | IX | 模块前缀 † |
| `name` | `varchar(64)` | 否 | — |  | — |

索引：`idx_perm_module`(module) · `uk_perm_code`(code) **UNIQUE**

### `iam_role`

**无实体** · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `role_no` | `varchar(36)` | 否 | — | UQ | — |
| `code` | `varchar(32)` | 否 | — |  | ADMIN/OPS/CS/FINANCE/BD/VIEWER/AGENT † |
| `name` | `varchar(64)` | 否 | — |  | — |
| `builtin` | `tinyint(1)` | 否 | `0` |  | 内置角色只读 † |
| `data_scope` | `varchar(16)` | 否 | `'ALL'` |  | ALL/REGION/LOCATION/AGENT/SELF † |
| `scope_refs` | `varchar(512)` | 是 | — |  | 范围明细(区域/站点/代理 列表) † |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`uk_role_no`(role_no) **UNIQUE**

### `iam_role_perm`

**无实体** · 业务列 2 · 标准列缺 `tenant_id`/`version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `role_no` | `varchar(36)` | 否 | — | IX | — |
| `perm_code` | `varchar(64)` | 否 | — |  | 支持通配 * / device:* † |

索引：`uk_role_perm`(role_no,perm_code) **UNIQUE**

### `iam_staff_perf` — 员工绩效

实体 `IamStaffPerf` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `employee_no` | `varchar(36)` | 否 | — | IX | 员工业务键(逻辑引用 iam_employee) |
| `employee_name` | `varchar(64)` | 是 | — |  | 姓名快照(写入时冗余,不随源改名回溯) |
| `period` | `char(7)` | 否 | — |  | 账期 YYYY-MM |
| `role` | `varchar(32)` | 是 | — |  | 统计口径角色码 OPS/CS/FINANCE/BD/... |
| `handled` | `int(11)` | 否 | `0` |  | 期内处理单量(工单+工单外受理) |
| `avg_resolve_mins` | `int(11)` | 是 | — |  | 平均解决时长(分钟) |
| `score` | `decimal(5,2)` | 是 | — |  | 绩效评分 0..100(百分数口径) |

索引：`idx_staff_perf_tenant_period`(tenant_id,period) · `uk_staff_perf`(employee_no,period) **UNIQUE**


---

## 库存（`inv_*`）

### `inv_stock` — 仓/区域库存

实体 `InvStock` · 业务列 4 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `warehouse_no` | `varchar(36)` | 否 | — | IX | 所属仓库 |
| `item_type` | `varchar(16)` | 否 | — |  | CABINET/POWERBANK |
| `model` | `varchar(32)` | 否 | `''` |  | 型号(空串=不分型号) |
| `qty` | `int(11)` | 否 | `0` |  | 结存数量 |

索引：`idx_stock_tenant`(tenant_id) · `uk_stock`(warehouse_no,item_type,model) **UNIQUE**

### `inv_transfer` — 调拨单

实体 `InvTransfer` · 业务列 14 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `transfer_no` | `varchar(36)` | 否 | — | UQ | 业务键 TR* |
| `from_location` | `varchar(64)` | 否 | — | IX | 调出方(仓库号/站点号/点位号) |
| `to_location` | `varchar(64)` | 否 | — | IX | 调入方(仓库号/站点号/点位号) |
| `item_type` | `varchar(16)` | 否 | — |  | CABINET/POWERBANK |
| `powerbank_count` | `int(11)` | 否 | `0` |  | 调拨件数 |
| `status` | `varchar(16)` | 否 | `'DRAFT'` |  | DRAFT/IN_TRANSIT/DONE |
| `operator` | `varchar(64)` | 是 | — |  | 经办人(employee_no) |
| `from_name` | `varchar(128)` | 是 | — |  | 约定:名称 |
| `from_ref` | `varchar(36)` | 是 | — |  | 人工定型：调拨起点业务键，配合 from_type 解释（WAREHOUSE/SITE/LOCATION） |
| `from_type` | `varchar(32)` | 是 | — |  | 约定:枚举/短码 |
| `operator_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `to_name` | `varchar(128)` | 是 | — |  | 约定:名称 |
| `to_ref` | `varchar(36)` | 是 | — |  | 人工定型：调拨终点业务键，配合 to_type |
| `to_type` | `varchar(32)` | 是 | — |  | 约定:枚举/短码 |

索引：`idx_transfer_from`(from_location) · `idx_transfer_tenant_status`(tenant_id,status,created_at) · `idx_transfer_to`(to_location) · `uk_transfer_no`(transfer_no) **UNIQUE**

### `inv_transfer_item` — 调拨明细

实体 `InvTransferItem` · 业务列 4 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `transfer_no` | `varchar(36)` | 否 | — | IX | 所属调拨单 |
| `powerbank_no` | `varchar(36)` | 是 | — | IX | 充电宝(item_type=POWERBANK) |
| `cabinet_no` | `varchar(36)` | 是 | — | IX | 机柜(item_type=CABINET) |
| `checked` | `tinyint(1)` | 否 | `0` |  | 是否已签收核对 |

索引：`idx_transfer_item_cab`(cabinet_no) · `idx_transfer_item_no`(transfer_no) · `idx_transfer_item_pb`(powerbank_no)

### `inv_warehouse` — 仓库

实体 `InvWarehouse` · 业务列 4 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `warehouse_no` | `varchar(36)` | 否 | — | UQ | 业务键 |
| `region_id` | `varchar(36)` | 是 | — | IX | 区域 |
| `name` | `varchar(128)` | 否 | — |  | 仓库名称 |
| `address` | `varchar(256)` | 是 | — |  | — |

索引：`idx_wh_region`(region_id) · `idx_wh_tenant`(tenant_id) · `uk_warehouse_no`(warehouse_no) **UNIQUE**


---

## 场地与点位（`loc_*`）

### `loc_contract`

实体 `LocContract` · 业务列 13 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `contract_no` | `varchar(36)` | 否 | — | UQ | — |
| `venue_name` | `varchar(128)` | 是 | — |  | — |
| `site_name` | `varchar(128)` | 是 | — |  | — |
| `share_rate` | `decimal(5,4)` | 否 | `0.0000` |  | — |
| `entry_fee` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `start_at` | `varchar(40)` | 是 | — |  | — |
| `end_at` | `varchar(40)` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/EXPIRED † |
| `attach_url` | `varchar(512)` | 是 | — |  | 合同附件 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `settle_period` | `varchar(16)` | 是 | — |  | MONTH/QUARTER |
| `site_no` | `varchar(36)` | 是 | — |  | 站点 |
| `venue_no` | `varchar(36)` | 是 | — |  | 场地方 |

索引：`uk_contract_no`(contract_no) **UNIQUE**

### `loc_contract_attach` — 合同附件元数据（不含字节流）

实体 `LocContractAttach` · 业务列 6 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `attach_no` | `varchar(36)` | 否 | — | UQ | 业务键，前缀 ATT |
| `contract_no` | `varchar(36)` | 否 | — |  | — |
| `file_name` | `varchar(255)` | 否 | — |  | — |
| `size` | `bigint(20)` | 否 | `0` |  | 字节数 |
| `uploaded_by` | `varchar(64)` | 是 | — |  | — |
| `uploaded_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |

索引：`idx_contract_attach`(tenant_id,contract_no) · `uk_contract_attach_no`(attach_no) **UNIQUE**

### `loc_lead` — BD 拓展 CRM 商机

实体 `LocLead` · 业务列 8 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `lead_no` | `varchar(36)` | 否 | — | UQ | 业务键 LD* |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `venue_name` | `varchar(128)` | 否 | — |  | 意向场地方名称 |
| `contact` | `varchar(64)` | 是 | — |  | 联系方式(掩码;明文落 pii) |
| `stage` | `varchar(16)` | 否 | `'NEW'` |  | NEW/CONTACTED/NEGOTIATING/SIGNED/LOST |
| `owner` | `varchar(64)` | 是 | — | IX | 负责人(employee_no) |
| `expect_sites` | `int(11)` | 否 | `0` |  | 预计可铺站点数 |
| `next_follow_at` | `date` | 是 | — |  | 下次跟进日(仅日期) |

索引：`idx_lead_owner`(owner) · `idx_lead_tenant_stage`(tenant_id,stage,updated_at) · `uk_lead_no`(lead_no) **UNIQUE**

### `loc_lead_follow` — 线索跟进记录（append）

实体 `LocLeadFollow` · 业务列 8 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `follow_no` | `varchar(36)` | 否 | — | UQ | 业务键，前缀 LF |
| `lead_no` | `varchar(36)` | 否 | — |  | — |
| `channel` | `varchar(16)` | 否 | — |  | 联系渠道 CALL/VISIT/WECHAT/EMAIL |
| `from_stage` | `varchar(24)` | 是 | — |  | 来源阶段；首条建档跟进为 null |
| `to_stage` | `varchar(24)` | 否 | — |  | 跟进后阶段（可与来源相同=未推进） |
| `owner` | `varchar(64)` | 是 | — |  | 跟进人 |
| `content` | `varchar(500)` | 否 | — |  | 跟进内容 |
| `next_at` | `date` | 是 | — |  | 下次跟进计划日；空=未约 |

索引：`idx_lead_follow`(tenant_id,lead_no,created_at) · `uk_lead_follow_no`(follow_no) **UNIQUE**

### `loc_location`

实体 `LocLocation` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `location_no` | `varchar(36)` | 否 | — | UQ | 业务键(点位) † |
| `name` | `varchar(128)` | 否 | — |  | 点位名(如 L1 东门) † |
| `site_no` | `varchar(36)` | 是 | — |  | 归属站点 † |
| `site_name` | `varchar(128)` | 是 | — |  | — |
| `agent_no` | `varchar(36)` | 是 | — |  | 归属代理(冗余·随站点，数据范围锚点) |
| `spot_desc` | `varchar(256)` | 是 | — |  | 位置描述 † |
| `cabinet_count` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_loc_scope`(tenant_id,agent_no) · `uk_location_no`(location_no) **UNIQUE**

### `loc_site`

实体 `LocSite` · 业务列 16 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `site_no` | `varchar(36)` | 否 | — | UQ | 业务键 † |
| `name` | `varchar(128)` | 否 | — |  | 站点名称 † |
| `venue_name` | `varchar(128)` | 是 | — |  | — |
| `agent_no` | `varchar(36)` | 是 | — |  | 归属代理(空=平台直营) † |
| `region_id` | `varchar(64)` | 是 | — |  | 区域(数据权限) † |
| `address` | `varchar(256)` | 是 | — |  | — |
| `scene_type` | `varchar(32)` | 是 | — |  | 商场/机场/餐饮/地铁/写字楼 † |
| `point_count` | `int(11)` | 否 | `0` |  | — |
| `cabinet_count` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/PAUSED † |
| `lat` | `decimal(10,6)` | 是 | — |  | — |
| `lng` | `decimal(10,6)` | 是 | — |  | — |
| `name_ar` | `varchar(128)` | 是 | — |  | — |
| `venue_no` | `varchar(36)` | 是 | — |  | 归属场地方(逻辑引用) |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |
| `open_hours` | `varchar(64)` | 是 | — |  | 营业时段展示文本,如 09:00-22:00 |

索引：`idx_site_archived`(tenant_id,archived_at) · `uk_site_no`(site_no) **UNIQUE**

### `loc_site_lifecycle` — 门店生命周期

实体 `LocSiteLifecycle` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `site_no` | `varchar(36)` | 否 | — | UQ | 站点 |
| `stage` | `varchar(16)` | 否 | `'PROSPECTING'` |  | PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED |
| `stage_at` | `date` | 是 | — |  | 进入当前阶段日期(仅日期) |
| `owner` | `varchar(64)` | 是 | — |  | 负责人(employee_no) |
| `gmv_ltm` | `decimal(18,2)` | 否 | `0.00` |  | 近 12 月 GMV |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `owner_no` | `varchar(36)` | 是 | — |  | 既有DDL同名列×1 |

索引：`idx_site_lifecycle_stage`(tenant_id,stage) · `uk_site_lifecycle_site`(site_no) **UNIQUE**

### `loc_site_lifecycle_log` — 门店生命周期流转留痕(append)

实体 `LocSiteLifecycleLog` · 业务列 5 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `site_no` | `varchar(36)` | 否 | — | IX | — |
| `from_stage` | `varchar(16)` | 是 | — |  | PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED(空=首次) |
| `to_stage` | `varchar(16)` | 否 | — |  | PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED |
| `operator` | `varchar(64)` | 是 | — |  | 操作人(employee_no) |
| `reason` | `varchar(512)` | 是 | — |  | — |

索引：`idx_site_lc_log_site`(site_no,created_at)

### `loc_venue`

实体 `LocVenue` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `venue_no` | `varchar(36)` | 否 | — | UQ | 业务键 † |
| `name` | `varchar(128)` | 否 | — |  | 场地方名称 † |
| `contact` | `varchar(64)` | 是 | — |  | 联系方式(明文脱敏;敏感落 pii) † |
| `industry` | `varchar(32)` | 是 | — |  | 行业 † |
| `location_count` | `int(11)` | 否 | `0` |  | — |
| `name_ar` | `varchar(128)` | 是 | — |  | 名称(阿语) |
| `region_id` | `varchar(36)` | 是 | — |  | 区域 |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/PAUSED |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`uk_venue_no`(venue_no) **UNIQUE**

### `loc_venue_onboarding` — 门店自助进件

实体 `LocVenueOnboarding` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `onboarding_no` | `varchar(36)` | 否 | — | UQ | 业务键 OB* |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `venue_name` | `varchar(128)` | 否 | — |  | 申请门店名称 |
| `contact` | `varchar(64)` | 是 | — |  | 联系方式(掩码;明文落 pii) |
| `industry` | `varchar(32)` | 是 | — |  | — |
| `attach` | `longtext` | 是 | — |  | 营业执照等附件 |
| `requested_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | 提交时刻 |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/APPROVED/REJECTED |
| `review_at` | `datetime(3)` | 是 | — |  | 审核时刻(空=尚未审核) |
| `review_by` | `varchar(64)` | 是 | — |  | 审核人(employee_no) |
| `review_note` | `varchar(512)` | 是 | — |  | 审核意见/驳回原因 |
| `venue_no` | `varchar(36)` | 是 | — | IX | 通过后回填 loc_venue.venue_no |

索引：`idx_onboarding_tenant_status`(tenant_id,status,requested_at) · `idx_onboarding_venue`(venue_no) · `uk_onboarding_no`(onboarding_no) **UNIQUE**


---

## 主数据（`md_*`）

### `md_bank` — 银行字典(全局)

实体 `MdBank` · 业务列 10 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `bank_code` | `varchar(32)` | 否 | — | UQ | 自然键(前缀 BK 或行业行号) |
| `bank_name` | `varchar(128)` | 否 | — |  | 银行名称(默认语) |
| `bank_name_en` | `varchar(128)` | 是 | — |  | 银行名称(英语) |
| `bank_name_ar` | `varchar(128)` | 是 | — |  | 银行名称(阿语) |
| `country` | `char(2)` | 否 | `'AE'` | IX | 所属国家 ISO alpha-2 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | 默认结算币种 |
| `swift_prefix` | `varchar(16)` | 是 | — |  | SWIFT/BIC 前缀(收款账户校验) |
| `iban_length` | `int(11)` | 是 | — |  | IBAN 位数(收款账户校验) |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/DISABLED |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_bank_country`(country,status) · `uk_bank_code`(bank_code) **UNIQUE**

### `md_device_type` — 设备类型注册表（全局，无 tenant_id）

**无实体** · 业务列 12 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `type_code` | `varchar(32)` | 否 | — | UQ | POWERBANK / EV_PILE / LOCKER |
| `name_zh` | `varchar(64)` | 否 | — |  | — |
| `name_en` | `varchar(64)` | 是 | — |  | — |
| `name_ar` | `varchar(64)` | 是 | — |  | — |
| `has_movable_item` | `tinyint(1)` | 否 | `0` |  | 槽位里是否有平台资产：充电宝1，桩/柜0 |
| `item_table` | `varchar(64)` | 是 | — |  | 物品台账表名；has_movable_item=0 时为 NULL |
| `order_ext_table` | `varchar(64)` | 否 | — |  | 订单扩展表名 |
| `default_metering` | `varchar(16)` | 否 | `'MINUTE'` |  | MINUTE/KWH/COUNT |
| `reservation_target` | `varchar(16)` | 否 | `'CABINET'` |  | 预约粒度 CABINET/SLOT |
| `supports_return_elsewhere` | `tinyint(1)` | 否 | `0` |  | 是否支持异地归还 |
| `command_family` | `varchar(32)` | 否 | — |  | 驱动指令族 |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | — |

索引：`uk_device_type_code`(type_code) **UNIQUE**

### `md_market_country` — 多国家市场(全局)

实体 `MdMarketCountry` · 业务列 9 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `country_code` | `char(2)` | 否 | — | UQ | 自然键 ISO alpha-2(AE/SA/EG...) |
| `name` | `varchar(64)` | 否 | — |  | 国家名(默认语) |
| `name_en` | `varchar(64)` | 是 | — |  | 国家名(英语) |
| `name_ar` | `varchar(64)` | 是 | — |  | 国家名(阿语) |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | 本地币种(ISO 4217) |
| `timezone` | `varchar(64)` | 是 | — |  | IANA 时区,如 Asia/Dubai |
| `compliance` | `varchar(128)` | 是 | — |  | 合规口径标签,如 PDPL/CBUAE |
| `city_count` | `int(11)` | 否 | `0` |  | 已开城市数(维护列;明细以 md_region 为准) |
| `status` | `varchar(16)` | 否 | `'PLANNED'` | IX | LIVE/PILOT/PLANNED |

索引：`idx_market_status`(status) · `uk_market_country`(country_code) **UNIQUE**

### `md_problem` — C端报障问题字典

实体 `MdProblem` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `problem_no` | `varchar(36)` | 否 | — | UQ | 业务键(前缀 ISS,勿与充电宝 PB 混用) |
| `category` | `varchar(16)` | 否 | — |  | RENT/RETURN/BILLING/DEVICE/ACCOUNT/OTHER |
| `title` | `varchar(256)` | 否 | — |  | 问题标题(默认语) |
| `title_en` | `varchar(256)` | 是 | — |  | 问题标题(英语) |
| `title_ar` | `varchar(256)` | 是 | — |  | 问题标题(阿语) |
| `answer` | `text` | 是 | — |  | 标准答复(默认语) |
| `answer_en` | `text` | 是 | — |  | 标准答复(英语) |
| `answer_ar` | `text` | 是 | — |  | 标准答复(阿语) |
| `suggested_action` | `varchar(16)` | 否 | `'SELF_SERVICE'` |  | SELF_SERVICE/TO_WORKORDER/TO_REFUND/TO_CS |
| `sort_no` | `int(11)` | 否 | `0` |  | 展示排序 |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/DISABLED |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_problem_cat`(tenant_id,category,status,sort_no) · `uk_problem_no`(problem_no) **UNIQUE**

### `md_region` — 地区库

实体 `MdRegion` · 业务列 7 · 标准列缺 `tenant_id`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `region_id` | `varchar(36)` | 否 | — | UQ | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `name_ar` | `varchar(64)` | 是 | — |  | — |
| `parent_id` | `varchar(36)` | 是 | — | IX | — |
| `level` | `int(11)` | 否 | `1` |  | — |
| `city_count` | `int(11)` | 是 | — |  | 既有DDL同名列×1 |
| `name_en` | `varchar(64)` | 是 | — |  | 既有DDL同名列×3 |

索引：`idx_region_parent`(parent_id) · `uk_region_id`(region_id) **UNIQUE**


---

## 营销（`mkt_*`）

### `mkt_campaign` — 营销活动

实体 `MktCampaign` · 业务列 8 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `campaign_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `kind` | `varchar(24)` | 否 | — |  | NEW_USER/RECHARGE_GIFT/COUPON_PUSH/REFERRAL/FESTIVAL |
| `rule` | `longtext` | 是 | — |  | 活动规则(门槛/奖励/频次) |
| `status` | `varchar(16)` | 否 | `'DRAFT'` |  | DRAFT/RUNNING/ENDED |
| `start_at` | `datetime(3)` | 是 | — |  | — |
| `end_at` | `datetime(3)` | 是 | — |  | — |

索引：`idx_mcmp_tenant_status`(tenant_id,status,start_at) · `uk_mkt_campaign_no`(campaign_no) **UNIQUE**

### `mkt_notice` — 公告(三语)

实体 `MktNotice` · 业务列 15 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `notice_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `title` | `varchar(128)` | 否 | — |  | 中文 |
| `title_en` | `varchar(128)` | 是 | — |  | — |
| `title_ar` | `varchar(128)` | 是 | — |  | — |
| `content` | `text` | 是 | — |  | 中文 |
| `content_en` | `text` | 是 | — |  | — |
| `content_ar` | `text` | 是 | — |  | — |
| `type` | `varchar(16)` | 否 | `'SYSTEM'` |  | SYSTEM/PROMO/MAINTENANCE |
| `pinned` | `tinyint(1)` | 否 | `0` |  | 置顶 |
| `start_at` | `datetime(3)` | 是 | — |  | 生效起 |
| `end_at` | `datetime(3)` | 是 | — |  | 生效止 |
| `status` | `varchar(16)` | 否 | `'DRAFT'` |  | DRAFT/PUBLISHED/OFFLINE |
| `published_by` | `varchar(36)` | 是 | — |  | employee_no |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_ntc_publish`(tenant_id,status,pinned,start_at) · `uk_notice_no`(notice_no) **UNIQUE**

### `mkt_push` — 推送触达

实体 `MktPush` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `push_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `title` | `varchar(128)` | 否 | — |  | — |
| `content` | `text` | 是 | — |  | — |
| `channel` | `varchar(16)` | 否 | `'APP_PUSH'` |  | APP_PUSH/SUBSCRIBE |
| `audience` | `longtext` | 是 | — |  | 受众条件(分群 SEG/标签/全量) |
| `sent_count` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'DRAFT'` |  | DRAFT/SENT |
| `sent_at` | `datetime(3)` | 是 | — |  | 空=尚未发送 |

索引：`idx_mpush_tenant_status`(tenant_id,status,sent_at) · `uk_push_no`(push_no) **UNIQUE**

### `mkt_referral` — 邀请裂变

实体 `MktReferral` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `invite_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `inviter_no` | `varchar(36)` | 否 | — |  | 邀请人 c_user_no |
| `invitee_no` | `varchar(36)` | 是 | — |  | 被邀请人 c_user_no(注册后回填) |
| `reward` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/REWARDED |

索引：`idx_mref_inviter`(tenant_id,inviter_no,created_at) · `uk_invite_no`(invite_no) **UNIQUE** · `uk_referral_invitee`(tenant_id,invitee_no) **UNIQUE**

### `mkt_referral_rule` — 邀请裂变规则（mkt_referral 是邀请记录，本表是规则）

实体 `MktReferralRule` · 业务列 14 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `rule_no` | `varchar(36)` | 否 | — | UQ | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `inviter_reward` | `decimal(12,2)` | 否 | `0.00` |  | 邀请人奖励金额 |
| `invitee_reward` | `decimal(12,2)` | 否 | `0.00` |  | 被邀请人奖励金额 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `reward_type` | `varchar(16)` | 否 | `'BONUS'` |  | BONUS 赠金/COUPON 券 |
| `coupon_tpl_no` | `varchar(36)` | 是 | — |  | reward_type=COUPON 时关联券模板 |
| `cap_per_user` | `int(11)` | 否 | `0` |  | 单人可得奖励上限次数,0=不限 |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/DISABLED |
| `start_at` | `datetime(3)` | 是 | — |  | — |
| `end_at` | `datetime(3)` | 是 | — |  | — |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间,NULL=在用 |
| `reward_to` | `varchar(8)` | 否 | `'BOTH'` |  | 奖励对象：INVITER/INVITEE/BOTH |
| `trigger_event` | `varchar(16)` | 否 | `'FIRST_ORDER'` |  | 触发事件（前端 ReferralTrigger） |

索引：`uk_referral_rule_no`(rule_no) **UNIQUE**


---

## 通知（`notify_*`）

### `notify_blacklist` — 触达拉黑(全渠道)

实体 `NotifyBlacklist` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `block_no` | `varchar(36)` | 否 | — | UQ | 业务键(前缀 NBL,勿与用户黑名单 BL 混用) |
| `target` | `varchar(128)` | 否 | — |  | 被拉黑接收方(脱敏存储) |
| `channel` | `varchar(16)` | 否 | `'ALL'` |  | SMS/EMAIL/PUSH/WHATSAPP/ALL(全渠道) |
| `reason` | `varchar(24)` | 否 | — |  | USER_OPT_OUT/HARD_BOUNCE/ABUSE/MANUAL |
| `blocked_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | 拉黑时刻 |
| `blocked_by` | `varchar(64)` | 是 | — |  | 操作人(employee_no 或 SYSTEM) |
| `expire_at` | `datetime(3)` | 是 | — |  | 到期自动解除(空=永久) |
| `released_at` | `datetime(3)` | 是 | — |  | 解除时刻(空=尚未解除) |
| `released_by` | `varchar(64)` | 是 | — |  | 解除人 |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/RELEASED |

索引：`idx_nbl_status`(tenant_id,status,expire_at) · `idx_nbl_target`(tenant_id,target,channel,status) · `uk_notify_blacklist_no`(block_no) **UNIQUE**

### `notify_log` — 通知发送记录(append,按 created_at 月分区,保留 7 年)

实体 `NotifyLog` · 业务列 12 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `log_no` | `varchar(36)` | 否 | — | IX | 业务键(NTC 域外独立取号) |
| `channel` | `varchar(16)` | 否 | — |  | SMS/EMAIL/PUSH/WHATSAPP |
| `template_no` | `varchar(36)` | 是 | — | IX | 通知模板(逻辑引用 notify_template) |
| `target` | `varchar(128)` | 否 | — | IX | 接收方(**存储即脱敏**,如 +9715****1234;明文不入库) |
| `scene` | `varchar(64)` | 是 | — |  | 业务场景码 |
| `sent_at` | `datetime(3)` | 是 | — |  | 实际发出时刻(空=尚未发生) |
| `status` | `varchar(16)` | 否 | `'SENT'` |  | SENT/FAILED |
| `fail_reason` | `varchar(256)` | 是 | — |  | 失败原因(渠道返回) |
| `cost` | `decimal(18,4)` | 否 | `0.0000` |  | 单条触达成本(4 位小数,单价级金额) |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | 成本币种 |
| `idempotency_key` | `varchar(64)` | 是 | — |  | 幂等键；重发/试发必带，历史 seed 为 null |
| `resend_of` | `varchar(36)` | 是 | — | IX | 由哪条记录重发而来；null=原始发送 |

索引：`idx_notify_log_target`(target) · `idx_notify_log_tenant_time`(tenant_id,created_at) · `idx_notify_log_tpl`(template_no) · `idx_notify_resend_of`(resend_of) · `uk_notify_idem`(tenant_id,idempotency_key) **UNIQUE** · `uk_notify_log_no`(log_no,created_at) **UNIQUE**

### `notify_template` — 通知模板(多语)

实体 `NotifyTemplate` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `template_no` | `varchar(36)` | 否 | — |  | — |
| `channel` | `varchar(16)` | 否 | — |  | PUSH/SMS/SUBSCRIBE/INAPP |
| `code` | `varchar(64)` | 否 | — |  | — |
| `content` | `text` | 是 | — |  | — |
| `content_ar` | `text` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `lang` | `varchar(8)` | 是 | — |  | 既有DDL同名列×2 |
| `name` | `varchar(64)` | 是 | — |  | 既有DDL同名列×10 |
| `params` | `longtext` | 是 | — |  | db-design 标 JSON |
| `scene` | `longtext` | 是 | — |  | db-design 标 JSON |

索引：`uk_notify_tpl`(tenant_id,channel,code) **UNIQUE**


---

## 订单（`ord_*`）

### `ord_charge_ext` — 订单扩展-充电桩

**无实体** · 业务列 10 · 标准列缺 `id`/`tenant_id`/`created_by`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `order_no` | `varchar(36)` | 否 | — | PK | — |
| `connector_no` | `varchar(36)` | 是 | — |  | 枪/连接器 |
| `energy_kwh` | `decimal(10,3)` | 是 | — |  | 充电量；三位小数是行业口径 |
| `start_soc` | `tinyint(4)` | 是 | — |  | — |
| `end_soc` | `tinyint(4)` | 是 | — |  | — |
| `peak_power_kw` | `decimal(8,2)` | 是 | — |  | — |
| `meter_start` | `decimal(12,3)` | 是 | — |  | 起始表底数 |
| `meter_stop` | `decimal(12,3)` | 是 | — |  | 终止表底数 —— **结算只认它**，过程累计仅展示 |
| `stop_reason` | `varchar(16)` | 是 | — |  | FULL/USER/FAULT/BALANCE/REMOTE |
| `idle_min` | `int(11)` | 是 | — |  | 充满后占位分钟 |

### `ord_complaint` — 投诉订单

实体 `OrdComplaint` · 业务列 14 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `complaint_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `order_no` | `varchar(36)` | 否 | — | IX | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `issue_type` | `varchar(24)` | 否 | — |  | BILLING_DISPUTE/NOT_EJECTED/NOT_RETURNED/DEVICE_FAULT/OTHER |
| `description` | `varchar(1024)` | 是 | — |  | — |
| `screenshot_url` | `varchar(512)` | 是 | — |  | 截图证据 |
| `submitted_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/PROCESSING/RESOLVED/REJECTED |
| `handler_name` | `varchar(64)` | 是 | — |  | 处理人快照名(不回溯) |
| `handled_at` | `datetime(3)` | 是 | — |  | 空=尚未处理 |
| `resolution` | `varchar(16)` | 是 | — |  | REFUND/COMPENSATE/REJECT/EXPLAINED |
| `resolution_note` | `varchar(512)` | 是 | — |  | — |
| `wo_no` | `varchar(36)` | 是 | — | IX | 转工单(幂等由 wo_order.source_ref UK 保证) |

索引：`idx_ocpl_order`(order_no) · `idx_ocpl_tenant_status`(tenant_id,status,created_at) · `idx_ocpl_user`(tenant_id,c_user_no,created_at) · `idx_ocpl_wo`(wo_no) · `uk_complaint_no`(complaint_no) **UNIQUE**

### `ord_deposit` — 押金与欠费

实体 `OrdDeposit` · 业务列 16 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `deposit_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `order_no` | `varchar(36)` | 否 | — | UQ | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | 押金额 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `status` | `varchar(16)` | 否 | `'HELD'` |  | HELD/RELEASED/BOUGHT_OUT/ARREARS |
| `arrears_amount` | `decimal(18,2)` | 否 | `0.00` |  | 欠费额(status=ARREARS) |
| `released_at` | `datetime(3)` | 是 | — |  | 空=尚未释放 |
| `buyout_amount` | `decimal(18,2)` | 是 | — |  | 买断金额；null=未买断 |
| `buyout_at` | `datetime(3)` | 是 | — |  | 买断时间 |
| `dun_count` | `int(11)` | 否 | `0` |  | 催缴次数（累加，不覆盖） |
| `last_dun_at` | `datetime(3)` | 是 | — |  | 最近一次催缴时间 |
| `last_dun_channel` | `varchar(16)` | 是 | — |  | 最近催缴渠道 SMS/PUSH/CALL |
| `operator_name` | `varchar(64)` | 是 | — |  | 最近操作人（服务端取登录态，不信入参） |
| `note` | `varchar(255)` | 是 | — |  | 备注 |

索引：`idx_odep_tenant_status`(tenant_id,status,created_at) · `idx_odep_user`(tenant_id,c_user_no,created_at) · `uk_deposit_no`(deposit_no) **UNIQUE** · `uk_deposit_order`(order_no) **UNIQUE**

### `ord_event_log` — 订单状态流水(append)

实体 `OrdEventLog` · 业务列 6 · 标准列缺 `tenant_id`/`updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `order_no` | `varchar(36)` | 否 | — | IX | — |
| `from_status` | `varchar(16)` | 是 | — |  | — |
| `to_status` | `varchar(16)` | 否 | — |  | — |
| `event` | `varchar(32)` | 否 | — |  | — |
| `data` | `longtext` | 是 | — |  | — |
| `operator` | `varchar(64)` | 是 | — |  | 既有DDL同名列×3 |

索引：`idx_oel_order`(order_no,created_at)

### `ord_exception` — 异常订单

实体 `OrdException` · 业务列 15 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `exception_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `order_no` | `varchar(36)` | 否 | — | IX | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `cabinet_no` | `varchar(36)` | 是 | — |  | — |
| `type` | `varchar(24)` | 否 | — |  | NOT_EJECTED/NOT_RETURNED/OVERTIME_BUYOUT/DOUBLE_CHARGE |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | 涉及金额 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `status` | `varchar(16)` | 否 | `'OPEN'` |  | OPEN/HANDLED |
| `handled_by` | `varchar(36)` | 是 | — |  | employee_no |
| `handled_at` | `datetime(3)` | 是 | — |  | 空=尚未处理 |
| `handle_action` | `varchar(16)` | 是 | — |  | 处置动作：REFUND/COMPENSATE/WORK_ORDER/IGNORE |
| `handle_result` | `varchar(255)` | 是 | — |  | 处置结果说明 |
| `refund_no` | `varchar(36)` | 是 | — |  | 产出的退款单号(handle_action=REFUND) |
| `work_order_no` | `varchar(36)` | 是 | — |  | 产出的工单号(handle_action=WORK_ORDER) |

索引：`idx_oexc_order`(order_no) · `idx_oexc_tenant_status`(tenant_id,status,created_at) · `idx_oexc_user`(tenant_id,c_user_no,created_at) · `uk_exception_no`(exception_no) **UNIQUE**

### `ord_intervention` — 订单人工干预留痕（append）

实体 `OrdIntervention` · 业务列 9 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `intervention_no` | `varchar(36)` | 否 | — | UQ | 业务键，前缀 ITV |
| `order_no` | `varchar(36)` | 否 | — |  | — |
| `action` | `varchar(24)` | 否 | — |  | eject/force_return/waive/compensate/refund_apply |
| `operator` | `varchar(64)` | 是 | — |  | 干预人 |
| `reason` | `varchar(500)` | 否 | — |  | — |
| `amount` | `decimal(18,4)` | 是 | — |  | — |
| `currency` | `varchar(8)` | 是 | — |  | — |
| `before_status` | `varchar(24)` | 否 | — |  | — |
| `after_status` | `varchar(24)` | 否 | — |  | 与 before 相同 = 只留痕不改状态 |

索引：`idx_intervention_action`(tenant_id,action,created_at) · `idx_intervention_order`(tenant_id,order_no,created_at) · `uk_intervention_no`(intervention_no) **UNIQUE**

### `ord_locker_ext` — 订单扩展-储物柜

**无实体** · 业务列 5 · 标准列缺 `id`/`tenant_id`/`created_by`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `order_no` | `varchar(36)` | 否 | — | PK | — |
| `cell_index` | `int(11)` | 是 | — |  | 格口 |
| `access_code` | `varchar(16)` | 是 | — |  | 取件码 |
| `overtime_min` | `int(11)` | 是 | — |  | 超期占用分钟 |
| `item_note` | `varchar(128)` | 是 | — |  | 存物备注 |

### `ord_order`

实体 `OrdOrder` · 业务列 32 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `order_no` | `varchar(36)` | 否 | — | UQ | — |
| `c_user_no` | `varchar(36)` | 是 | — | IX | — |
| `cabinet_no` | `varchar(36)` | 是 | — |  | — |
| `location_no` | `varchar(36)` | 是 | — |  | 借出点位 |
| `site_no` | `varchar(36)` | 是 | — |  | 借出站点(冗余·数据范围锚点) |
| `agent_no` | `varchar(36)` | 是 | — |  | 归属代理(冗余·数据范围锚点) |
| `return_cabinet_no` | `varchar(36)` | 是 | — |  | — |
| `powerbank_no` | `varchar(36)` | 是 | — |  | — |
| `location_name` | `varchar(128)` | 是 | — |  | — |
| `status` | `varchar(24)` | 否 | `'CREATED'` | IX | — |
| `rent_start_at` | `varchar(32)` | 是 | — |  | — |
| `rent_end_at` | `varchar(32)` | 是 | — |  | — |
| `duration_min` | `int(11)` | 是 | — |  | — |
| `fee_amount` | `decimal(12,2)` | 否 | `0.00` |  | — |
| `deposit_amount` | `decimal(12,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `price_plan_no` | `varchar(36)` | 是 | — |  | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `coupon_no` | `varchar(36)` | 是 | — |  | 结算所用券(逻辑引用 usr_coupon) |
| `buyout` | `tinyint(1)` | 否 | `0` |  | 是否买断转持有 |
| `free_reason` | `varchar(24)` | 是 | — |  | 免费单原因(空=正常单)：INTERNAL_TEST/VIP/BD_DEMO/MERCHANT_SELF，源 usr_free_whitelist.reason |
| `waived_amount` | `decimal(18,2)` | 否 | `0.00` |  | 减免金额(免费单统计口径) |
| `device_type` | `varchar(32)` | 是 | — |  | 设备类型，一等维度 |
| `sub_status` | `varchar(24)` | 是 | — |  | 类型子状态（DISPENSING/PREPARING/IDLE）；**资金侧不读** |
| `slot_index` | `int(11)` | 是 | — |  | 槽位：宝=仓位 桩=枪 柜=格口 |
| `started_at` | `datetime(3)` | 是 | — |  | 业务开始（充电宝=借出时刻） |
| `ended_at` | `datetime(3)` | 是 | — |  | 业务结束 |
| `amount` | `decimal(18,2)` | 是 | — |  | 应收合计（原 fee_amount 的共性化） |
| `auth_no` | `varchar(36)` | 是 | — |  | 预授权单（逻辑引用 pay_auth） |
| `source_channel` | `varchar(16)` | 是 | — |  | APP/MINI/H5/INTERCONNECT |
| `partner_no` | `varchar(36)` | 是 | — |  | 互联互通伙伴（intc_partner） |
| `price_snapshot` | `longtext` | 是 | — |  | 计价方案的**展开结构**快照，非 plan_no 引用 —— 改价不影响在途单 |

索引：`idx_ord_free`(tenant_id,free_reason,created_at) · `idx_ord_owner`(c_user_no) · `idx_ord_scope`(tenant_id,agent_no,status,created_at) · `idx_ord_status`(status) · `idx_ord_type`(tenant_id,device_type,status,created_at) · `uk_ord_order_no`(order_no) **UNIQUE**

### `ord_refund` — 退款审批单(业务侧·聚合根,幂等)

实体 `OrdRefund` · 业务列 16 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `refund_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `order_no` | `varchar(36)` | 否 | — | IX | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `reason` | `varchar(256)` | 是 | — |  | — |
| `applicant_name` | `varchar(64)` | 是 | — |  | 申请人快照名(不回溯) |
| `applied_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/APPROVED/REJECTED/EXECUTED/FAILED |
| `auditor_name` | `varchar(64)` | 是 | — |  | 审批人快照名 |
| `audited_at` | `datetime(3)` | 是 | — |  | 空=尚未审批 |
| `reject_reason` | `varchar(256)` | 是 | — |  | 驳回必填(资金审批合规) |
| `idempotency_key` | `varchar(64)` | 否 | — | UQ | 幂等键(前端生成) db-design §1.6 防重复退款 |
| `psp_txn_no` | `varchar(64)` | 是 | — |  | PSP 交易号(冗余便于对账) |
| `pay_refund_no` | `varchar(36)` | 是 | — | IX | → pay_refund.refund_no(渠道执行凭证) |

索引：`idx_orfd_order`(order_no) · `idx_orfd_pay_refund`(pay_refund_no) · `idx_orfd_tenant_status`(tenant_id,status,created_at) · `idx_orfd_user`(tenant_id,c_user_no,created_at) · `uk_ord_refund_idem`(idempotency_key) **UNIQUE** · `uk_ord_refund_no`(refund_no) **UNIQUE**

### `ord_rent_ext` — 订单扩展-充电宝

实体 `OrdRentExt` · 业务列 6 · 标准列缺 `id`/`tenant_id`/`created_by`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `order_no` | `varchar(36)` | 否 | — | PK | 主表业务键，1:1 |
| `powerbank_no` | `varchar(36)` | 是 | — | IX | 借走的充电宝 |
| `return_cabinet_no` | `varchar(36)` | 是 | — |  | 归还柜机（可异地） |
| `return_slot_index` | `int(11)` | 是 | — |  | — |
| `buyout` | `tinyint(1)` | 是 | — |  | 是否买断（丢失） |
| `duration_min` | `int(11)` | 是 | — |  | 时长（分钟） |

索引：`idx_rentext_pb`(powerbank_no)

### `ord_reservation` — 预约订单(借/还)

实体 `OrdReservation` · 业务列 13 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `reservation_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `type` | `varchar(16)` | 否 | — |  | BORROW/RETURN |
| `site_no` | `varchar(36)` | 是 | — | IX | — |
| `site_name` | `varchar(128)` | 是 | — |  | 站点名快照(不回溯) |
| `cabinet_no` | `varchar(36)` | 是 | — |  | — |
| `reserved_from` | `datetime(3)` | 否 | — |  | 预约窗口起 |
| `reserved_to` | `datetime(3)` | 否 | — |  | 预约窗口止 |
| `hold_fee` | `decimal(18,2)` | 否 | `0.00` |  | 占位费 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/FULFILLED/EXPIRED/CANCELLED |
| `order_no` | `varchar(36)` | 是 | — |  | 履约后回填 ord_rent.order_no |

索引：`idx_orsv_site`(site_no,reserved_from) · `idx_orsv_tenant_status`(tenant_id,status,created_at) · `idx_orsv_user`(tenant_id,c_user_no,created_at) · `uk_reservation_no`(reservation_no) **UNIQUE**


---

## 支付（`pay_*`）

### `pay_auth` — 免押编排(实际冻结在 nearpay)

实体 `PayAuth` · 业务列 8 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `auth_no` | `varchar(36)` | 否 | — | UQ | — |
| `order_no` | `varchar(36)` | 否 | — | IX | — |
| `freeze_amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `captured_amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `status` | `varchar(16)` | 否 | `'FROZEN'` |  | FROZEN/CAPTURED/RELEASED |
| `nearpay_auth_no` | `varchar(64)` | 是 | — |  | — |
| `c_user_no` | `varchar(36)` | 是 | — |  | 既有DDL同名列×18 |
| `expire_at` | `datetime(3)` | 是 | — |  | 既有DDL同名列×2 |

索引：`idx_auth_order`(order_no) · `uk_auth_no`(auth_no) **UNIQUE**

### `pay_channel` — 支付渠道配置

实体 `PayChannel` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `channel_code` | `varchar(32)` | 否 | — | UQ | 自然键(前缀 CH 或渠道自然码,如 STRIPE/TABBY) |
| `channel_name` | `varchar(128)` | 否 | — |  | 渠道名称(默认语) |
| `channel_name_en` | `varchar(128)` | 是 | — |  | 渠道名称(英语) |
| `channel_name_ar` | `varchar(128)` | 是 | — |  | 渠道名称(阿语) |
| `mode` | `varchar(16)` | 否 | `'DELEGATED'` |  | DELEGATED(委托 nearpay 执行)/DIRECT(平台直连) |
| `api_base` | `varchar(256)` | 是 | — |  | 渠道 API 基址 |
| `merchant_id` | `varchar(128)` | 是 | — |  | 商户号(非密钥,可入库) |
| `api_key_masked` | `varchar(64)` | 是 | — |  | 密钥掩码,如 sk_live_****3f9a;**明文落 KMS/vault,不入库** |
| `api_secret_masked` | `varchar(64)` | 是 | — |  | 签名密钥掩码;**明文落 KMS/vault,不入库** |
| `status` | `varchar(16)` | 否 | `'DISABLED'` |  | ENABLED/DISABLED |

索引：`idx_pay_channel_tenant`(tenant_id,status) · `uk_pay_channel_code`(channel_code) **UNIQUE**

### `pay_channel_scope` — 支付渠道适用范围

实体 `PayChannelScope` · 业务列 3 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `channel_code` | `varchar(32)` | 否 | — | IX | 渠道(逻辑引用 pay_channel.channel_code) |
| `scope_type` | `varchar(16)` | 否 | — | IX | COUNTRY/CURRENCY/CAPABILITY |
| `scope_value` | `varchar(64)` | 否 | — |  | COUNTRY=ISO alpha-2;CURRENCY=ISO 4217;CAPABILITY=PAY/REFUND/AUTH/CAPTURE/PAYOUT |

索引：`idx_pay_scope_lookup`(scope_type,scope_value) · `uk_pay_channel_scope`(channel_code,scope_type,scope_value) **UNIQUE**

### `pay_event_log` — nearpay 结果事件留痕(幂等)

实体 `PayEventLog` · 业务列 6 · 标准列缺 `tenant_id`/`updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `source` | `varchar(16)` | 否 | `'NEARPAY'` |  | — |
| `ref_no` | `varchar(64)` | 否 | — | IX | nearpay 单号 |
| `event_type` | `varchar(32)` | 否 | — |  | — |
| `raw` | `longtext` | 是 | — |  | — |
| `processed` | `tinyint(1)` | 否 | `0` |  | — |
| `received_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |

索引：`uk_pay_event`(ref_no,event_type) **UNIQUE**

### `pay_order` — 支付引用(映射 nearpay)

实体 `PayOrder` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `pay_no` | `varchar(36)` | 否 | — | UQ | — |
| `order_no` | `varchar(36)` | 是 | — | IX | 关联租借订单；RECHARGE/MEMBERSHIP 场景为空 |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `type` | `varchar(16)` | 否 | — |  | DEPOSIT/RENT/BUYOUT/RECHARGE/MEMBERSHIP |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `channel_code` | `varchar(32)` | 是 | — |  | 支付渠道(逻辑引用 pay_channel) |
| `status` | `varchar(16)` | 否 | `'INIT'` |  | INIT/PAYING/PAID/FAILED/CLOSED |
| `nearpay_txn_no` | `varchar(64)` | 是 | — | IX | nearpay 交易引用 |
| `paid_at` | `datetime(3)` | 是 | — |  | — |

索引：`idx_pay_nearpay`(nearpay_txn_no) · `idx_pay_order`(order_no) · `idx_pay_owner`(tenant_id,c_user_no,created_at) · `uk_pay_no`(pay_no) **UNIQUE**

### `pay_refund` — 退款引用

实体 `PayRefund` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `refund_no` | `varchar(36)` | 否 | — | UQ | — |
| `pay_no` | `varchar(36)` | 否 | — | IX | — |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `reason` | `varchar(256)` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'INIT'` |  | INIT/SUCCESS/FAILED |
| `nearpay_refund_no` | `varchar(64)` | 是 | — |  | — |
| `ord_refund_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |

索引：`idx_refund_pay`(pay_no) · `uk_refund_no`(refund_no) **UNIQUE**


---

## 计价（`price_*`）

### `price_ladder` — 计价阶梯

实体 `PriceLadder` · 业务列 6 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `ladder_no` | `varchar(36)` | 否 | — | UQ | 业务键，前缀 PL |
| `item_no` | `varchar(36)` | 否 | — | IX | 所属费用项 |
| `seq` | `int(11)` | 否 | — |  | 阶梯序，从 1 起 |
| `from_qty` | `decimal(12,3)` | 否 | `0.000` |  | 区间下界（含） |
| `to_qty` | `decimal(12,3)` | 是 | — |  | 区间上界（不含）；NULL=无上限 |
| `unit_price` | `decimal(18,4)` | 否 | — |  | 该区间单价。四位小数 —— 电价常见 0.6543/度 |

索引：`idx_pl_item`(item_no,from_qty) · `uk_ladder_item_seq`(item_no,seq) **UNIQUE** · `uk_price_ladder_no`(ladder_no) **UNIQUE**

### `price_plan` — 计费模板

实体 `PricePlan` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `plan_no` | `varchar(36)` | 否 | — | UQ | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `free_minutes` | `int(11)` | 否 | `0` |  | — |
| `unit_minutes` | `int(11)` | 否 | `30` |  | — |
| `unit_price` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `cap_daily` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `cap_total` | `decimal(18,2)` | 否 | `0.00` |  | 总封顶=买断价 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `scope` | `varchar(32)` | 否 | `'DEFAULT'` |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `device_type` | `varchar(32)` | 是 | — |  | 适用设备类型；NULL=通用 |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_plan_tenant`(tenant_id) · `uk_plan_no`(plan_no) **UNIQUE**

### `price_plan_item` — 计价费用项

实体 `PricePlanItem` · 业务列 11 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `item_no` | `varchar(36)` | 否 | — | UQ | 业务键，前缀 PI |
| `plan_no` | `varchar(36)` | 否 | — | IX | 所属方案 |
| `item_type` | `varchar(16)` | 否 | — |  | TIME_FEE/ENERGY_FEE/SERVICE_FEE/IDLE_FEE/HOLD_FEE |
| `metering` | `varchar(16)` | 否 | `'MINUTE'` |  | MINUTE/KWH/COUNT |
| `free_qty` | `decimal(12,3)` | 否 | `0.000` |  | 免费额度（分钟/度/次）；IDLE_FEE 用它表达宽限期 |
| `unit_qty` | `decimal(12,3)` | 否 | `1.000` |  | 计费步长，如每 30 分钟、每 1 度 |
| `cap_daily` | `decimal(18,2)` | 是 | — |  | 单项日封顶 |
| `cap_total` | `decimal(18,2)` | 是 | — |  | 单项总封顶 |
| `rounding` | `varchar(16)` | 否 | `'CEIL'` |  | CEIL/FLOOR/HALF_UP —— **必须显式**，四舍五入口径是对账争议高发区 |
| `sort` | `int(11)` | 否 | `0` |  | 出账顺序（账单展示与发票行序） |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | — |

索引：`idx_pi_plan`(plan_no,sort) · `uk_price_item_no`(item_no) **UNIQUE**

### `price_plan_scope` — 计费模板适用范围(多值拆表)

实体 `PricePlanScope` · 业务列 3 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `plan_no` | `varchar(36)` | 否 | — | IX | — |
| `scope_type` | `varchar(16)` | 否 | — | IX | SITE/SCENE/ALL |
| `scope_ref` | `varchar(64)` | 否 | `'*'` |  | site_no / scene_type / *(ALL) |

索引：`idx_pscope_ref`(scope_type,scope_ref) · `uk_plan_scope`(plan_no,scope_type,scope_ref) **UNIQUE**

### `price_rule` — 差异化定价(按站点/场景)

实体 `PriceRule` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `rule_no` | `varchar(36)` | 否 | — | UQ | — |
| `plan_no` | `varchar(36)` | 否 | — |  | 差异化指向的模板 |
| `site_no` | `varchar(36)` | 是 | — | IX | 按站点取价 |
| `scene_type` | `varchar(32)` | 是 | — |  | 按场景取价 |
| `priority` | `int(11)` | 否 | `1` |  | — |
| `currency` | `varchar(8)` | 是 | — |  | 既有DDL同名列×31 |
| `day_cap` | `decimal(18,2)` | 是 | — |  | 约定:金额 |
| `dimension` | `varchar(16)` | 是 | — |  | 既有DDL同名列×2 |
| `free_mins` | `int(11)` | 是 | — |  | 约定:整数 |
| `location_name` | `varchar(128)` | 是 | — |  | 约定:名称 |
| `match_ref` | `varchar(36)` | 是 | — |  | 人工定型：匹配对象业务键，配合 dimension（SCENE/LOCATION/SITE） |
| `unit_price` | `decimal(18,2)` | 是 | — |  | 既有DDL同名列×1 |

索引：`idx_prule_site`(site_no) · `uk_price_rule_no`(rule_no) **UNIQUE**

### `price_schedule` — 活动/时段价

实体 `PriceSchedule` · 业务列 8 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `rule_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `period` | `varchar(128)` | 否 | — |  | 时段/节假日表达式,如 18:00-23:00 或 HOLIDAY:EID |
| `multiplier` | `decimal(6,4)` | 否 | `1.0000` |  | 倍率(可 >1,非 0..1 比率) |
| `active` | `tinyint(1)` | 否 | `1` |  | — |
| `item_no` | `varchar(36)` | 是 | — |  | 作用的费用项；NULL=作用于方案的全部项 |
| `mode` | `varchar(16)` | 是 | — |  | MULTIPLY 系数 / REPLACE 替换价 |

索引：`idx_psched_tenant`(tenant_id,active) · `uk_price_sched_no`(rule_no) **UNIQUE**


---

## 分润（`share_*`）

### `share_record` — 逐单分润记录

实体 `ShareRecord` · 业务列 14 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `record_no` | `varchar(36)` | 否 | — | UQ | — |
| `order_no` | `varchar(36)` | 否 | — | IX | — |
| `payee_type` | `varchar(16)` | 否 | — | IX | VENUE/AGENT/PLATFORM |
| `payee_no` | `varchar(36)` | 是 | — |  | — |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `mode` | `varchar(16)` | 否 | `'LEDGER'` |  | — |
| `settle_no` | `varchar(36)` | 是 | — |  | 归属结算单 |
| `dimension` | `varchar(16)` | 是 | — |  | 既有DDL同名列×2 |
| `payee_name` | `varchar(128)` | 是 | — |  | 既有DDL同名列×1 |
| `rate` | `decimal(5,4)` | 是 | — |  | 既有DDL同名列×2 |
| `status` | `varchar(16)` | 是 | — |  | 既有DDL同名列×72 |
| `period` | `varchar(7)` | 是 | — | IX | 归属结算周期 YYYY-MM（写入定格，不再由 created_at 现推） |
| `gross_amount` | `decimal(18,2)` | 是 | — |  | 分润基数(GMV快照)；写入定格，不再由 amount/rate 反推 |

索引：`idx_share_record_period`(period,payee_no) · `idx_srec_order`(order_no) · `idx_srec_payee`(payee_type,payee_no) · `uk_share_record_no`(record_no) **UNIQUE**

### `share_rule` — 分润规则

实体 `ShareRule` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `rule_no` | `varchar(36)` | 否 | — | UQ | — |
| `dimension` | `varchar(16)` | 否 | — | IX | VENUE/AGENT |
| `payee_no` | `varchar(36)` | 是 | — |  | 分成方(venue_no/agent_no) |
| `mode` | `varchar(16)` | 否 | `'LEDGER'` |  | CHANNEL_SPLIT/LEDGER |
| `rate` | `decimal(5,4)` | 否 | `0.0000` |  | — |
| `priority` | `int(11)` | 否 | `1` |  | — |
| `currency` | `varchar(8)` | 是 | — |  | 既有DDL同名列×31 |
| `formula` | `longtext` | 是 | — |  | db-design 标 JSON |
| `payee_name` | `varchar(128)` | 是 | — |  | 既有DDL同名列×1 |

索引：`idx_srule_payee`(dimension,payee_no) · `uk_share_rule_no`(rule_no) **UNIQUE**


---

## 结算（`stl_*`）

### `stl_settlement` — 结算单

实体 `StlSettlement` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `settle_no` | `varchar(36)` | 否 | — | UQ | — |
| `payee_type` | `varchar(16)` | 否 | — | IX | VENUE/AGENT |
| `payee_no` | `varchar(36)` | 否 | — |  | — |
| `period` | `varchar(16)` | 否 | — |  | 2026-07 |
| `total_amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `status` | `varchar(16)` | 否 | `'GEN'` |  | GEN/CONFIRMED/PAID |
| `payee_name` | `varchar(128)` | 是 | — |  | 既有DDL同名列×1 |
| `confirmed_by` | `varchar(36)` | 是 | — |  | 确认人 |
| `confirmed_at` | `datetime(3)` | 是 | — |  | 确认时间 |

索引：`idx_stl_payee`(payee_type,payee_no) · `uk_settle_no`(settle_no) **UNIQUE** · `uk_settle_period`(payee_type,payee_no,period) **UNIQUE**

### `stl_settlement_detail` — 结算明细

实体 `StlSettlementDetail` · 业务列 5 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `settle_no` | `varchar(36)` | 否 | — | IX | — |
| `ref_type` | `varchar(16)` | 否 | — | IX | ORDER/SHARE |
| `ref_no` | `varchar(36)` | 否 | — |  | order_no / share_record.record_no |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |

索引：`idx_stld_ref`(ref_type,ref_no) · `uk_stl_detail`(settle_no,ref_type,ref_no) **UNIQUE**

### `stl_withdrawal` — 提现

实体 `StlWithdrawal` · 业务列 18 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `withdraw_no` | `varchar(36)` | 否 | — | UQ | — |
| `payee_type` | `varchar(16)` | 否 | — | IX | — |
| `payee_no` | `varchar(36)` | 否 | — |  | — |
| `payee_name` | `varchar(128)` | 是 | — |  | 收款方名快照(冗余) |
| `account_no` | `varchar(36)` | 是 | — |  | 账户(逻辑引用 acct_account) |
| `bank_code` | `varchar(32)` | 是 | — |  | 收款银行(逻辑引用 md_bank) |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `fee` | `decimal(18,2)` | 否 | `0.00` |  | 提现手续费(取 sys_biz_rule WITHDRAW) |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `status` | `varchar(16)` | 否 | `'APPLY'` |  | APPLY/AUDIT/PAYING/PAID/FAILED |
| `nearpay_payout_no` | `varchar(64)` | 是 | — |  | nearpay 打款单 |
| `applied_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `applicant_no` | `varchar(36)` | 是 | — |  | 申请人(代理账号/员工) |
| `auditor_no` | `varchar(36)` | 是 | — |  | 审批人(服务端回填,不信前端) |
| `auditor_name` | `varchar(64)` | 是 | — |  | 审批人名快照 |
| `audited_at` | `datetime(3)` | 是 | — |  | 审批时间(NULL=未审) |
| `reject_reason` | `varchar(256)` | 是 | — |  | 驳回原因(驳回时必填) |
| `paid_at` | `datetime(3)` | 是 | — |  | 打款到账时间 |

索引：`idx_wd_audit`(tenant_id,status,applied_at) · `idx_wd_payee`(payee_type,payee_no) · `uk_withdraw_no`(withdraw_no) **UNIQUE**


---

## 系统配置（`sys_*`）

### `sys_app_version` — C端应用版本

实体 `SysAppVersion` · 业务列 13 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `version_id` | `varchar(64)` | 否 | — | UQ | 自然键,复合 PLATFORM-versionNo,如 IOS-1.4.2 |
| `version_no` | `varchar(32)` | 否 | — |  | 语义版本号,如 1.4.2 |
| `platform` | `varchar(16)` | 否 | — | IX | IOS/ANDROID/H5 |
| `build_no` | `int(11)` | 是 | — |  | 构建号(单调递增) |
| `release_note` | `text` | 是 | — |  | 更新说明(默认语) |
| `release_note_en` | `text` | 是 | — |  | 更新说明(英语) |
| `release_note_ar` | `text` | 是 | — |  | 更新说明(阿语) |
| `force_update` | `tinyint(1)` | 否 | `0` |  | 是否强制更新 |
| `min_supported` | `varchar(32)` | 是 | — |  | 最低可用版本(低于此版强更) |
| `rollout_percent` | `decimal(5,2)` | 否 | `0.00` |  | 灰度百分比 0..100(百分数口径) |
| `download_url` | `varchar(512)` | 是 | — |  | 下载地址/商店链接 |
| `status` | `varchar(16)` | 否 | `'DRAFT'` |  | DRAFT/RELEASED/ROLLBACK |
| `released_at` | `datetime(3)` | 是 | — |  | 发布时刻(空=尚未发生) |

索引：`idx_app_version_status`(tenant_id,platform,status) · `uk_app_platform_version`(platform,version_no) **UNIQUE** · `uk_app_version_id`(version_id) **UNIQUE**

### `sys_biz_rule` — 业务规则(三分区单例)

实体 `SysBizRule` · 业务列 3 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `category` | `varchar(16)` | 否 | — |  | WITHDRAW/RESERVATION/BILLING(每租户每类唯一一行) |
| `rule` | `longtext` | 是 | — |  | 规则体;WITHDRAW: feeRate/feeCap/minAmount/settleDays/dailyLimit/needApproval |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | 规则内金额阈值的币种 |

索引：`uk_biz_rule`(tenant_id,category) **UNIQUE**

### `sys_login_setting` — 登录设置(按国家)

实体 `SysLoginSetting` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `country` | `varchar(8)` | 否 | — |  | 自然键 ISO alpha-2;* = 默认行 |
| `country_name` | `varchar(64)` | 是 | — |  | 国家名(展示快照) |
| `otp_enabled` | `tinyint(1)` | 否 | `1` |  | 短信验证码登录开关 |
| `password_enabled` | `tinyint(1)` | 否 | `0` |  | 密码登录开关 |
| `apple_enabled` | `tinyint(1)` | 否 | `0` |  | Apple 登录开关 |
| `google_enabled` | `tinyint(1)` | 否 | `0` |  | Google 登录开关 |
| `otp_expire_sec` | `int(11)` | 否 | `300` |  | 验证码有效期(秒) |
| `otp_daily_limit` | `int(11)` | 否 | `10` |  | 单号码每日验证码上限 |
| `force_real_name` | `tinyint(1)` | 否 | `0` |  | 是否强制实名(合规要求国家) |

索引：`uk_login_setting_country`(tenant_id,country) **UNIQUE**

### `sys_outbox` — 事务性发件箱

实体 `SysOutbox` · 业务列 10 · 标准列缺 `version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `event_no` | `varchar(36)` | 否 | — | UQ | 事件编号，前缀 EVT |
| `aggregate_type` | `varchar(64)` | 否 | — | IX | 聚合类型，如 AgtAssignment / OrdOrder |
| `aggregate_id` | `varchar(64)` | 否 | — |  | 聚合业务键，如 ASG0001 / ORD0001 |
| `event_type` | `varchar(64)` | 否 | — |  | 事件类型，如 ASSET_ASSIGNED / ORDER_SETTLED |
| `payload` | `longtext` | 否 | — |  | 事件载荷。**必须自带消费方所需全部字段** —— 让消费方回查等于把同步调用藏进事件 |
| `status` | `varchar(16)` | 否 | `'PENDING'` | IX | PENDING/SENT/FAILED |
| `retry_count` | `int(11)` | 否 | `0` |  | — |
| `next_retry_at` | `datetime(3)` | 是 | — |  | 下次重试时间；PENDING 且 <= now 才会被取走 |
| `last_error` | `varchar(512)` | 是 | — |  | — |
| `sent_at` | `datetime(3)` | 是 | — |  | — |

索引：`idx_outbox_aggregate`(aggregate_type,aggregate_id) · `idx_outbox_poll`(status,next_retry_at,id) · `uk_outbox_event_no`(event_no) **UNIQUE**

### `sys_param` — 系统参数

实体 `SysParam` · 业务列 4 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `param_key` | `varchar(64)` | 否 | — |  | 自然键,如 order.max_duration_min |
| `label` | `varchar(128)` | 是 | — |  | 参数中文名(运营端展示) |
| `value` | `varchar(512)` | 是 | — |  | 参数值(字符串存储,由消费方解析) |
| `group_name` | `varchar(64)` | 是 | — |  | 分组(运营端分栏) |

索引：`idx_sys_param_group`(tenant_id,group_name) · `uk_sys_param`(tenant_id,param_key) **UNIQUE**

### `sys_tax_setting` — 税率与发票设置

实体 `SysTaxSetting` · 业务列 8 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `country` | `varchar(8)` | 否 | — |  | 自然键 ISO alpha-2;* = 默认行 |
| `country_name` | `varchar(64)` | 是 | — |  | 国家名(展示快照) |
| `tax_name` | `varchar(64)` | 是 | — |  | 税种名,如 VAT |
| `rate_percent` | `decimal(5,2)` | 否 | `0.00` |  | 税率 0..100(百分数口径,非 0..1) |
| `trn` | `varchar(64)` | 是 | — |  | 税号 Tax Registration Number |
| `invoice_title` | `varchar(256)` | 是 | — |  | 开票抬头(运营主体名) |
| `included_in_price` | `tinyint(1)` | 否 | `1` |  | 价格是否含税(1=含税价,0=价外税) |
| `effective_from` | `date` | 是 | — |  | 生效日(仅日期语义) |

索引：`uk_tax_setting_country`(tenant_id,country) **UNIQUE**

### `sys_token`

实体 `SysToken` · 业务列 7 · 标准列缺 `id`/`tenant_id`/`created_by`/`updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `token` | `varchar(64)` | 否 | — | PK | — |
| `realm` | `varchar(16)` | 否 | — |  | — |
| `subject_no` | `varchar(64)` | 是 | — |  | — |
| `role_nos` | `varchar(256)` | 是 | — |  | — |
| `perm_stamp` | `bigint(20)` | 否 | `0` |  | — |
| `payload` | `text` | 否 | — |  | — |
| `expire_at` | `datetime(3)` | 是 | — | IX | — |

索引：`idx_token_expire`(expire_at)


---

## 用户（`usr_*`）

### `usr_blacklist` — 用户黑名单(前缀 BL,勿与 NBL 触达拉黑混淆)

实体 `UsrBlacklist` · 业务列 9 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `blacklist_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `reason` | `varchar(256)` | 是 | — |  | — |
| `blacklisted_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `blacklisted_by` | `varchar(36)` | 是 | — |  | employee_no |
| `released_at` | `datetime(3)` | 是 | — |  | 空=尚未解除 |
| `released_by` | `varchar(36)` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/RELEASED |

索引：`idx_ubl_tenant_status`(tenant_id,status,created_at) · `idx_ubl_user`(tenant_id,c_user_no,status) · `uk_blacklist_no`(blacklist_no) **UNIQUE**

### `usr_consent` — 同意与撤回留痕(append,月分区,WORM)

实体 `UsrConsent` · 业务列 6 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `agreement_code` | `varchar(64)` | 否 | — | IX | 协议标识 TOS/PRIVACY/MARKETING/... |
| `agreement_version` | `varchar(32)` | 否 | — |  | 协议版本(规格写作 version,避让 BaseEntity 乐观锁列名) |
| `action` | `varchar(8)` | 否 | — |  | GRANT/REVOKE |
| `lang` | `varchar(8)` | 否 | `'en'` |  | zh/en/ar |
| `ip` | `varchar(64)` | 是 | — |  | — |

索引：`idx_ucst_agreement`(agreement_code,agreement_version) · `idx_ucst_user`(tenant_id,c_user_no,created_at)

### `usr_coupon` — 用户券

实体 `UsrCoupon` · 业务列 6 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `coupon_no` | `varchar(36)` | 否 | — | UQ | — |
| `c_user_no` | `varchar(36)` | 否 | — | IX | — |
| `tpl_no` | `varchar(36)` | 否 | — |  | — |
| `status` | `varchar(16)` | 否 | `'UNUSED'` |  | UNUSED/USED/EXPIRED |
| `used_order_no` | `varchar(36)` | 是 | — |  | — |
| `expire_at` | `datetime(3)` | 是 | — |  | 既有DDL同名列×2 |

索引：`idx_coupon_user`(c_user_no) · `uk_coupon_no`(coupon_no) **UNIQUE**

### `usr_coupon_issue` — 券发放记录（append）

实体 `UsrCouponIssue` · 业务列 7 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `issue_no` | `varchar(36)` | 否 | — | UQ | 业务键，前缀 CIS |
| `coupon_no` | `varchar(36)` | 否 | — |  | 券模板号（coupon_tpl.tpl_no） |
| `coupon_name` | `varchar(64)` | 是 | — |  | — |
| `target_type` | `varchar(24)` | 否 | — |  | 人群类型 ALL/SEGMENT/USER_LIST |
| `target_desc` | `varchar(255)` | 是 | — |  | 人群口径的可读描述（含规模） |
| `quantity` | `int(11)` | 否 | `0` |  | 本次发放张数 |
| `operator_name` | `varchar(64)` | 是 | — |  | — |

索引：`idx_coupon_issue_tpl`(tenant_id,coupon_no,created_at) · `uk_coupon_issue_no`(issue_no) **UNIQUE**

### `usr_credit` — 信用/黑名单

实体 `UsrCredit` · 业务列 7 · 标准列缺 `version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `risk_no` | `varchar(36)` | 是 | — | UQ | 业务键 RK* |
| `c_user_no` | `varchar(36)` | 否 | — | UQ | — |
| `score` | `int(11)` | 否 | `600` |  | — |
| `risk_level` | `varchar(8)` | 是 | — |  | HIGH/MEDIUM/LOW |
| `blacklisted` | `tinyint(1)` | 否 | `0` |  | — |
| `reason` | `varchar(256)` | 是 | — |  | — |
| `flagged_at` | `datetime(3)` | 是 | — |  | 标记时间 |

索引：`idx_credit_level`(tenant_id,risk_level) · `uk_credit_risk_no`(risk_no) **UNIQUE** · `uk_credit_user`(c_user_no) **UNIQUE**

### `usr_credit_change` — 信用分变更流水（append）

实体 `UsrCreditChange` · 业务列 7 · 标准列缺 `updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `change_no` | `varchar(36)` | 否 | — | UQ | 业务键，前缀 CSC |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `score_before` | `int(11)` | 否 | — |  | — |
| `score_after` | `int(11)` | 否 | — |  | — |
| `delta` | `int(11)` | 否 | — |  | after - before；正=加分，负=减分 |
| `reason` | `varchar(255)` | 否 | — |  | **必填** —— 没有原因的调分等于没有记录 |
| `operator_name` | `varchar(64)` | 是 | — |  | 服务端取登录态，不信入参 |

索引：`idx_credit_change_user`(tenant_id,c_user_no,created_at) · `uk_credit_change_no`(change_no) **UNIQUE**

### `usr_favorite` — 收藏门店

实体 `UsrFavorite` · 业务列 2 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `c_user_no` | `varchar(36)` | 否 | — | IX | — |
| `site_no` | `varchar(36)` | 否 | — | IX | — |

索引：`idx_ufav_site`(site_no) · `idx_ufav_user`(tenant_id,c_user_no,created_at) · `uk_favorite`(c_user_no,site_no) **UNIQUE**

### `usr_free_whitelist` — 免费用户白名单

实体 `UsrFreeWhitelist` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `whitelist_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `reason` | `varchar(24)` | 否 | — |  | INTERNAL_TEST/VIP/BD_DEMO/MERCHANT_SELF |
| `quota_type` | `varchar(16)` | 否 | `'UNLIMITED'` |  | UNLIMITED/TIMES/AMOUNT |
| `quota_value` | `decimal(18,2)` | 否 | `0.00` |  | TIMES=次数,AMOUNT=金额(按 currency) |
| `used_value` | `decimal(18,2)` | 否 | `0.00` |  | 已用量,口径同 quota_value |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | quota_type=AMOUNT 时生效 |
| `valid_from` | `date` | 是 | — |  | — |
| `valid_to` | `date` | 是 | — |  | — |
| `granted_by` | `varchar(36)` | 是 | — |  | employee_no |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | ACTIVE/EXPIRED/REVOKED |

索引：`idx_ufw_tenant_status`(tenant_id,status,valid_to) · `idx_ufw_user`(tenant_id,c_user_no,status) · `uk_whitelist_no`(whitelist_no) **UNIQUE**

### `usr_identity`

实体 `UsrIdentity` · 业务列 5 · 标准列缺 `version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `c_user_no` | `varchar(36)` | 否 | — | IX | — |
| `provider` | `varchar(16)` | 否 | — |  | WECHAT_MP/WECHAT_OA/APPLE/GOOGLE/PHONE † |
| `provider_uid` | `varchar(128)` | 否 | — |  | openid / apple·google sub / hash(phone) † |
| `union_key` | `varchar(128)` | 否 | — |  | 微信 unionid，或 provider:uid（跨渠道归并键） † |
| `bound_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |

索引：`idx_identity_union`(tenant_id,union_key) · `idx_identity_user`(c_user_no) · `uk_identity_provider_uid`(tenant_id,provider,provider_uid) **UNIQUE**

### `usr_invoice` — C端开票申请

实体 `UsrInvoice` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `invoice_no` | `varchar(36)` | 否 | — | UQ | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `title_no` | `varchar(36)` | 否 | — |  | → usr_invoice_title.title_no |
| `title` | `varchar(128)` | 是 | — |  | 抬头快照(不回溯) |
| `amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `status` | `varchar(16)` | 否 | `'APPLIED'` |  | APPLIED/ISSUED/REJECTED |
| `file_url` | `varchar(512)` | 是 | — |  | — |
| `applied_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `issued_at` | `datetime(3)` | 是 | — |  | 空=尚未开具 |

索引：`idx_uiv_status`(tenant_id,status,applied_at) · `idx_uiv_user`(tenant_id,c_user_no,created_at) · `uk_usr_invoice_no`(invoice_no) **UNIQUE**

### `usr_invoice_title` — C端发票抬头

实体 `UsrInvoiceTitle` · 业务列 6 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `title_no` | `varchar(36)` | 否 | — | UQ | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `type` | `varchar(16)` | 否 | `'PERSONAL'` |  | PERSONAL/COMPANY |
| `title` | `varchar(128)` | 否 | — |  | — |
| `vat_trn` | `varchar(32)` | 是 | — |  | 税号 TRN(COMPANY 必填) |
| `is_default` | `tinyint(1)` | 否 | `0` |  | — |

索引：`idx_uivt_user`(tenant_id,c_user_no,is_default) · `uk_invoice_title_no`(title_no) **UNIQUE**

### `usr_logoff` — 注销申请(PDPL 冷静期)

实体 `UsrLogoff` · 业务列 5 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `requested_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `cooling_until` | `datetime(3)` | 否 | — |  | 冷静期截止,期内可撤销 |
| `status` | `varchar(16)` | 否 | `'PENDING'` | IX | PENDING/CANCELLED/DONE |
| `purged_at` | `datetime(3)` | 是 | — |  | 空=尚未清除 |

索引：`idx_ulof_cooling`(status,cooling_until) · `idx_ulof_user`(tenant_id,c_user_no,requested_at)

### `usr_membership` — 会员/次卡

实体 `UsrMembership` · 业务列 9 · 标准列缺 `version`/`deleted`（追加表/全局表，符合预期）

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `mbr_no` | `varchar(36)` | 否 | — | UQ | — |
| `c_user_no` | `varchar(36)` | 否 | — | IX | — |
| `plan_no` | `varchar(36)` | 否 | — |  | — |
| `start_at` | `date` | 是 | — |  | — |
| `end_at` | `date` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `auto_renew` | `tinyint(1)` | 是 | — |  | 既有DDL同名列×1 |
| `level` | `varchar(16)` | 是 | — |  | 既有DDL同名列×2 |
| `points` | `int(11)` | 是 | — |  | 约定:整数 |

索引：`idx_mbr_user`(c_user_no) · `uk_mbr_no`(mbr_no) **UNIQUE**

### `usr_message` — 站内消息中心

实体 `UsrMessage` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `message_no` | `varchar(36)` | 否 | — | UQ | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `type` | `varchar(24)` | 否 | — |  | ORDER/WALLET/COUPON/SYSTEM/ACTIVITY/CS |
| `title` | `varchar(128)` | 否 | — |  | — |
| `body` | `text` | 是 | — |  | — |
| `is_read` | `tinyint(1)` | 否 | `0` |  | 规格字段名 read,MySQL 保留字改名 is_read |
| `read_at` | `datetime(3)` | 是 | — |  | 空=尚未读 |

索引：`idx_umsg_unread`(tenant_id,c_user_no,is_read) · `idx_umsg_user`(tenant_id,c_user_no,created_at) · `uk_message_no`(message_no) **UNIQUE**

### `usr_notify_pref` — C端通知偏好

实体 `UsrNotifyPref` · 业务列 6 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `c_user_no` | `varchar(36)` | 否 | — | IX | — |
| `category` | `varchar(24)` | 否 | — |  | ORDER/WALLET/COUPON/SYSTEM/ACTIVITY/CS |
| `enabled` | `tinyint(1)` | 否 | `1` |  | — |
| `quiet_start` | `char(5)` | 是 | — |  | 静默起 HH:mm |
| `quiet_end` | `char(5)` | 是 | — |  | 静默止 HH:mm |
| `lang` | `varchar(8)` | 否 | `'en'` |  | zh/en/ar |

索引：`idx_unpf_user`(tenant_id,c_user_no) · `uk_notify_pref`(c_user_no,category) **UNIQUE**

### `usr_push_token` — Push token 注册

实体 `UsrPushToken` · 业务列 5 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `platform` | `varchar(16)` | 否 | — | IX | APNS/FCM/UNIPUSH |
| `token` | `varchar(256)` | 否 | — |  | — |
| `device_id` | `varchar(128)` | 是 | — |  | — |
| `active` | `tinyint(1)` | 否 | `1` |  | — |

索引：`idx_uptk_user`(tenant_id,c_user_no,active) · `uk_push_token`(platform,token) **UNIQUE**

### `usr_recharge_order` — 充值订单

实体 `UsrRechargeOrder` · 业务列 13 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `recharge_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `c_user_no` | `varchar(36)` | 否 | — |  | — |
| `nickname` | `varchar(64)` | 是 | — |  | 昵称快照(不回溯) |
| `package_no` | `varchar(36)` | 是 | — |  | 空=自定义金额充值 |
| `pay_amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `gift_amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `credit_amount` | `decimal(18,2)` | 否 | `0.00` |  | 实际入账=pay+gift |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `channel_code` | `varchar(32)` | 是 | — |  | → pay_channel.channel_code |
| `status` | `varchar(16)` | 否 | `'PENDING'` |  | PENDING/PAID/FAILED/REFUNDED |
| `psp_txn_no` | `varchar(64)` | 是 | — | IX | PSP 交易号(db-design 写作 psg_txn_no,按 ord_refund 口径统一为 psp_) |
| `paid_at` | `datetime(3)` | 是 | — |  | 空=尚未支付 |

索引：`idx_rord_psp`(psp_txn_no) · `idx_rord_tenant_status`(tenant_id,status,created_at) · `idx_rord_user`(tenant_id,c_user_no,created_at) · `uk_recharge_no`(recharge_no) **UNIQUE**

### `usr_recharge_pkg` — 充值套餐

实体 `UsrRechargePkg` · 业务列 10 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `package_no` | `varchar(36)` | 否 | — | UQ | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `name` | `varchar(64)` | 否 | — |  | — |
| `pay_amount` | `decimal(18,2)` | 否 | `0.00` |  | 实付 |
| `gift_amount` | `decimal(18,2)` | 否 | `0.00` |  | 赠送 |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `valid_days` | `int(11)` | 是 | — |  | 到账余额有效期(天),空=永久 |
| `sort_no` | `int(11)` | 否 | `0` |  | — |
| `status` | `varchar(16)` | 否 | `'ENABLED'` |  | ENABLED/DISABLED |
| `archived_at` | `datetime(3)` | 是 | — |  | 归档时间；null=在用 |

索引：`idx_rpkg_tenant_status`(tenant_id,status,sort_no) · `uk_recharge_pkg_no`(package_no) **UNIQUE**

### `usr_recharge_pkg_market` — 充值套餐适用市场(多值拆表)

实体 `UsrRechargePkgMarket` · 业务列 2 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `package_no` | `varchar(36)` | 否 | — | IX | — |
| `country_code` | `char(2)` | 否 | — | IX | ISO alpha-2 → md_market_country.country_code |

索引：`idx_rpkgm_country`(country_code) · `uk_rpkg_market`(package_no,country_code) **UNIQUE**

### `usr_user`

实体 `UsrUser` · 业务列 7 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `c_user_no` | `varchar(36)` | 否 | — | UQ | — |
| `openid` | `varchar(64)` | 是 | — |  | — |
| `unionid` | `varchar(64)` | 是 | — | IX | — |
| `nickname` | `varchar(64)` | 是 | — |  | — |
| `avatar` | `varchar(256)` | 是 | — |  | — |
| `credit_score` | `int(11)` | 否 | `600` |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |

索引：`idx_user_openid`(tenant_id,openid) · `idx_user_unionid`(unionid) · `uk_c_user_no`(c_user_no) **UNIQUE**

### `usr_wallet` — 钱包

实体 `UsrWallet` · 业务列 7 · 标准列缺 `created_at`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `wallet_no` | `varchar(36)` | 否 | — | UQ | — |
| `c_user_no` | `varchar(36)` | 否 | — | UQ | — |
| `balance` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `gift_balance` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `deposit_amount` | `decimal(18,2)` | 否 | `0.00` |  | — |
| `currency` | `varchar(8)` | 否 | `'AED'` |  | — |
| `frozen_amount` | `decimal(18,2)` | 是 | — |  | 约定:金额 |

索引：`uk_wallet_no`(wallet_no) **UNIQUE** · `uk_wallet_user`(c_user_no) **UNIQUE**

### `usr_wallet_txn` — 钱包流水(append)

实体 `UsrWalletTxn` · 业务列 10 · 标准列缺 `tenant_id`/`updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `wallet_no` | `varchar(36)` | 否 | — | IX | — |
| `direction` | `varchar(8)` | 否 | — |  | IN/OUT |
| `amount` | `decimal(18,2)` | 否 | — |  | — |
| `biz_type` | `varchar(24)` | 是 | — |  | — |
| `biz_no` | `varchar(36)` | 是 | — |  | — |
| `c_user_no` | `varchar(36)` | 是 | — |  | 既有DDL同名列×26 |
| `currency` | `varchar(8)` | 是 | — |  | 既有DDL同名列×31 |
| `title` | `varchar(128)` | 是 | — |  | 既有DDL同名列×5 |
| `txn_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `type` | `varchar(16)` | 是 | — |  | 既有DDL同名列×7 |

索引：`idx_wtxn_wallet`(wallet_no,created_at)


---

## 工单（`wo_*`）

### `wo_dispatch` — 派单记录

实体 `WoDispatch` · 业务列 6 · 标准列缺 `tenant_id`/`updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `wo_no` | `varchar(36)` | 否 | — | IX | — |
| `assignee_id` | `varchar(36)` | 否 | — |  | — |
| `strategy` | `varchar(16)` | 否 | `'MANUAL'` |  | NEAREST/LOAD/MANUAL/GRAB |
| `action` | `varchar(24)` | 是 | — |  | — |
| `dispatched_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `assignee_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |

索引：`idx_wdisp_wo`(wo_no)

### `wo_handle` — 现场处理

实体 `WoHandle` · 业务列 8 · 标准列缺 `tenant_id`/`updated_at`/`updated_by`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `wo_no` | `varchar(36)` | 否 | — | IX | — |
| `assignee_id` | `varchar(36)` | 否 | — |  | — |
| `photos` | `longtext` | 是 | — |  | — |
| `note` | `varchar(512)` | 是 | — |  | — |
| `part_changed` | `tinyint(1)` | 否 | `0` |  | — |
| `device_changed` | `tinyint(1)` | 否 | `0` |  | — |
| `handled_at` | `datetime(3)` | 否 | `current_timestamp(3)` |  | — |
| `assignee_no` | `longtext` | 是 | — |  | db-design 标 JSON |

索引：`idx_whandle_wo`(wo_no)

### `wo_inspection_plan` — 巡检计划

实体 `WoInspectionPlan` · 业务列 12 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `plan_no` | `varchar(36)` | 否 | — | UQ | — |
| `route` | `longtext` | 是 | — |  | 站点/点位路线 |
| `cron` | `varchar(64)` | 是 | — |  | — |
| `assignee_id` | `varchar(36)` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'ACTIVE'` |  | — |
| `active` | `tinyint(1)` | 是 | — |  | 既有DDL同名列×3 |
| `assignee_no` | `varchar(36)` | 是 | — |  | 约定:业务键 |
| `frequency` | `varchar(32)` | 是 | — |  | 约定:枚举/短码 |
| `next_at` | `datetime(3)` | 是 | — |  | 约定:时间列(实体用String映射) |
| `last_run_at` | `datetime(3)` | 是 | — |  | 上次执行时间 |
| `last_run_period` | `varchar(16)` | 是 | — |  | 上次执行覆盖的周期标识 |
| `last_run_wo_nos` | `varchar(512)` | 是 | — |  | 上次执行产出的工单号,逗号分隔 |

索引：`uk_insp_plan_no`(plan_no) **UNIQUE**

### `wo_order`

实体 `WoOrder` · 业务列 26 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `wo_no` | `varchar(36)` | 否 | — | UQ | — |
| `type` | `varchar(16)` | 是 | — |  | FAULT/REFILL/INSPECT/INSTALL/REMOVE/COMPLAINT/CLEAN † |
| `source` | `varchar(16)` | 是 | — |  | ALERT/USER/VENUE/MANUAL † |
| `priority` | `varchar(8)` | 是 | — |  | — |
| `cabinet_no` | `varchar(36)` | 是 | — |  | — |
| `location_no` | `varchar(36)` | 是 | — |  | 点位 |
| `site_no` | `varchar(36)` | 是 | — |  | 站点(冗余·数据范围锚点) |
| `agent_no` | `varchar(36)` | 是 | — |  | 归属代理(冗余·数据范围锚点) |
| `location_name` | `varchar(128)` | 是 | — |  | — |
| `status` | `varchar(16)` | 否 | `'CREATED'` |  | CREATED/DISPATCHED/ACCEPTED/PROCESSING/DONE/AUDITED/CLOSED † |
| `assignee_name` | `varchar(64)` | 是 | — |  | — |
| `sla_due_at` | `varchar(40)` | 是 | — |  | — |
| `description` | `varchar(512)` | 是 | — |  | — |
| `wo_created_at` | `varchar(40)` | 是 | — |  | — |
| `close_reason` | `varchar(16)` | 是 | — |  | 关单原因(status=CLOSED 时必填)：RESOLVED 正常完结/INVALID 误报/DUPLICATE 重复单/WITHDRAWN 撤单 |
| `closed_at` | `datetime(3)` | 是 | — |  | 关单时间 |
| `audited_by` | `varchar(36)` | 是 | — |  | 验收人(AUDITED 时回填,服务端写) |
| `audited_at` | `datetime(3)` | 是 | — |  | 验收时间 |
| `assignee_id` | `varchar(36)` | 是 | — |  | — |
| `region_id` | `varchar(36)` | 是 | — |  | — |
| `reject_reason` | `varchar(256)` | 是 | — |  | 最近一次退回原因(reject 驳回退回 / rework 验收退回返工,均必填) |
| `reject_count` | `int(11)` | 否 | `0` |  | 累计退回次数(驳回+返工),反复退回=派单或工单描述有问题 |
| `expected_at` | `datetime(3)` | 是 | — |  | 期望完成时间(开单时填,超期提示用) |
| `audit_result` | `varchar(16)` | 是 | — |  | 验收结论：PASS/PASS_WITH_ISSUE/FAIL（与前端 WoAuditResult 同值域） |
| `audit_note` | `varchar(255)` | 是 | — |  | 验收说明 |
| `source_ref` | `varchar(64)` | 是 | — | UQ | 来源单据引用(告警号/投诉号/巡检键)，手工开单为 NULL |

索引：`idx_wo_scope`(tenant_id,agent_no,status,created_at) · `uk_wo_no`(wo_no) **UNIQUE** · `uk_wo_source_ref`(source_ref) **UNIQUE**

### `wo_sla` — SLA 计时

实体 `WoSla` · 业务列 6 · 标准列缺 `tenant_id`/`created_at`/`version`/`deleted` ⚠️

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `wo_no` | `varchar(36)` | 否 | — | UQ | — |
| `respond_due_at` | `datetime(3)` | 是 | — |  | — |
| `resolve_due_at` | `datetime(3)` | 是 | — |  | — |
| `respond_breached` | `tinyint(1)` | 否 | `0` |  | — |
| `resolve_breached` | `tinyint(1)` | 否 | `0` |  | — |
| `escalated_at` | `datetime(3)` | 是 | — |  | 约定:时间列(实体用String映射) |

索引：`uk_sla_wo`(wo_no) **UNIQUE**

### `wo_sla_rule` — SLA 规则(按工单类型)

实体 `WoSlaRule` · 业务列 6 · 标准列齐备

| 列 | 类型 | 空 | 默认 | 键 | 说明 |
|---|---|---|---|---|---|
| `sla_no` | `varchar(36)` | 否 | — | UQ | 业务键 SLA* |
| `wo_type` | `varchar(16)` | 否 | — |  | FAULT/REFILL/INSPECT/INSTALL/REMOVE/COMPLAINT/CLEAN |
| `response_mins` | `int(11)` | 否 | `0` |  | 响应时限(分钟) |
| `resolve_mins` | `int(11)` | 否 | `0` |  | 解决时限(分钟) |
| `escalate_to` | `varchar(64)` | 是 | — |  | 超时升级到(role_no/employee_no) |
| `active` | `tinyint(1)` | 否 | `1` |  | 是否启用 |

索引：`idx_sla_rule_active`(tenant_id,active) · `uk_sla_rule_no`(sla_no) **UNIQUE** · `uk_sla_rule_type`(tenant_id,wo_type) **UNIQUE**

