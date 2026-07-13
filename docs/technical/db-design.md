# 服务端数据库设计（db-design.md）

> 状态：草稿（待确认）· 创建 2026-07-11
> 关联：[architecture.md](./architecture.md) · [ADR-002 多租户](./ADR/ADR-002-多租户隔离方案.md) · [ADR-007 隔离键映射](./ADR/ADR-007-租户隔离键与命名映射.md)
> 规范对齐：ai-neargo `db-naming.md`（ADR-010）；本文为服务端库表 SSOT，字段级 DDL 由各模块 TDD 细化。
> 字段级建表脚本（已出，全域）：[ddl/](./ddl/README.md) —— pb_core 全域 4 个 SQL（loc/agt/iam · dev/gw · ord/price/pay/acct/share/stl · usr/coupon/ad/wo/notify/dict）+ pii/auth 骨架，约 45 表。

---

## 一、命名与通用约定（对齐 neargo）

### 1.1 库 / 表（**合并方案：3 库**，ADR-010）
powerbank 是模块化单体部署，不采用 neargo「一域一库」，而是**按合规边界合并为 3 个库**：
- **`pb_core`** —— 全部业务域（platform / ops / trade / user / gateway）合库。域用**子域前缀**区分（`iam_`/`dev_`/`loc_`/`wo_`/`ord_`/`pay_`/`acct_`/`usr_`/`gw_`…），表名库内唯一 → 未来按域拆库 = 纯 schema 迁移、零改名。app-modulith 与 access-gateway 两进程共享此库（gateway 仅用 `gw_*`）。
- **`pb_pii`** —— 个人数据（`pii_*`），**独立 KMS + 区域驻留**（PDPL 硬要求，ADR-009，不能并入 core）。
- **`pb_auth`** —— 登录凭据（auth-core `cred_*`，`realm` 列区分员工/消费者池），**独立 KMS，仅 `neargo-auth-core` 访问**。
- **表名 = `<子域前缀>_<实体>`**：小写 `snake_case`、单数、≤16。前缀 = 子域 = 未来拆库单元。
- 聚合根标 ★；只增日志 `append`（按 `created_at` 月分区）；个人数据表 `pii_` 前缀且只落 `pb_pii`。

> 为何 trade 可并入 core：支付/收单**委托 nearpay**（ADR-005），powerbank 无资金 vault，「资金轨 CBUAE」压力在 nearpay 侧；powerbank trade 仅持订单/计费/账务记账/nearpay 引用，属业务数据，故并入 `pb_core`。

### 1.2 通用列（继承 commons `BaseEntity` v2，下文省略）
`id BIGINT UNSIGNED AUTO_INCREMENT PK`（库内物理主键，不跨库不对外）· `region_id VARCHAR(36)` · `created_at DATETIME(3)` · `updated_at DATETIME(3)` · `version BIGINT` · `deleted TINYINT(1)`。

### 1.3 隔离键与业务键
- **隔离键（域列）`tenant_id VARCHAR(36)`**：需隔离的业务表显式声明，存 `tenant_no` 值；`PowerbankTenantLineHandler` 自动注入（ADR-007）。全局表（`tenant`/`iam_`/`dict_`/`gw_vendor`/`md_`）放行不注入。
- **业务键 `<x>_no VARCHAR(36) UNIQUE`**：聚合根 / 跨域引用 / 对外 API 一律走全局业务键（ULID/雪花，`IdGenerator`），如 `tenant_no`/`order_no`/`cabinet_no`/`pay_no`。跨域**逻辑引用不建物理 FK**，仅建索引。

### 1.4 类型基线（MySQL 8）
InnoDB · `utf8mb4_0900_ai_ci` · 金额 `DECIMAL(18,2)` + `currency VARCHAR(8) DEFAULT 'AED'`（MENA 主市场，多币种，ADR-009）· 时间 `DATETIME(3)` UTC · 布尔 `TINYINT(1)` · 枚举 `VARCHAR` + 列注释（不用 MySQL ENUM，`status` 命名）· `JSON` · 敏感字段 `[KMS]` 落 `*_pii` 库 · **用户可见文本双语**：`xxx` + `xxx_ar` 或 `xxx_i18n JSON`（ar/en + RTL）。

### 1.5 库清单（3 库）
| 库 | 含子域（前缀）| 数据轨 | KMS | 访问方 | 说明 |
|----|-------------|-------|-----|-------|------|
| **`pb_core`** | platform(`iam_`/`notify_`/`dict_`/`tenant`) · ops(`dev_`/`loc_`/`wo_`/`inv_`) · trade(`ord_`/`price_`/`pay_`/`acct_`/`share_`/`stl_`/`recon_`) · user(`usr_`/`coupon_`/`mbr_`) · gateway(`gw_`) | 业务(+遥测) | 平台 | app-modulith + access-gateway | 遥测/流水/报文表按月分区 |
| **`pb_pii`** | `pii_*`（C端手机/证件等）| **PDPL** | **独立** | 业务域（脱敏访问）| 区域内驻留 |
| **`pb_auth`** | `cred_*`（`realm`=员工/消费者）| **PDPL** | **独立** | 仅 neargo-auth-core | 业务域只持 user_id/c_user_no 逻辑引用 |

> 由 neargo 的 8 库合并为 3 库。合并只影响物理归属，表名/子域前缀不变，域边界仍在（逻辑引用不建跨库物理 FK），规模化后可按前缀无痛拆出。

---

## 二、platform 子域 · 库 `pb_core`（平台支撑）

| 表 | 说明 | 关键列 |
|----|------|-------|
| `tenant` ★（全局）| 租户/品牌运营商 | `tenant_no` UK, name, brand_name, status(ENABLED/SUSPENDED), plan, quota `JSON`, contact, expire_at |
| `tenant_config` | 租户级配置（支付/计费/品牌/启用供应商渠道）| tenant_no, category(PAY/BILLING/BRAND/VENDOR), config_key, config_value `JSON`；UK(tenant_no,category,config_key) |
| `iam_employee` ★ | 员工 | `employee_no` UK, tenant_id, name, phone(掩码), user_id(→pb_auth_b), dept_id, status |
| `iam_dept` | 组织架构 | dept_no, tenant_id, parent_id, name, path |
| `iam_role` | 角色 | role_no, tenant_id(平台角色空), code, name |
| `iam_permission`（全局）| 权限目录 | `perm_code` UK, module, name |
| `iam_role_permission` | 角色权限 | role_no, perm_code |
| `iam_data_scope` | 数据权限（角色级+员工级统一）| **subject_type(ROLE/EMPLOYEE)**, subject_no, scope_type(ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF), scope_refs `JSON`（区域/站点/代理列表）|
| `audit_log` `append` | 操作审计(WORM) | tenant_id, user_id, action, target_type, target_no, detail `JSON`, ip, created_at |
| `notify_template` | 通知模板 | tenant_id, channel(SMS/SUBSCRIBE/PUSH/INAPP), code, content, params `JSON` |
| `notify_record` `append` | 触达记录 | tenant_id, channel, target, template_code, status, created_at |
| `dict_entry`（全局）| 字典 | dict_type, dict_key, dict_value, label |
| `openapi_app` | 开放平台应用 | tenant_id, app_key, app_secret(hash), scopes `JSON`, status |

---

## 三、ops 子域 · 库 `pb_core`（设备运维 · 点位 · 工单）

### 3.1 设备台账（子域 `dev_`）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `dev_cabinet` ★ | 机柜/充电桩 | `cabinet_no` UK, tenant_id, **agent_no(归属代理,可空)**, sn, vendor_code, model, location_no, slot_total, online_status(ONLINE/OFFLINE), last_heartbeat_at, fw_version, status(生命周期: DEPLOYED/FAULT/RETIRED) |
| `dev_slot` | 仓位 | cabinet_no, slot_index, powerbank_no(在仓), lock_status, health；UK(cabinet_no,slot_index) |
| `dev_powerbank` ★ | 充电宝 | `powerbank_no` UK, tenant_id, sn, vendor_code, battery, status(IN_STOCK/DEPLOYED/IN_USE/RETURNED/SCRAP/LOST), cabinet_no(当前), slot_index |
| `dev_shadow` | 设备影子快照(主 Redis) | cabinet_no, slots `JSON`, online, snapshot_at |
| `dev_heartbeat` `append`(月) | 心跳遥测 | cabinet_no, metrics `JSON`, beat_at |
| `dev_ota_release` | OTA 版本 | fw_type, version, version_code, artifact_url, checksum, mandatory, status(DRAFT/PUBLISHED/PAUSED/COMPLETED) |
| `dev_ota_rollout` | OTA 投放 | release_id, scope(DEVICE/LOCATION/ALL), target_ref, forced, status |
| `dev_ota_task` | 逐设备升级 | rollout_id, cabinet_no, status(PENDING/DOWNLOADING/INSTALLING/SUCCESS/FAILED/ROLLED_BACK), previous_version, progress |
| `dev_alert` | 设备告警 | tenant_id, cabinet_no, source(DEVICE/OTA/RENT), severity, code, message, status(OPEN/ACK/RESOLVED), dedup_key, count |

> OTA/alert 三层模型直接套 neargo PF10（`platform-device-ops.md`），对象换柜机。

### 3.2 站点 · 点位 · 场地方（子域 `loc_`，层级见[领域模型](./领域模型-本体与关系.md)）
> 本体层级：场地方 Venue 提供 → 站点 Site（网点）→ 点位 Point → 机柜。站点归属代理(agent_no,可空=直营)。
| 表 | 说明 | 关键列 |
|----|------|-------|
| `loc_venue` ★ | 场地方（物业/商户）| `venue_no` UK, tenant_id, name, contact `[KMS→pii]`, industry |
| `loc_site` ★（新）| 站点/网点 | `site_no` UK, tenant_id, **agent_no(归属代理,可空)**, venue_no, name, address, region_id, lng, lat, scene_type, status |
| `loc_location` ★ | 点位 Point（站点内投放位）| `location_no` UK, tenant_id, **site_no**, agent_no(冗余,随站点), name, spot_desc, status |
| `loc_contract` | 进场合同（场地方×站点）| `contract_no` UK, tenant_id, venue_no, **site_no**, share_rate `DECIMAL(5,4)`, entry_fee, settle_period, start_at, end_at, attach_url, status |

> `dev_cabinet.location_no` 指向点位；站点归属为权威、cabinet.agent_no 冗余便于查询。

### 3.3 库存调拨（子域 `inv_`，P1）
`inv_warehouse` · `inv_stock`(仓/区域库存) · `inv_transfer`(调拨单: transfer_no, from, to, status)。

### 3.5 代理商（子域 `agt_`，[ADR-012](./ADR/ADR-012-代理商模型.md)）
> 代理商=运营方体内经营伙伴，非租户。设备/点位经 `agent_no` 归属；分润复用 `share_*`(dimension=AGENT)；数据权限维度 `AGENT`。
| 表 | 说明 | 关键列 |
|----|------|-------|
| `agt_agent` ★ | 代理商 | `agent_no` UK, tenant_id, name, contact `[KMS→pii]`, region_scope `JSON`, default_share_rate `DECIMAL(5,4)`, settle_account, status(ENABLED/SUSPENDED) |
| `agt_account` | 代理登录账号 | `agent_no`, cred 引用(pb_auth realm=AGENT), role=AGENT, status |
> 代理设备/点位归属见 `dev_cabinet.agent_no` / `loc_location.agent_no`；代理收益/结算/提现复用 `share_record`/`stl_*`(payeeType=AGENT)。

### 3.4 工单（子域 `wo_`）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `wo_order` ★ | 工单 | `wo_no` UK, tenant_id, type(FAULT/REFILL/INSPECT/INSTALL/REMOVE/COMPLAINT/CLEAN), source(ALERT/USER/VENUE/MANUAL), priority, cabinet_no, location_no, **agent_no(冗余·数据权限过滤)**, **site_no(冗余)**, status(CREATED/DISPATCHED/ACCEPTED/PROCESSING/DONE/AUDITED/CLOSED), assignee_id, sla_due_at, description |
| `wo_dispatch` | 派单记录 | wo_no, assignee_id, strategy(NEAREST/LOAD/MANUAL/GRAB), dispatched_at, action |
| `wo_sla` | SLA 计时 | wo_no, respond_due_at, resolve_due_at, respond_breached, resolve_breached |
| `wo_handle` | 现场处理 | wo_no, assignee_id, photos `JSON`, note, part_changed, device_changed, handled_at |
| `wo_inspection_plan`(P1) | 巡检计划 | plan_no, tenant_id, route `JSON`, cron, assignee_id |

---

## 四、gateway 子域 · 库 `pb_core`（南向接入 · access-gateway 进程共享 core，仅用 gw_*）

| 表 | 说明 | 关键列 |
|----|------|-------|
| `gw_vendor`（全局）| 硬件供应商 | `vendor_code` UK, name, access_mode(TCP/MQTT/HTTP_API), status |
| `gw_vendor_config` | 供应商接入配置(可租户级) | vendor_code, tenant_id(空=全局), api_base, app_key, app_secret `[KMS]`, verify_key `[KMS]`, params `JSON` |
| `gw_device_binding` | 供应商标识↔平台SN映射 | sn, vendor_code, cabinet_no, raw_identity, bound_at |
| `gw_command_log` `append`(月) ★ | 指令下发日志(幂等源) | `command_id` UK, tenant_id, sn, cabinet_no, type(EJECT_SLOT/…), payload `JSON`, status(PENDING/SENT/ACKED/CONFIRMED/TIMEOUT/FAILED), retry, order_no, sent_at, confirmed_at |
| `gw_message_log` `append`(月) | 上下行报文留痕 | sn, direction(UP/DOWN), raw(脱敏), parsed_event, at |

> 运行态（连接会话 `SN→实例`、指令幂等 SETNX、影子）主要在 Redis，DB 仅留可追溯日志与配置。

---

## 五、trade 子域 · 库 `pb_core`（订单/计费/账务/分润；支付委托 nearpay）

### 5.1 订单 · 计费（子域 `ord_` / `price_`）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `ord_rent` ★ | 租借订单 | `order_no` UK, tenant_id, c_user_no, cabinet_no(借出), return_cabinet_no, powerbank_no, location_no, **agent_no(冗余·数据权限过滤)**, **site_no(冗余)**, price_plan_no, status(CREATED/DISPENSING/IN_USE/RETURNED/SETTLED/CLOSED/EXCEPTION), rent_start_at, rent_end_at, duration_min, fee_amount, deposit_amount, buyout `TINYINT` |
| `ord_event_log` `append` | 订单状态流水 | order_no, from_status, to_status, event, operator, created_at |
| `price_plan` ★ | 计费模板 | `plan_no` UK, tenant_id, name, unit_minutes, unit_price, free_minutes, cap_daily, cap_total(封顶/买断价), currency |
| `price_rule`(P1) | 差异化定价 | plan_no, dimension(SCENE/LOCATION/TIMESLOT), match_ref, price |

### 5.2 支付（子域 `pay_`）——**委托 neargo nearpay，延后集成（ADR-005）**
> powerbank 不落渠道/PSP 明文密钥；支付执行在 nearpay，本域只存**支付引用 + 状态镜像**驱动订单。MVP 用 `PaymentPort` Stub。

| 表 | 说明 | 关键列 |
|----|------|-------|
| `pay_order` ★ | 支付引用（映射 nearpay 交易）| `pay_no` UK, tenant_id, order_no, c_user_no, type(DEPOSIT/RENT/BUYOUT), amount, currency, status(INIT/PAYING/PAID/FAILED/CLOSED), **nearpay_txn_no**(引用), paid_at |
| `pay_auth` ★ | 免押编排状态（实际冻结在 nearpay）| `auth_no` UK, tenant_id, order_no, freeze_amount, captured_amount, status(FROZEN/CAPTURED/RELEASED), **nearpay_auth_no**(引用) |
| `pay_refund` | 退款引用 | `refund_no` UK, tenant_id, pay_no, amount, reason, status(INIT/SUCCESS/FAILED), **nearpay_refund_no** |
| `pay_event_log` `append` | nearpay 结果事件留痕(幂等) | source(NEARPAY), ref_no, event_type, raw `JSON`, processed, received_at；UK(ref_no,event_type) |

### 5.3 账务 · 分账（子域 `acct_` / `share_`）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `acct_account` ★ | 账户 | `account_no` UK, tenant_id, owner_type(PLATFORM/TENANT/VENUE/AGENT/USER), owner_no, balance, frozen, currency |
| `acct_ledger` `append`(月) | 复式记账分录 | `entry_no`, tenant_id, account_no, direction(D/C), amount, biz_type, biz_no, voucher_no, created_at |
| `share_rule` | 分润规则 | `rule_no`, tenant_id, dimension(VENUE/AGENT), mode(CHANNEL_SPLIT/LEDGER), rate/formula `JSON`, priority |
| `share_record` | 分润记录 | `share_no`, tenant_id, order_no, payee_type, payee_no, amount, mode, status(PENDING/DONE) |

### 5.4 结算 · 提现 · 对账（子域 `stl_` / `recon_`）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `stl_settlement` ★ | 结算单 | `settle_no` UK, tenant_id, payee_type, payee_no, period, total_amount, status(GEN/CONFIRMED/PAID) |
| `stl_settlement_detail` | 结算明细 | settle_no, ref_no(order/share), amount |
| `stl_withdrawal` | 提现 | `withdraw_no` UK, tenant_id, account_no, amount, status(APPLY/AUDIT/PAYING/PAID/FAILED), audit_by, paid_at |
| `recon_task` | 对账任务 | `recon_no`, tenant_id, channel, bill_date, status |
| `recon_diff` | 对账差错 | recon_no, pay_no, diff_type, detail `JSON` |

---

## 六、user 子域 · 库 `pb_core`（C端 · 会员 · 券）

| 表 | 说明 | 关键列 |
|----|------|-------|
| `usr_user` ★ | C端用户 | `c_user_no` UK, tenant_id, openid(主渠道冗余), unionid(冗余), nickname, avatar, status, credit_score（唯一性移交 `usr_identity`，不再用 UK(tenant_id,openid)）|
| `usr_identity` ★ | 多渠道身份绑定（登录归并）| c_user_no, tenant_id, provider(WECHAT_MP/WECHAT_OA/APPLE/GOOGLE/PHONE), provider_uid(openid / apple·google sub / 手机哈希), union_key(微信 unionid，或 'provider:uid'), bound_at；UK(tenant_id,provider,provider_uid)；IDX(tenant_id,union_key) |
| `usr_credit` | 信用/黑名单 | c_user_no, tenant_id, score, blacklisted, reason |
| `usr_wallet` | 钱包 | `wallet_no` UK, tenant_id, c_user_no, balance, gift_balance, deposit_amount |
| `usr_wallet_txn` `append` | 钱包流水 | wallet_no, direction, amount, biz_type, biz_no, created_at |
| `coupon_tpl` | 券模板 | `tpl_no` UK, tenant_id, type(CUT/DISCOUNT), value, threshold, valid_rule `JSON`, stock |
| `usr_coupon` | 用户券 | `coupon_no` UK, tenant_id, c_user_no, tpl_no, status(UNUSED/USED/EXPIRED), used_order_no |
| `mbr_plan` | 会员方案 | `plan_no` UK, tenant_id, name, price, period, rights `JSON` |
| `usr_membership` | 用户会员 | `mbr_no` UK, tenant_id, c_user_no, plan_no, start_at, end_at, status |

> **多渠道登录归并（[TDD-认证鉴权-实现细节 Part B](./TDD-认证鉴权-实现细节.md)）**：App/小程序/H5 各渠道身份落 `usr_identity`（1 用户 : N 身份）；同一微信用户的小程序 openid 与公众号 H5 openid 靠 `union_key`(unionid) 归并同一 `c_user_no`；phone/apple/google 各成一条身份。前提：微信开放平台已绑定小程序+公众号同主体。

### 6.1 广告（子域 `ad_`，P2 · 未来设备投屏广告，[领域模型](./领域模型-本体与关系.md)）
> 广告位挂机柜；广告可按 区域/站点/场景 定向；未来广告收入可纳入分润。首期可仅建 `ad_slot` 预留。
| 表 | 说明 | 关键列 |
|----|------|-------|
| `ad_slot` | 广告位 | `ad_slot_no` UK, tenant_id, cabinet_no, type(SCREEN/BODY), status |
| `ad_advertiser` | 广告主 | `advertiser_no` UK, tenant_id, name, contact |
| `ad_campaign` | 广告活动 | `campaign_no` UK, tenant_id, advertiser_no, budget, target `JSON`(region/site/scene), start_at, end_at, status |
| `ad_creative` | 广告创意 | `creative_no` UK, campaign_no, media_url, duration, mime |
| `ad_placement` | 投放排期 | `placement_no` UK, campaign_no, creative_no, ad_slot_no, schedule `JSON`, status |
| `ad_impression` `append`(月) | 曝光统计 | placement_no, cabinet_no, played_at, duration |

## 七、库 `pb_pii`（个人数据 · 独立 KMS · PDPL）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `pii_user` | C端敏感信息 | c_user_no, tenant_id, phone `[KMS]`, real_name `[KMS]`, id_no `[KMS]` |

> 独立库 + 独立 KMS + 区域驻留是 PDPL 硬要求（ADR-009），**不并入 pb_core**。业务域经脱敏接口访问。

## 八、库 `pb_auth`（凭据 · 独立 KMS · 复用 neargo-auth-core）
`cred_credential`（`realm` 区分员工/运维池 与 消费者池，取代 neargo 的 ng_auth_b/ng_auth_c 双库）——结构由 auth-core 定义，**仅 auth-core 访问**；业务域仅持 `user_id`/`c_user_no` 逻辑引用，不落凭据。

---

## 九、ER 关键关系（逻辑，无物理 FK）

```
tenant 1─* iam_employee / loc_location / dev_cabinet / usr_user ...       （租户隔离）
loc_venue 1─* loc_location 1─* dev_cabinet 1─* dev_slot *─1 dev_powerbank
usr_user 1─* usr_identity（多渠道登录身份，unionid 归并）
usr_user 1─* ord_rent 1─* pay_order / pay_auth / pay_refund
iam_role 1─* iam_role_permission ；iam_data_scope(subject=ROLE|EMPLOYEE) 承载角色级+员工级数据权限
ord_rent 1─* share_record ；acct_account 1─* acct_ledger
dev_alert *─1 dev_cabinet ；dev_alert 1─0..1 wo_order（告警联动开单）
gw_command_log *─1 dev_cabinet ；gw_command_log 0..1─1 ord_rent（弹出指令）
```

## 十、待确认
1. 隔离键落地：直接用 `merchant_id` 复用 commons handler，还是按 ADR-007 用 `tenant_id` + 自有 handler？（本文按 `tenant_id`）
2. 币种/区域：默认 `AED`、多币种、多 region（UAE→MENA），用户可见字段 ar/en 双语（ADR-009 已定）；PII/资金按区域驻留细节待定。
3. ~~gateway 独立库~~ → 已合并入 `pb_core`（ADR-010）；access-gateway 进程共享 core 仅用 `gw_*`。
4. 分区/归档策略细节（心跳/报文/账本月分区的保留周期）。
5. 合并粒度确认：是否接受 3 库（core + pii + auth）；若更激进可将 pii+auth 合为 1 个 `pb_secure`（2 库），但削弱访问边界。
6. 认证鉴权驱动的表变更（已并入本文 + ddl/，2026-07-12，源 [TDD-认证鉴权-实现细节](./TDD-认证鉴权-实现细节.md)）：`usr_identity`(新)、`usr_user` 去 openid 唯一键、`iam_data_scope` 用 `subject_type(ROLE/EMPLOYEE)` 统一角色/员工级 + scope_type 补 SITE/VENUE/AGENT/SELF、`ord_rent`/`wo_order` 增 `agent_no`（`site_no` 已有）。**待确认**：归属列冗余 vs 子查询（本文取冗余，避免 AGENT 查询走子查询）。
