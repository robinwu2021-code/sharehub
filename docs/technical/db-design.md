# 服务端数据库设计（db-design.md）

> 状态：**v2 全量重整**（2026-07-29）· 初版 2026-07-11
> 关联：[architecture.md](./architecture.md) · [api/README.md](../api/README.md) · [ddl/](./ddl/README.md)
> 规范对齐：ai-neargo `db-naming.md`（ADR-010）；本文为**服务端库表 SSOT**。
>
> ## 本次重整的依据与口径（必读）
> v1（2026-07-11）写于运营端 73 项菜单、C 端清单未细化之前。此后运营端按[功能覆盖原则](../requirements/运营端功能清单.md#-功能覆盖原则2026-07-29-用户定调硬约束)对标补齐到 **98 项菜单叶**、C 端定稿 **17 模块**，前端已 100% 落地（mock）。**v2 以三份文档 + 一份代码为输入源，逐项反查建表**：
>
> | 输入源 | 作用 | 数量 |
> |---|---|---|
> | [运营端功能清单 §三](../requirements/运营端功能清单.md) | 运营端菜单叶全量单表（与 `ops-web/lib/nav.ts` 逐行 1:1） | 98 叶 |
> | [C端功能清单](../requirements/C端功能清单.md) | C 端子功能编号 `C-XX-NN` | 17 模块 |
> | [功能权限清单](../requirements/功能权限清单.md) | 权限码 = 资源边界 | 89 码 |
> | `ops-web/lib/types/*.ts` | **前端已定型的实体与字段**（70 实体） | 161 契约方法 |
>
> **原则**：前端已定型的字段即为本表的「关键列」下界 —— 前端能展示的，库里必须存得下。前端 mock 里出现、v1 未建表的实体，本次一律补表并标 `NEW`。
>
> **表数**：v1 63 表 → **v2 `pb_core` 131 表**（新增 68，改造 6）+ `pb_pii` 1 + `pb_auth` 1 = **133**。数字由 `ddl/*.sql` 的 `CREATE TABLE` 实数核出，非估算。逐表来源见 §十一 覆盖核对表。

---

## 一、命名与通用约定（对齐 neargo）

### 1.1 库 / 表（**3 库**，ADR-010）
powerbank 是模块化单体部署，不采用 neargo「一域一库」，而是**按合规边界合并为 3 个库**：

- **`pb_core`** —— 全部业务域（platform / ops / trade / user / gateway）合库。域用**子域前缀**区分，表名库内唯一 → 未来按域拆库 = 纯 schema 迁移、零改名。app-modulith 与 access-gateway 两进程共享此库（gateway 仅用 `gw_*`）。
- **`pb_pii`** —— 个人数据（`pii_*`），**独立 KMS + 区域驻留**（PDPL 硬要求，ADR-009）。
- **`pb_auth`** —— 登录凭据（`cred_*`，`realm` 列区分员工/代理/消费者池），**独立 KMS，仅 `neargo-auth-core` 访问**。

- **表名 = `<子域前缀>_<实体>`**：小写 `snake_case`、单数、≤16。前缀 = 子域 = 未来拆库单元。
- 聚合根标 ★；只增日志 `append`（按 `created_at` 月分区）；个人数据表 `pii_` 前缀且只落 `pb_pii`。
- **读模型标 `[读]`**：不建物理表，由既有表聚合/联合而来（统计页、分析页、双流日志）。§十一 会标明它们各自的来源表。

### 1.2 子域前缀总表（v2）

| 域 | 前缀 | 含义 | 表数 |
|---|---|---|---|
| platform | `tenant` `iam_` `notify_` `dict_` `md_` `sys_` `openapi_` | 租户口子·组织权限·消息·字典·主数据·系统配置 | 26 |
| ops | `dev_` `inv_` `loc_` `agt_` `wo_` | 设备·库存·场地·代理·工单 | 37 |
| gateway | `gw_` | 南向接入 | 5 |
| trade | `ord_` `price_` `pay_` `acct_` `share_` `stl_` `recon_` `fin_` | 订单·计费·支付·账务·分润·结算·对账·发票 | 28 |
| user | `usr_` `mbr_` `coupon_` `mkt_` `ad_` `cs_` | C端·会员·券·营销·广告·客服 | 35 |
| **`pb_core` 小计** | | | **131** |
| pii / auth | `pii_` `cred_` | 个人数据 / 凭据（**独立库**） | 2 |

> 审计表落 `iam_` 前缀（`iam_audit_log`），不单设 `audit_` 前缀 —— 见 §2.2。

### 1.3 通用列（继承 commons `BaseEntity` v2，下文各表省略）
`id BIGINT UNSIGNED AUTO_INCREMENT PK`（库内物理主键，不跨库不对外）· `region_id VARCHAR(36)` · `created_at DATETIME(3)` · `updated_at DATETIME(3)` · `version BIGINT` · `deleted TINYINT(1)`。

> ⚠️ **软删除是全站唯一删除语义**（对应[横向缺口 G1](../requirements/运营端功能清单.md#三b-横向缺口跨模块2026-07-29-梳理发现)）：master 数据一律 `deleted=1` 逻辑删除，**不做物理删除**；已发生业务引用的记录（设备产生过订单、场地方签过合同）连逻辑删除都禁止，只允许改 `status` 停用。

### 1.4 隔离键与业务键
- **隔离键 `tenant_id VARCHAR(36)`**：需隔离的业务表显式声明，MVP 恒为 `MAIN`；`PowerbankTenantLineHandler` 自动注入（ADR-007）。全局表（`tenant`/`iam_permission`/`dict_`/`md_`/`gw_vendor`）放行不注入。
- **业务键 `<x>_no VARCHAR(36) UNIQUE`**：聚合根 / 跨域引用 / 对外 API 一律走全局业务键（`IdGenerator`），如 `order_no`/`cabinet_no`/`alarm_no`/`refund_no`。跨域**逻辑引用不建物理 FK**，仅建索引。
- **归属冗余列**：`agent_no` / `site_no` 在 `ord_rent` / `wo_order` / `dev_cabinet` / `dev_alarm` 上冗余，供 `AGENT`/`SITE` 数据范围直接过滤，避免子查询（ADR-012）。
- **展示名冗余列**（`*_name`）：`site_name`/`venue_name`/`payee_name`/`assignee_name`/`nickname` 等一律「**存 `_no` 为准 + 冗余 `_name` 供列表直出**」。`_no` 是权威，`_name` 是写入时快照，**不随源改名回溯更新**（历史单据要留当时的名字）。
- **计数列不是列，是聚合**：`site.point_count`/`cabinet_count`、`venue.location_count`、`agent.cabinet_count`、`role.perm_count`/`member_count`、`cuser.orders`、`coupon.issued`、`code_batch.bound` 等前端字段**不落物理列**，由 API 层聚合。落列必然与明细表不自洽（前端 mock 已踩过：钱包页 `orderCount` 必须直接取订单表同用户号计数才两页自洽）。

### 1.4.1 业务键前缀注册表（`IdGenerator` 规格 · SSOT）
> 来源：`ops-web/lib/mock/db/*` 的实际取号。**后端 `IdGenerator` 必须与此一致**，否则前后端切换时主键格式漂移。
> 生成规则：`前缀 + 数字`，取号 = **扫描同前缀已有号取 max+1**，禁止 `base + 数组长度`（该写法已导致 `saveProblem`/`saveNotifyBlacklist` 撞主键，2026-07-29 已修）。

| 前缀 | 实体 | 前缀 | 实体 | 前缀 | 实体 |
|---|---|---|---|---|---|
| `CAB` | 机柜 | `PB` | 充电宝 | `SN` | 设备序列号 |
| `CMD` | 指令记录 | `LOG` | 设备日志 | `BC` | 编码批次 |
| `TR` | 调拨单 | `OTA` | OTA 投放 | `ALM` | 告警记录 |
| `AN` | 告警通知 | `AR` | 通知规则 | `WO` | 工单 |
| `SLA` | SLA 规则 | `IP` | 巡检计划 | `ST` | 站点 |
| `LOC` | 点位 | `VEN` | 场地方 | `CT` | 合同 |
| `LD` | 商机 | `OB` | 门店进件 | `AG` | 代理商 |
| `AA` | 代理账号 | `AC` | 代理分润 | `ORD` | 租借订单 |
| `DEP` | 押金 | `CPL` | 投诉 | `RFD` | 退款 |
| `RSV` | 预约 | `PD` | 差异化定价 | `PS` | 时段价 |
| `SR` | 分润规则 | `SREC` | 分润记录 | `STL` | 结算单 |
| `WD` | 提现 | `LE` | 账务分录 | `V` | 记账凭证 |
| `RC` | 对账批次 | `INV` | 发票(运营侧) | `UINV` | 发票(C端申请) |
| `RP` | 充值套餐 | | | | |
| `U` | C端用户/会员/钱包/白名单 | `RK` | 风控 | `BL` | 用户黑名单 |
| `CP` | 优惠券 | `CMP` | 活动 | `PM` | 推送 |
| `RF` | 裂变邀请 | `AS` | 广告位 | `AD` | 广告活动 |
| `DLV` | 投放曝光 | `NTC` | 公告 | `TK` | 报障工单 |
| `CS` | 客服会话 | `E` | 员工 | `R` | 角色 |
| `A` | 审计 | `D` | 部门 | `T` | 租户 |
| `NT` | 通知模板 | `DC` | 字典 | `APP` | OpenAPI 应用 |
| `NBL` | 触达拉黑 | `ISS` | 问题字典 | `BK` | 银行 |
| `CH` | 支付渠道 | `SEG` | 消费者分群 | `NL` | 通知发送记录 |

**自然键（无前缀，对外有语义）**：`vendor_code` · `channel_code` · `bank_code` · `alarm_code` · `param_key` · `region_id` · `country_code`（ISO alpha-2）· `sys_tax_setting.country` · `sys_login_setting.country`（`*` = 默认行）· `sys_app_version.version_id`（复合 `PLATFORM-versionNo`）。

> ⚠️ **自然键在非全局表上必须与 `tenant_id` 组成复合 UK**（`sys_param` / `sys_login_setting` / `sys_tax_setting` 三处）。单列 UK 在启用多租户时必撞 —— 休眠口子也要建对，否则将来是破坏性迁移。真正的全局表（`md_bank` / `md_market_country` / `md_region` / `dev_alarm_code` / `iam_permission` / `gw_vendor`）才可用单列 UK。

**已知前缀冲突（必须保持区分，勿合并）**：`NBL`(触达拉黑) ≠ `BL`(用户黑名单) · `ISS`(问题字典) ≠ `PB`(充电宝)。

### 1.5 类型基线（MySQL 8 / MariaDB）
InnoDB · `utf8mb4_0900_ai_ci` · 金额 `DECIMAL(18,2)` + `currency VARCHAR(8) DEFAULT 'AED'`（ADR-009）· 比率 `DECIMAL(5,4)` · 时间 `DATETIME(3)` UTC · 布尔 `TINYINT(1)` · 枚举 `VARCHAR` + 列注释（不用 MySQL ENUM，列名 `status`/`type`/`level`）· `JSON` · 敏感字段 `[KMS]` 落 `pb_pii`。

**比率列约定**（v2 统一，前端目前两套并存必须收敛）：
- **小数比率 `DECIMAL(5,4)`，取值 0..1** —— 一切「分成/费率」：`share_rate`、`default_share_rate`、`rate`、`fee_rate`。
- **百分数 `DECIMAL(5,2)`，取值 0..100** —— 一切「进度/占比/税率」：`rollout_percent`、`progress`、`rate_percent`、`online_rate`、`fault_rate`。
- 列名即约定：`*_rate` = 小数，`*_percent` / `*_rate`(统计派生) = 百分数。**前端 `Agent.shareRate`(0..1) 与 `TaxSetting.ratePercent`(0..100) 并存是已知不一致**，后端按本约定存，由 API 层做展示换算。

**金额列约定**：一律 `DECIMAL(18,2)` + **同表必带 `currency VARCHAR(8)`**。前端目前有 6 处带金额却无币种（`coupon_tpl.value/threshold`、`loc_contract.entry_fee`、`agt_commission`、`share_rule`、`sys_biz_rule` 三档规则），**v2 一律补 `currency`** —— 多国开城后无币种的金额是脏数据。

**时间列约定**：`DATETIME(3)` UTC 存储；仅日期语义用 `DATE`（`valid_from`/`valid_to`/`effective_from`/`stage_at`/`effective_at`），账期用 `CHAR(7)`（`YYYY-MM`：`period`），时刻用 `CHAR(5)`（`HH:mm`：`quiet_start`/`quiet_end`）。可空时间戳的语义统一为「尚未发生」（`audited_at`/`handled_at`/`released_at`/`paid_at`/`review_at`）。

**三语文本列约定**（i18n，ar/en/zh + RTL）：用户可见文本用 **`xxx` + `xxx_en` + `xxx_ar` 三列**（对齐前端 `Notice.title/titleEn/titleAr`、`ProblemEntry`、`AppVersion.releaseNote*`、`BankEntry.bankNameEn`），不用 `JSON`——前端已按三列取值，且需按列检索。仅当语种可能扩展的场景（`notify_template`）才用 `lang` 行区分。

### 1.6 幂等键约定（v2 新增，硬要求）
资金与外呼类写操作必须落幂等键，**列名统一 `idempotency_key VARCHAR(64)` + 表内 UNIQUE**：

| 表 | 幂等键来源 | 防的是 |
|---|---|---|
| `ord_refund` | 前端生成，`RefundRecord.idempotencyKey` | 重复退款 |
| `gw_command_log` | `command_id` | 重复弹出 |
| `pay_event_log` | UK(`ref_no`,`event_type`) | 回调重放 |
| `dev_alarm` → `wo_order` | UK(`alarm_no`) on `wo_order.source_ref` | 告警重复开单 |
| `ord_complaint` → `wo_order` | UK(`complaint_no`) on `wo_order.source_ref` | 投诉重复开单 |

### 1.7 多值列的落法（前端目前是 CSV 字符串，后端必须拆）
前端为渲染方便把多值塞进一个逗号串，**后端不得照抄**——否则无法按值检索、无法约束合法值。

| 前端字段 | 落法 |
|---|---|
| `PaymentChannel.countries` / `currencies` / `capabilities` | `pay_channel_scope`(channel_code, scope_type(COUNTRY/CURRENCY/CAPABILITY), scope_value)；UK 三列 |
| `RechargePackage.markets` | `usr_recharge_pkg_market`(package_no, country_code)；UK |
| `Agent.regionScope` | `agt_agent_region`(agent_no, region_id)；UK |
| `AgentAccount.dataScope` | 复用 `iam_data_scope`(subject_type=**AGENT_ACCOUNT**, subject_no=account_no) |
| `PricePlan.scope` | `price_plan_scope`(plan_no, scope_type(SITE/SCENE/ALL), scope_ref)；UK |
| `TenantConfig.enabledVendors` | `tenant_config` 已是 `JSON`，保留 |
| `Invoice.orderNos` / `usr_invoice.orderNos` | `fin_invoice_item`(invoice_no, order_no)；UK |

> 这 6 张关联表已计入 §1.2 的 131。

---

## 二、platform 子域 · 库 `pb_core`（26 表）

### 2.1 租户（🔒 休眠口子，产品层不体现 · ADR-011）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `tenant` ★（全局）| 租户/品牌运营商 | `tenant_no` UK, name, brand_name, status(ENABLED/SUSPENDED), plan, cabinet_count, quota `JSON`, contact, expire_at |
| `tenant_config` | 租户级配置 | tenant_no, category(PAY/BILLING/BRAND/VENDOR), config_key, config_value `JSON`；UK(tenant_no,category,config_key) |

> MVP 恒 `tenant_no=MAIN`，无管理面。表结构就绪但不暴露 API。

### 2.2 组织 · 员工 · 权限（菜单：员工与权限 5 叶）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `iam_employee` ★ | 员工 | `employee_no` UK, tenant_id, name, phone(掩码,明文→pii), email, dept_no, role_no, user_id(→pb_auth), status(ACTIVE/LEFT) | 员工 |
| `iam_dept` | 组织架构 | `dept_no` UK, tenant_id, parent_no, name, leader_no, member_count, path, sort | 组织架构 |
| `iam_role` | 角色 | `role_no` UK, tenant_id, code, name, builtin, data_scope(ALL/REGION/LOCATION/AGENT/SELF), scope_refs | 角色权限 |
| `iam_permission`（全局）| 权限目录 | `perm_code` UK, module, name | 角色权限 |
| `iam_role_perm` | 角色权限 | role_no, perm_code；UK(role_no,perm_code) | 角色权限 |
| `iam_employee_role` | 员工角色 | employee_no, role_no；UK | 员工 |
| `iam_data_scope` | 数据权限（角色级+员工级统一）| subject_type(ROLE/EMPLOYEE), subject_no, scope_type(ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF), scope_refs `JSON`；UK(subject_type,subject_no) | 角色权限·数据权限抽屉 |
| `iam_menu` | 菜单树（动态导航）| `menu_no` UK, parent_no, name, name_en, name_ar, type, path, icon, sort, perm, visible, status | —（支撑 nav） |
| `iam_staff_perf` `NEW` | 员工绩效 | employee_no, period, role, handled, avg_resolve_mins, score；UK(employee_no,period) | 绩效报表 |
| `iam_audit_log` `append`(月) | 操作审计(WORM) | tenant_id, actor(employee_no), actor_name, action, target_type, target_no, detail `JSON`, ip, created_at | 操作审计 |

> ⚠️ **表名以现有 DDL 的 `iam_audit_log` 为准**（v1 正文写 `audit_log`、DDL 建的是 `iam_audit_log`，v2 统一取后者 —— 它符合「前缀 = 子域」规则）。现有 DDL 只有单列 `target`，需按上表拆为 `target_type` + `target_no`。

> `iam_data_scope` 落库是[缺口 G7](../requirements/运营端功能清单.md#三b-横向缺口跨模块2026-07-29-梳理发现)（数据权限抽屉当前只有 UI、保存丢弃）的后端前提。

### 2.3 消息触达（菜单：系统设置·消息触达 3 叶 + 告警通知）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `notify_template` | 通知模板 | `template_no` UK, tenant_id, name, channel(SMS/EMAIL/PUSH/WHATSAPP), lang(ar/en/zh), scene, content, params `JSON`, status | 通知模板 |
| `notify_log` `append`(月) `NEW` | 发送记录 | `log_no`(前缀 `NL`) UK, tenant_id, channel, template_no, target(**存储即脱敏**), scene, sent_at, status(SENT/FAILED), fail_reason, **cost `DECIMAL(18,4)`**（单条触达成本，是 §1.5「金额 `(18,2)`」的**唯一例外** —— 单条短信可能是 0.0035 AED，两位小数会全归零）, currency | 发送记录 |
| `notify_blacklist` `NEW` | 触达拉黑（全渠道）| `block_no` UK, tenant_id, target, channel(SMS/EMAIL/PUSH/WHATSAPP/**ALL**), reason(USER_OPT_OUT/HARD_BOUNCE/ABUSE/MANUAL), blocked_at, blocked_by, expire_at, **released_at**, **released_by**, status(ACTIVE/RELEASED) | 触达拉黑 |

> **比竞品清晰在哪**：对方只有「短信拉黑」，我们 `channel` 含 `ALL` 覆盖全渠道；`notify_log.cost` 让触达成本可核算。
> **解除拉黑是软删除**：`status=RELEASED` + 保留记录，不物理删（合规留痕）。

### 2.4 系统配置（菜单：系统设置·业务规则/基础字典/开放与市场）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `dict_item`（全局）| 参数字典 | `dict_no` UK, group_code, code, label, label_en, label_ar, sort, enabled | 参数字典 |
| `md_region`（全局）| 地区库 | `region_id` UK, name, name_en, name_ar, parent_id, level, city_count | 地区库 |
| `md_bank`（全局）`NEW` | 银行字典（提现收款方）| `bank_code` UK, bank_name, bank_name_en, country, currency, swift_prefix, **iban_length**, status | 银行管理 |
| `md_problem` `NEW` | C端报障问题字典 | `problem_no` UK, category(RENT/RETURN/BILLING/DEVICE/ACCOUNT/OTHER), title/title_en/title_ar, answer/answer_en/answer_ar, **suggested_action**(SELF_SERVICE/TO_WORKORDER/TO_REFUND/TO_CS), sort_no, status | 问题管理 |
| `md_market_country` `NEW` | 多国家市场 | `country_code` UK(ISO alpha-2), name/name_en/name_ar, currency, timezone, compliance, status(LIVE/PILOT/PLANNED)；`city_count` 为 `[读]` 聚合自 `md_region` | 多国家市场 |
| `sys_param` | 系统参数 | UK(**tenant_id**,`param_key`), label, value, group_name, updated_at | 系统参数 |
| `sys_biz_rule` `NEW` | 业务规则（三分区单例）| tenant_id, category(**WITHDRAW/RESERVATION/BILLING**), rule `JSON`, currency, updated_at, updated_by；UK(tenant_id,category) | 业务规则 |
| `sys_login_setting` `NEW` | 登录设置（按国家）| UK(**tenant_id**,`country`)（`*` = 默认行）, country_name, otp_enabled, password_enabled, apple_enabled, google_enabled, otp_expire_sec, otp_daily_limit, force_real_name | 登录设置 |
| `sys_app_version` `NEW` | C端应用版本 | `version_id` UK, version_no, platform(IOS/ANDROID/H5), build_no, release_note/_en/_ar, force_update, min_supported, **rollout_percent**, download_url, status(DRAFT/RELEASED/ROLLBACK), released_at；UK(platform,version_no) | 应用版本 |
| `sys_tax_setting` `NEW` | 税率与发票 | UK(**tenant_id**,`country`), country_name, tax_name, rate_percent, trn, invoice_title, **included_in_price**, effective_from | 税率与发票 |
| `openapi_app` | 开放平台应用 | `app_no` UK, tenant_id, name, app_key UK, app_secret(hash), scopes `JSON`, rate_limit, status | OpenAPI 应用 |

> **`sys_biz_rule` 是提现手续费口径的唯一来源**：`category=WITHDRAW` 的 `rule.feeRate/feeCap/minAmount/settleDays/dailyLimit/needApproval` 是 `stl_withdrawal.fee` 的计算依据，财务页不得另存一份。

---

## 三、ops 子域 · 库 `pb_core`（33 表）

### 3.1 设备台账（`dev_`，菜单：设备管理 8 叶）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `dev_cabinet` ★ | 机柜/充电桩 | `cabinet_no` UK, tenant_id, **agent_no**, sn, vendor_code, model, location_no, site_no(冗余), slot_total, available_count, online_status(ONLINE/OFFLINE ← **正交轴**), last_heartbeat_at, fw_version, **status(IN_STOCK/DEPLOYED/FAULT/RETIRED)** ← 补 `IN_STOCK`，见 **§9A.2** | 设备台账 |
| `dev_slot` | 仓位 | cabinet_no, slot_index, powerbank_no(在仓), lock_status(LOCKED/UNLOCKED), health(OK/FAULT)；UK(cabinet_no,slot_index) | 机柜详情·仓位明细 |
| `dev_powerbank` ★ | 充电宝 | `powerbank_no` UK, tenant_id, sn, vendor_code, battery, **cycles**, health(OK/FAULT), **status(IN_STOCK/IN_CABINET/RENTED/FAULT/LOST/SOLD/SCRAP)** ← 两套枚举合一，见 **§9A.1**, cabinet_no, slot_index | 充电宝管理 |
| `dev_shadow` | 设备影子快照（主 Redis，DB 兜底）| cabinet_no, slots `JSON`, online, signal, temp, fault_count, snapshot_at | 实时监控 |
| `dev_heartbeat` `append`(月) | 心跳遥测 | cabinet_no, metrics `JSON`, beat_at | 实时监控 |
| `dev_code_batch` `NEW` | 设备编码批次 | `batch_no` UK, tenant_id, vendor_code, code_type(QR/SN), range_start, range_end, total, **bound**, produced_at, status(PENDING/PARTIAL/BOUND/VOID) | 设备编码 |
| `dev_ota_release` | OTA 版本 | `release_no` UK, fw_type, vendor_code, **`fw_version`**(固件版本串 —— **不能叫 `version`**，那是乐观锁列名), version_code, artifact_url, checksum, mandatory, status(DRAFT/PUBLISHED/PAUSED/COMPLETED) | 固件 OTA |
| `dev_ota_rollout` | OTA 投放 | `rollout_no` UK, release_no, fw_version, vendor_code, strategy(**GRAY/FULL**), scope(DEVICE/LOCATION/ALL), target_ref, progress, status(PENDING/RUNNING/DONE/**ROLLBACK**) | 固件 OTA |
| `dev_ota_task` | 逐设备升级 | rollout_no, cabinet_no, status(PENDING/DOWNLOADING/INSTALLING/SUCCESS/FAILED/ROLLED_BACK), previous_version, progress | 固件 OTA |

- **`[读] 实时监控 CabinetMonitor`** = `dev_cabinet` ⋈ `dev_shadow`（online/heartbeat_at/signal/temp/fault_count），不建表。
- **`[读] 设备日志 DeviceLog`** = `gw_command_log`(stream=COMMAND, direction=DOWN) **UNION ALL** `gw_message_log`(stream=REPORT, direction=UP)，按 `occurred_at` 同一时间轴排序。**不新建表** —— 这正是「比竞品清晰」的做法：对方只有单向「充电桩日志」，我们把下行指令与上行上报并到一条时间轴，排障时因果可读。

### 3.2 告警（`dev_alarm*`，菜单：告警管理 4 叶）— **v2 新增模块**
> v1 只有单表 `dev_alert`，无法承载「多厂商错误码归一化 + 处置预案字典 + 静默窗口」。v2 拆为四表，`dev_alert` **改造并更名** `dev_alarm`。

| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `dev_alarm` ★ `改造` | 告警记录 | `alarm_no` UK, tenant_id, cabinet_no, site_no, **agent_no**(数据权限), vendor_code, **alarm_code**(平台统一码→`dev_alarm_code`), **vendor_error_code**(厂商原始码), level(INFO/WARN/CRITICAL), source(DEVICE/OTA/RENT), occurred_at, status(OPEN/ACKED/CLOSED), **wo_no**(关联工单), remark, dedup_key, count | 告警记录 |
| `dev_alarm_notice` `append` `NEW` | 告警通知流水 | `notice_no` UK, alarm_no, channel(SMS/EMAIL/PUSH/WEBHOOK), target, sent_at, status(SENT/FAILED), fail_reason | 告警通知 |
| `dev_alarm_code`（全局）`NEW` | 告警代码字典 | `code` UK, message, message_en, message_ar, level, **suggestion**(建议处置), **auto_work_order**(是否自动开单) | 告警代码 |
| `dev_alarm_rule` `NEW` | 通知规则 | `rule_no` UK, tenant_id, alarm_code, target, channel, method(INSTANT/DIGEST), **quiet_start**/**quiet_end**(静默窗口), **escalate_minutes**(升级策略), status | 通知规则 |

> **比竞品清晰在哪**（三处，均落在列上）：① `alarm_code` + `vendor_error_code` 双列 → 多厂商错误码归一化，对方只有单一厂商码；② `dev_alarm.wo_no` → 告警可一键转工单且幂等，对方到「发通知」就断链；③ `dev_alarm_code.suggestion/auto_work_order` → 字典即处置预案；`dev_alarm_rule.quiet_*/escalate_minutes` → 防夜间轰炸与告警风暴。

### 3.3 库存调拨（`inv_`，菜单：库存调拨）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `inv_warehouse` | 仓库 | `warehouse_no` UK, tenant_id, name, region_id, address |
| `inv_stock` | 仓/区域库存 | warehouse_no, item_type(CABINET/POWERBANK), model, qty, updated_at；UK(warehouse_no,item_type,model) |
| `inv_transfer` `NEW` | 调拨单 | `transfer_no` UK, tenant_id, **from_type/to_type**(WAREHOUSE/SITE/LOCATION), from_ref/to_ref, from_name/to_name(快照), item_type, powerbank_count, status(DRAFT/IN_TRANSIT/DONE), operator_no, created_at |
| `inv_transfer_item` `NEW` | 调拨明细 | transfer_no, powerbank_no/cabinet_no, checked |

### 3.4 场地（`loc_`，菜单：站点与点位 8 叶）
> 本体层级：**场地方 Venue → 站点 Site（网点）→ 点位 Point → 机柜**。站点归属代理（`agent_no` 可空=直营）。

| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `loc_venue` ★ | 场地方（物业/商户）| `venue_no` UK, tenant_id, name, contact `[KMS→pii]`, industry, location_count | 场地方 |
| `loc_site` ★ | 站点/网点 | `site_no` UK, tenant_id, **agent_no**, venue_no, venue_name(冗余), name, address, region_id, lng, lat, scene_type, open_hours, point_count, cabinet_count, status(ACTIVE/PAUSED) | 站点管理 |
| `loc_location` ★ | 点位 Point（站点内投放位）| `location_no` UK, tenant_id, **site_no**, site_name(冗余), agent_no(冗余), name, spot_desc, cabinet_count, status | 点位管理 |
| `loc_contract` | 进场合同（场地方×站点）| `contract_no` UK, tenant_id, venue_no, venue_name, site_no, site_name, share_rate `DECIMAL(5,4)`, entry_fee, settle_period, start_at, end_at, attach_url, status(ACTIVE/EXPIRED) | 进场合同 |
| `loc_lead` `NEW` | BD 拓展 CRM 商机 | `lead_no` UK, tenant_id, venue_name, contact, stage(NEW/CONTACTED/NEGOTIATING/SIGNED/LOST), owner, expect_sites, next_follow_at, updated_at | BD 拓展 CRM |
| `loc_venue_onboarding` `NEW` | 门店自助进件 | `onboarding_no` UK, tenant_id, venue_name, contact, industry, attach `JSON`, requested_at, status(PENDING/APPROVED/REJECTED), review_at, review_by, review_note, **venue_no**(通过后回填) | 门店 Onboarding |
| `loc_site_lifecycle` `NEW` | 门店生命周期 | site_no, stage(PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED), stage_at, owner_no/owner, **gmv_ltm**(⚠️ 见下), currency；UK(site_no) + 变更走 `loc_site_lifecycle_log` | 门店生命周期 |
| `loc_site_lifecycle_log` `append` `NEW` | 阶段流转留痕 | site_no, from_stage, to_stage, operator, reason, created_at | 门店生命周期 |

- **`[读] 站点坪效 SiteAnalysis`** = `loc_site` ⋈ `ord_rent` 聚合（revenue/orders/turnover/payback_days/cabinet_count），不建表。
- ⚠️ **`loc_site_lifecycle.gmv_ltm` 是本文唯一「聚合值落列」的例外**，与 §1.4「计数列不是列」相抵。保留的理由：生命周期看的是**阶段决策快照**（"退场时该站累计做了多少"），不是实时经营指标，允许与 `ord_rent` 实时聚合有偏差。**由阶段流转时写入，不做定时回刷**；要实时值请查站点坪效。

### 3.5 代理商（`agt_`，ADR-012，菜单：代理商管理 6 叶）
> 代理商 = 运营方**体内经营伙伴，非租户**。设备/点位经 `agent_no` 归属；分润复用 `share_*`(dimension=AGENT)；数据权限维度 `AGENT`。

| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `agt_agent` ★ | 代理商档案 | `agent_no` UK, tenant_id, name, contact `[KMS→pii]`, region_scope `JSON`, default_share_rate `DECIMAL(5,4)`, settle_account, bank_code(→`md_bank`), cabinet_count, status(ENABLED/SUSPENDED) | 代理商档案 |
| `agt_account` `NEW` | 代理登录账号 | `account_no` UK, agent_no, agent_name, login_phone, cred 引用(pb_auth realm=AGENT), data_scope, status(ACTIVE/DISABLED) | 代理账号管理 |
| `agt_assignment` `append` `NEW` | 设备/点位划拨记录 | `assign_no` UK, agent_no, target_type(CABINET/LOCATION/SITE), target_no, action(ASSIGN/REVOKE), operator, created_at | 设备/点位划拨 |
| `agt_commission` `NEW` | 代理分润配置 | `rule_no` UK, agent_no, agent_name, dimension(**GMV/ORDER_COUNT**), rate `DECIMAL(5,4)`(GMV 维度用), **fixed_amount** `DECIMAL(18,2)`(ORDER_COUNT 维度用·单均固定额), currency, mode(CHANNEL_SPLIT/LEDGER), effective_at, status(ACTIVE/INACTIVE) | 分润配置 |

- **`[读] 代理绩效 AgentPerformance`** = `agt_agent` ⋈ `ord_rent`/`dev_cabinet` 聚合（gmv/cabinet_count/online_rate/rank），不建表。
- **代理收益结算**复用 `stl_settlement`(payee_type=AGENT)，不另建表（菜单为跨域深链）。

### 3.6 工单（`wo_`，菜单：工单管理 4 叶）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `wo_order` ★ | 工单 | `wo_no` UK, tenant_id, type(FAULT/REFILL/INSPECT/INSTALL/REMOVE/COMPLAINT/CLEAN), source(ALERT/USER/VENUE/MANUAL), **source_ref**(alarm_no/complaint_no，**UK 幂等**), priority(LOW/MEDIUM/HIGH), cabinet_no, location_no, **agent_no**, **site_no**, status(CREATED/DISPATCHED/ACCEPTED/PROCESSING/DONE/AUDITED/CLOSED ← 7 态全保留，见 **§9A.4**), **close_reason**(RESOLVED/INVALID/DUPLICATE/WITHDRAWN), assignee_no, assignee_name, sla_due_at, description | 工单列表/看板 |
| `wo_dispatch` `append` | 派单记录 | wo_no, assignee_no, strategy(NEAREST/LOAD/MANUAL/GRAB), dispatched_at, action | 派单（页内）|
| `wo_handle` `append` | 现场处理 | wo_no, assignee_no, photos `JSON`, note, part_changed, device_changed, handled_at | 处理与验收（页内）|
| `wo_sla` | SLA 计时（逐单）| wo_no, respond_due_at, resolve_due_at, respond_breached, resolve_breached, escalated_at；UK(wo_no) | SLA 管理 |
| `wo_sla_rule` `NEW` | SLA 规则（配置）| `sla_no` UK, tenant_id, wo_type, response_mins, resolve_mins, escalate_to, active | SLA 管理 |
| `wo_inspection_plan` | 巡检计划 | `plan_no` UK, tenant_id, route `JSON`, frequency, cron, next_at, assignee_no, active | 巡检计划 |

> `wo_order.source_ref` 上的 UNIQUE 是**告警/投诉转工单幂等**的落点（§1.6）——重复点「转工单」返回首次结果，不产生第二张单。

---

## 四、gateway 子域 · 库 `pb_core`（5 表 · access-gateway 进程共享 core，仅用 `gw_*`）

| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `gw_vendor`（全局）| 硬件供应商 | `vendor_code` UK, name, access_mode(TCP/MQTT/HTTP_API), device_count, status(ENABLED/DISABLED) | 供应商接入 |
| `gw_vendor_config` | 供应商接入配置 | vendor_code, tenant_id(空=全局), api_base, app_key, app_secret `[KMS]`, verify_key `[KMS]`, ip_whitelist, params `JSON` | 供应商接入 |
| `gw_device_binding` | 供应商标识↔平台 SN 映射 | sn, vendor_code, cabinet_no, raw_identity, bound_at；UK(vendor_code,raw_identity) | —（SN 规则解析）|
| `gw_command_log` `append`(月) ★ | 指令下发日志（幂等源）| `command_id` UK, tenant_id, sn, cabinet_no, type(EJECT/LOCK/REBOOT/LOCATE/VOICE), slot_index, payload `JSON`, status(PENDING/SENT/ACKED/TIMEOUT/FAILED), retry, order_no, operator, sent_at, confirmed_at | 远程控制·指令记录 / 设备日志 |
| `gw_message_log` `append`(月) | 上下行报文留痕 | sn, cabinet_no, vendor_code, direction(UP/DOWN), event_type, raw(脱敏), parsed_event `JSON`, result(OK/TIMEOUT/FAILED), occurred_at | 设备日志 |

> 运行态（连接会话 `SN→实例`、指令幂等 SETNX、影子）主要在 Redis，DB 仅留可追溯日志与配置。

---

## 五、trade 子域 · 库 `pb_core`（27 表）

### 5.1 订单（`ord_`，菜单：订单管理 7 叶）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `ord_rent` ★ | 租借订单 | `order_no` UK, tenant_id, c_user_no, cabinet_no(借出), return_cabinet_no, powerbank_no, location_no, location_name, **agent_no**, **site_no**, price_plan_no, coupon_no, status(CREATED/DISPENSING/IN_USE/RETURNED/SETTLED/CLOSED/EXCEPTION), rent_start_at, rent_end_at, duration_min, fee_amount, deposit_amount, currency, buyout, **free_reason**(→`usr_free_whitelist.reason`，空=正常单), **waived_amount**(减免额) | 订单列表 / 免费订单 |
| `ord_event_log` `append` | 订单状态流水（时间线）| order_no, from_status, to_status, event, operator, created_at | 订单详情·时间线 |
| `ord_exception` `NEW` | 异常订单 | `exception_no` UK, tenant_id, order_no, type(NOT_EJECTED/NOT_RETURNED/OVERTIME_BUYOUT/DOUBLE_CHARGE), cabinet_no, c_user_no, amount, currency, status(OPEN/HANDLED), handled_by, handled_at, created_at | 异常订单 |
| `ord_complaint` `NEW` | 投诉订单 | `complaint_no` UK, tenant_id, order_no, c_user_no, issue_type(BILLING_DISPUTE/NOT_EJECTED/NOT_RETURNED/DEVICE_FAULT/OTHER), description, **screenshot_url**(截图证据), submitted_at, status(PENDING/PROCESSING/RESOLVED/REJECTED), handler_name, handled_at, resolution(REFUND/COMPENSATE/REJECT/EXPLAINED), resolution_note, **wo_no**(转工单) | 投诉订单 |
| `ord_refund` ★ `NEW` | 退款审批单（业务侧）| `refund_no` UK, tenant_id, order_no, c_user_no, amount, currency, reason, applicant_name, applied_at, status(PENDING/APPROVED/REJECTED/EXECUTED/FAILED), auditor_name, audited_at, reject_reason, **idempotency_key** UK, **psp_txn_no**, pay_refund_no(→`pay_refund`) | 退款记录 |
| `ord_reservation` `NEW` | 预约订单 | `reservation_no` UK, tenant_id, c_user_no, type(**BORROW/RETURN**), site_no, site_name, cabinet_no, reserved_from, reserved_to, hold_fee, currency, status(PENDING/FULFILLED/EXPIRED/CANCELLED), order_no(履约后回填) | 预约订单 |
| `ord_deposit` `NEW` | 押金与欠费 | `deposit_no` UK, tenant_id, order_no, c_user_no, amount, currency, status(HELD/RELEASED/BOUGHT_OUT/**ARREARS**), **arrears_amount**, released_at, created_at | 押金与欠费 |

> **`ord_refund` vs `pay_refund` 的分工**（v2 明确，v1 混为一谈）：`ord_refund` 是**业务审批单**（客服申请→财务审批，带幂等键与驳回原因）；`pay_refund` 是**渠道退款引用**（nearpay 侧的执行凭证）。审批通过才生成 `pay_refund`，1:1 关联。这就是「我们多审批链与幂等键」的落点。
> **免费订单不建表**：`ord_rent` 加 `free_reason` + `waived_amount` 两列，免费订单 = `free_reason IS NOT NULL` 的筛选视图。避免同一笔租借在两张表各存一份。

### 5.2 计费（`price_`，菜单：计费定价 3 叶）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `price_plan` ★ | 计费模板 | `plan_no` UK, tenant_id, name, free_minutes, unit_minutes, unit_price, cap_daily, cap_total(封顶/买断价), currency, scope, status(ACTIVE/DISABLED) | 计费模板 |
| `price_rule` `NEW` | 差异化定价 | `rule_no` UK, tenant_id, plan_no, dimension(SCENE/LOCATION/SITE), match_ref, scene, location_name, free_mins, unit_price, day_cap, priority, currency | 差异化定价 |
| `price_schedule` `NEW` | 活动/时段价 | `rule_no` UK, tenant_id, name, period(时段/节假日表达式), **multiplier `DECIMAL(6,4)`**（倍率可 >1，是 §1.5「比率 0..1」的例外）, active | 活动/时段价 |

> 计费模板改动**仅影响新订单**：`ord_rent.price_plan_no` 指向下单当刻的模板，历史单不重算。

### 5.3 支付（`pay_`）——**委托 neargo nearpay，延后集成（ADR-005）**
> powerbank 不落渠道/PSP 明文密钥；支付执行在 nearpay，本域只存**支付引用 + 状态镜像**驱动订单。MVP 用 `PaymentPort` Stub。

| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `pay_order` ★ | 支付引用 | `pay_no` UK, tenant_id, order_no, c_user_no, type(DEPOSIT/RENT/BUYOUT/**RECHARGE**/**MEMBERSHIP**), amount, currency, channel_code, status(INIT/PAYING/PAID/FAILED/CLOSED), **nearpay_txn_no**, paid_at | 订单详情（页内）|
| `pay_auth` ★ | 免押编排状态 | `auth_no` UK, tenant_id, order_no, c_user_no, freeze_amount, captured_amount, status(FROZEN/CAPTURED/RELEASED), **nearpay_auth_no**, expire_at | 押金与欠费 |
| `pay_refund` | 渠道退款引用 | `refund_no` UK, tenant_id, pay_no, ord_refund_no, amount, reason, status(INIT/SUCCESS/FAILED), **nearpay_refund_no** | 退款记录 |
| `pay_event_log` `append` | nearpay 结果事件留痕（幂等）| source(NEARPAY), ref_no, event_type, raw `JSON`, processed, received_at；**UK(ref_no,event_type)** | —（回调幂等）|
| `pay_channel` `NEW` | 支付渠道配置 | `channel_code` UK, channel_name/_en/_ar, mode(**DELEGATED**(委托 nearpay)/**DIRECT**), status, api_base, merchant_id, **api_key_masked** + **api_secret_masked**(调用密钥 + 验签密钥两把，对齐 `gw_vendor_config`；明文落 KMS/vault **不入库**), updated_at；国家/币种/能力见 `pay_channel_scope`（§1.7） | 支付渠道 |

> **`pay_channel` 的 API 落在 `/api/platform/payment-channels`**（承载在「系统设置」页），表前缀仍归 trade —— 这是本文里唯一一处「表子域 ≠ API 前缀」，因为它本质是支付域配置，只是被运营端归到系统设置菜单下。
> **比竞品清晰在哪**：对方 7 个支付渠道各占一个菜单；我们一张表 + 一页 + 配置抽屉，密钥列只出掩码。

### 5.4 账务 · 分润（`acct_` / `share_`，菜单：财务管理·分润与结算/平台账）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `acct_account` ★ | 账户 | `account_no` UK, tenant_id, owner_type(PLATFORM/VENUE/AGENT/USER), owner_no, balance, frozen, currency | 账务分录 |
| `acct_ledger` `append`(月) | 复式记账分录 | `entry_no` UK, tenant_id, voucher_no, order_no, account_no, account(科目名), direction(**DEBIT/CREDIT**), amount, currency, summary, biz_type, biz_no, created_at | 账务分录 |
| `share_rule` | 分润规则 | `rule_no` UK, tenant_id, dimension(VENUE/AGENT), payee_no, payee_name, mode(CHANNEL_SPLIT/LEDGER), rate `DECIMAL(5,4)`, formula `JSON`, priority | 分润规则 |
| `share_record` | 分润记录（逐单）| `record_no` UK, tenant_id, order_no, dimension(VENUE/AGENT), payee_type, payee_no, payee_name, amount, rate, currency, mode, status(PENDING/DONE), settle_no(回填), created_at | 分润明细 |

- **`[读] 分润统计 ShareSummary`** = `share_record` 按 (dimension, payee_no, period) 聚合（order_count/gmv/share_amount/settled_amount/pending_amount），不建表。
  > **比竞品清晰在哪**：对方按「运营商/商户」切两套表，我们**一张表 + `dimension` 维度切换器**，口径天然自洽。

### 5.5 结算 · 提现 · 对账 · 发票（`stl_` / `recon_` / `fin_`）
| 表 | 说明 | 关键列 | 菜单叶 |
|----|------|-------|---|
| `stl_settlement` ★ | 结算单 | `settle_no` UK, tenant_id, payee_type(VENUE/AGENT), payee_no, payee_name, period, total_amount, currency, status(GEN/CONFIRMED/PAID) | 结算单 / 代理收益结算 |
| `stl_settlement_detail` `NEW` | 结算明细 | settle_no, ref_type(ORDER/SHARE), ref_no, amount | 结算单 |
| `stl_withdrawal` | 提现 | `withdraw_no` UK, tenant_id, account_no, payee_type(VENUE/AGENT), payee_no, **payee_name**, amount, **fee**, currency, **bank_code**, status(APPLY/AUDIT/PAYING/PAID/FAILED), applied_at, applicant_no, **auditor_no**/**auditor_name**, **audited_at**, **reject_reason**, paid_at | 提现审核 |
| `recon_task` `NEW` | 对账任务 | `batch_no` UK, tenant_id, channel, period, bill_date, nearpay_total, ledger_total, diff, currency, status(MATCHED/DIFF) | 对账 |
| `recon_diff` `NEW` | 对账差错 | batch_no, pay_no, diff_type, detail `JSON`, resolved | 对账 |
| `fin_invoice` `NEW` | 发票（运营侧开票管理）| `invoice_no`(前缀 `INV`) UK, tenant_id, payee_type, payee_no, payee_name, amount, **vat_trn**, currency, status(DRAFT/ISSUED/VOID), issued_at, file_url；关联订单走 `fin_invoice_item`（§1.7），**不落 `order_nos JSON`** | 发票 |

> **提现四件套（`fee`/`auditor_name`/`audited_at`/`reject_reason`）是资金审批合规下界**：驳回必须留原因，审批人必须留痕，手续费口径来自 `sys_biz_rule(WITHDRAW)`。派生列「实际到账」= `amount - fee`，不落库。

---

## 六、user 子域 · 库 `pb_core`（26 表）

### 6.1 C端用户与风控（菜单：用户管理 7 叶）
| 表 | 说明 | 关键列 | 菜单叶 / C端 |
|----|------|-------|---|
| `usr_user` ★ | C端用户 | `c_user_no` UK, tenant_id, openid(冗余), unionid(冗余), nickname, avatar, email, status, credit_score, registered_at | 用户列表 / C-AC-04 |
| `usr_identity` ★ | 多渠道身份绑定 | c_user_no, tenant_id, provider(WECHAT_MP/WECHAT_OA/APPLE/GOOGLE/PHONE), provider_uid, union_key, bound_at；UK(tenant_id,provider,provider_uid)；IDX(tenant_id,union_key) | C-AC-01 |
| `usr_credit` `改造` | 信用/风控 | `risk_no` UK, c_user_no, tenant_id, score, **risk_level**(HIGH/MEDIUM/LOW), reason, flagged_at | 风控用户 / C-ME-02 |
| `usr_blacklist` `NEW` | 黑名单 | `blacklist_no` UK, tenant_id, c_user_no, reason, blacklisted_at, blacklisted_by, released_at, released_by, status(ACTIVE/RELEASED) | 黑名单 |
| `usr_free_whitelist` `NEW` | 免费用户白名单 | `whitelist_no` UK, tenant_id, c_user_no, reason(**INTERNAL_TEST/VIP/BD_DEMO/MERCHANT_SELF**), quota_type(UNLIMITED/TIMES/AMOUNT), quota_value, used_value, valid_from, valid_to, granted_by, status(ACTIVE/EXPIRED/REVOKED) | 免费用户白名单 |

> **白名单归用户域而非订单域**（对方放订单域）：它本质是**用户属性**，且 `reason` 强制标注用途、`quota_*` 限额可控。免费订单由 `ord_rent.free_reason` 回指本表。
> `usr_credit` v1 只有 `score/blacklisted/reason`，v2 拆出独立 `usr_blacklist`（需要 `released_at` 与解除留痕），并给 `usr_credit` 补 `risk_level`。

### 6.2 钱包 · 充值（菜单：用户资产 / 财务·用户账）
| 表 | 说明 | 关键列 | 菜单叶 / C端 |
|----|------|-------|---|
| `usr_wallet` | 钱包 | `wallet_no` UK, tenant_id, c_user_no, balance, **gift_balance**(赠金), deposit_amount, frozen_amount, currency | 钱包 / C-WA-01 |
| `usr_wallet_txn` `append`(月) | 钱包流水 | `txn_no` UK, wallet_no, c_user_no, type(**RECHARGE/SPEND/REFUND/BONUS**), direction, title, amount(带符号), currency, biz_type, biz_no, created_at | 钱包 / C-WA-05 |
| `usr_recharge_pkg` `NEW` | 充值套餐 | `package_no` UK, tenant_id, name, pay_amount, **gift_amount**, currency, **markets**(适用市场,多选), **valid_days**(有效期), sort_no, status(ENABLED/DISABLED) | 充值套餐 / C-WA-02 |
| `usr_recharge_order` `NEW` | 充值订单 | `recharge_no` UK, tenant_id, c_user_no, nickname, package_no, pay_amount, gift_amount, credit_amount, currency, channel_code, status(PENDING/PAID/FAILED/REFUNDED), created_at, paid_at, **psp_txn_no**（前端字段名 `psgTxnNo` 是笔误，全库统一 `psp_`） | 充值订单 |

- **`[读] 用户价值画像`**（`order_count`/`order_amount`/`recharge_count`/`recharge_amount`）= `ord_rent` + `usr_recharge_order` 按 `c_user_no` 聚合，挂在钱包页展示，不落冗余列（避免与订单表不自洽）。

### 6.3 会员 · 券 · 营销（菜单：营销管理 8 叶）
| 表 | 说明 | 关键列 | 菜单叶 / C端 |
|----|------|-------|---|
| `mbr_plan` | 会员方案 | `plan_no` UK, tenant_id, name, price, period, card_type, rights `JSON`, status | 会员/次卡 / C-MB-01 |
| `usr_membership` | 用户会员 | `mbr_no` UK, tenant_id, c_user_no, plan_no, level(SILVER/GOLD/PLATINUM), points, start_at, end_at, auto_renew, status | 会员/次卡 / C-MB-03 |
| `coupon_tpl` | 券模板 | `tpl_no` UK, tenant_id, name, type(CUT/DISCOUNT), value, threshold, valid_rule `JSON`, stock, issued, status(ACTIVE/PAUSED) | 优惠券 / C-CP-01 |
| `usr_coupon` | 用户券 | `coupon_no` UK, tenant_id, c_user_no, tpl_no, status(UNUSED/USED/EXPIRED), used_order_no, expire_at | 优惠券 / C-CP-02 |
| `mkt_notice` `NEW` | 公告管理 | `notice_no` UK, tenant_id, title/title_en/title_ar, content/content_en/content_ar, type(SYSTEM/PROMO/MAINTENANCE), **pinned**, start_at, end_at, status(DRAFT/PUBLISHED/OFFLINE), published_by | 公告管理 / C端首页公告条 |
| `mkt_campaign` `NEW` | 活动 | `campaign_no` UK, tenant_id, name, kind, rule `JSON`, status(DRAFT/RUNNING/ENDED), start_at, end_at | 活动 / C-CP-04 |
| `mkt_push` `NEW` | 推送触达 | `push_no` UK, tenant_id, title, channel(APP_PUSH/SUBSCRIBE), audience `JSON`, sent_count, status(DRAFT/SENT), sent_at | 推送触达 / C-MS-02 |
| `mkt_referral` `NEW` | 邀请裂变 | `invite_no` UK, tenant_id, inviter_no, invitee_no, reward, currency, status(PENDING/REWARDED), created_at | 邀请裂变 / C-SH-01/03 |

> **`mkt_notice` 是 v1 的整体遗漏**：C 端首页 Hub 已有公告条，却没有发布口。v2 补齐，且三语 + 生效期 + 置顶（对方是单语公告）。

### 6.4 广告（`ad_`，P2 · 设备投屏，菜单：广告经营 3 叶）
| 表 | 说明 | 关键列 |
|----|------|-------|
| `ad_slot` | 广告位（挂机柜）| `slot_no` UK, tenant_id, cabinet_no, position(SCREEN/BODY), size, status(IDLE/OCCUPIED) |
| `ad_advertiser` | 广告主 | `advertiser_no` UK, tenant_id, name, contact |
| `ad_campaign` | 广告活动 | `ad_no` UK, tenant_id, advertiser_no, advertiser, creative, budget, targeting `JSON`(region/site/scene), start_at, end_at, status(DRAFT/RUNNING/ENDED) |
| `ad_creative` | 广告创意 | `creative_no` UK, ad_no, media_url, duration, mime |
| `ad_placement` | 投放排期 | `placement_no` UK, ad_no, creative_no, slot_no, schedule `JSON`, status |
| `ad_impression` `append`(月) | 曝光统计 | `delivery_no`, ad_no, slot_no, cabinet_no, impressions, plays, stat_date | 

> 广告收入未来可纳入分润（`share_record.biz_type=AD`），首期只建表不接分润。

### 6.5 客服（`cs_`，菜单：客服管理 4 叶）
| 表 | 说明 | 关键列 | 菜单叶 / C端 |
|----|------|-------|---|
| `cs_ticket` `NEW` | 报障受理 | `ticket_no` UK, tenant_id, c_user_no, order_no, cabinet_no, problem_no(→`md_problem`), issue, channel, status(OPEN/PROCESSING/CLOSED), handler_no, **wo_no**, **refund_no**, created_at | 报障受理 / C-CS-01/02 |
| `cs_session` `NEW` | 客服会话 | `session_no` UK, tenant_id, c_user_no, agent_name, last_message, status(ACTIVE/CLOSED), updated_at | 客服会话 / C-CS-04 |
| `cs_message` `append` `NEW` | 会话消息 | session_no, sender_type(USER/AGENT), content, attach `JSON`, created_at | 客服会话 |

> `cs_ticket` 是 C 端 `POST /mp/user/report` 的落点，同时是「报障 → 转工单 / 转退款」的枢纽（`wo_no` / `refund_no` 两个出口）。

### 6.6 C 端专属（v2 新增 —— v1 完全缺失，c-app 已在调）
| 表 | 说明 | 关键列 | C端编号 |
|----|------|-------|---|
| `usr_favorite` `NEW` | 收藏门店 | c_user_no, site_no, created_at；UK(c_user_no,site_no) | c-app `/mp/user/favorites` |
| `usr_message` `NEW` | 站内消息中心 | `message_no` UK, tenant_id, c_user_no, type, title, body, **is_read**（`read` 是 MySQL 保留字）, read_at, created_at | C-MS-03 |
| `usr_push_token` `NEW` | Push token 注册 | c_user_no, platform(APNS/FCM/UNIPUSH), token, device_id, active；UK(platform,token) | C-MS-01 |
| `usr_notify_pref` `NEW` | 通知偏好 | c_user_no, category, enabled, quiet_start, quiet_end, lang；UK(c_user_no,category) | C-MS-04 |
| `usr_invoice_title` `NEW` | 发票抬头 | `title_no` UK, c_user_no, type(PERSONAL/COMPANY), title, **vat_trn**, is_default | C-IV-02 |
| `usr_invoice` `NEW` | C端开票申请 | `invoice_no`(前缀 **`UINV`**，与运营侧 `INV` 分开防撞) UK, tenant_id, c_user_no, title_no, amount, currency, status(APPLIED/ISSUED/REJECTED), file_url, applied_at；关联订单走 `fin_invoice_item` | C-IV-01/03 |
| `usr_logoff` `NEW` | 注销申请（PDPL 冷静期）| c_user_no, requested_at, cooling_until, status(PENDING/CANCELLED/DONE), purged_at | C-AC-05 |
| `usr_consent` `append` `NEW` | 同意与撤回留痕（PDPL）| tenant_id, c_user_no, agreement_code, **agreement_version**（不叫 `version` —— 与 BaseEntity 乐观锁列撞名）, action(GRANT/REVOKE), lang, ip, created_at | C-AC-06 |

> **PDPL 硬要求的三件套**：`usr_logoff`（可注销 + 冷静期 + 到期清除）、`usr_consent`（明示同意与撤回可举证）、数据导出（走 `usr_*` + `pii_user` 的导出作业，不建表）。v1 只在文字里提 PDPL，未落表 —— v2 补齐。

---

## 七、库 `pb_pii`（个人数据 · 独立 KMS · PDPL · 1 表）

| 表 | 说明 | 关键列 |
|----|------|-------|
| `pii_user` | C端/伙伴敏感信息 | subject_type(C_USER/EMPLOYEE/VENUE/AGENT), subject_no, tenant_id, phone `[KMS]`, real_name `[KMS]`, id_no `[KMS]`, email `[KMS]`；UK(subject_type,subject_no) |

> v1 只覆盖 C 端用户；v2 用 `subject_type` 统一承载**员工手机、场地方联系人、代理联系人**——它们同样是个人数据，此前散落在 `iam_employee.phone` / `loc_venue.contact` / `agt_agent.contact` 明文列。业务表保留**掩码值**供列表展示，明文只在 `pb_pii`，经脱敏接口访问。

## 八、库 `pb_auth`（凭据 · 独立 KMS · 复用 neargo-auth-core · 1 表）

`cred_credential`（`realm` 区分 **STAFF / AGENT / CONSUMER** 三池）——结构由 auth-core 定义，**仅 auth-core 访问**；业务域仅持 `user_id`/`c_user_no`/`account_no` 逻辑引用，不落凭据。

> v1 写「员工/消费者」双池；v2 补 **AGENT 池**（`agt_account` 需要独立登录，见 §3.5）。

---

## 九、ER 关键关系（逻辑，无物理 FK）

```
场地本体:  loc_venue 1─* loc_site 1─* loc_location 1─* dev_cabinet 1─* dev_slot *─1 dev_powerbank
归属:      agt_agent 1─* loc_site / dev_cabinet / (冗余到 ord_rent.agent_no / wo_order.agent_no)
用户:      usr_user 1─* usr_identity（unionid 归并）· 1─1 usr_wallet · 1─* usr_coupon / usr_membership
交易主链:  usr_user 1─* ord_rent 1─* pay_order / pay_auth
           ord_rent 1─0..1 ord_deposit · 1─* ord_event_log · 1─* share_record
退款链:    ord_rent 1─* ord_refund（审批单，幂等键）1─1 pay_refund（渠道引用）
告警链:    dev_cabinet 1─* dev_alarm 1─* dev_alarm_notice
           dev_alarm *─1 dev_alarm_code（字典即处置预案）
           dev_alarm 0..1─1 wo_order  ← UK(wo_order.source_ref) 保幂等
投诉链:    ord_rent 1─* ord_complaint 0..1─1 wo_order（同一幂等机制）
报障链:    cs_ticket 0..1─1 wo_order · 0..1─1 ord_refund   ← C端报障的两个出口
资金链:    share_record *─1 stl_settlement 1─* stl_settlement_detail
           acct_account 1─* acct_ledger · acct_account 1─* stl_withdrawal
权限:      iam_role 1─* iam_role_perm · iam_data_scope(subject=ROLE|EMPLOYEE) 承载两级数据权限
南向:      gw_command_log *─1 dev_cabinet · gw_command_log 0..1─1 ord_rent（弹出指令）
```

---

## 九·A 状态机（业务梳理定稿 · 2026-07-29）

> 建 DDL 时暴露出前端存在两套不兼容枚举、多处「定义 N 态只产出 M 态」。本节按**实际业务流程**逐个梳理并定稿，**结论即 SSOT**，前端类型与后端状态机一律对齐本节。

### 9A.1 充电宝 `dev_powerbank.status`（**两套合一 → 7 态**）

**问题根因**：前端两套枚举实为**两个正交的轴被混用** ——
- `PowerbankStatus`（6 值 `IN_STOCK/DEPLOYED/IN_USE/RETURNED/SCRAP/LOST`）= **资产生命周期轴**
- `Powerbank.status`（4 值 `IN_CABINET/RENTED/FAULT/RETIRED`）= **位置/占用轴 + 生命周期混杂**

**业务判断**：充电宝的「在哪」已经由 `cabinet_no` + `slot_index` 两列精确表达，**不需要再用状态位重复表达位置**。真正需要状态机管的只有一件事：**这颗充电宝当前处于资产生命周期的哪一环、能不能借出**。故合为单一状态机：

| 状态 | 业务含义 | 可借 | 备注 |
|---|---|:---:|---|
| `IN_STOCK` | 入库未投放（在仓库） | 否 | `cabinet_no` 为空 |
| `IN_CABINET` | 在仓可借（已投放到机柜） | **是** | `cabinet_no`+`slot_index` 有值 |
| `RENTED` | 借出中（用户持有） | 否 | 挂 `ord_rent.powerbank_no` |
| `FAULT` | 故障待修（自检异常/坏机归还） | 否 | 触发 `dev_alarm` + 工单 |
| `LOST` | 丢失（超时未归还，待追偿） | 否 | **半终态，可回收** |
| `SOLD` | 买断（用户付费持有） | 否 | **终态** |
| `SCRAP` | 报废 | 否 | **终态** |

**废弃 3 个值，理由逐条**：
- `DEPLOYED` —— 「投放」是动作，投放的结果就是 `IN_CABINET`，两者语义重合。
- `RETURNED` —— 「归还」是**事件不是状态**，归还落仓后即 `IN_CABINET`；订单侧已有 `ord_rent.status=RETURNED` 承载归还语义，资产侧再存一份必然漂移。
- `RETIRED` —— 与 `SCRAP` 同义，择一。

**新增 `SOLD`**：v1 两套枚举都没有买断终态，但买断在系统里到处都是（`ord_rent.buyout`、`price_plan.cap_total` 买断价、`ord_exception.OVERTIME_BUYOUT`、`ord_deposit.BOUGHT_OUT`）。买断后充电宝归用户所有，**必须离开资产池，且不能记成 `LOST`** —— `LOST` 是「未付费且待追偿」，`SOLD` 是「已付费正常出表」，两者的财务处理完全不同（前者计损失，后者计收入）。

**状态转移**：
```
IN_STOCK ──投放/补货──▶ IN_CABINET ──借出──▶ RENTED
                            ▲                  │
                            └──── 归还(任意柜) ─┤
                                               ├── 超时未归还 ──▶ LOST ──失而复得──▶ IN_CABINET
                                               ├── 买断付费 ────▶ SOLD   (终态)
                                               └── 坏机归还 ────▶ FAULT
IN_CABINET ──自检/上报故障──▶ FAULT ──维修回仓──▶ IN_STOCK
FAULT / IN_STOCK / IN_CABINET ──报废──▶ SCRAP (终态)
```

**存量迁移映射**：`DEPLOYED→IN_CABINET` · `IN_USE→RENTED` · `RETURNED→IN_CABINET` · `RETIRED→SCRAP`。

### 9A.2 机柜 `dev_cabinet.status`（3 → **4 态**）

`DEPLOYED / FAULT / RETIRED` **缺 `IN_STOCK`**。理由：「库存调拨」tab 管的就是**未投放机柜**（`inv_stock.item_type=CABINET`），没有 `IN_STOCK` 就无法把「仓库里的新机柜」与「已投放在点位的机柜」区分开，调拨单的起止状态也无从表达。

定稿 4 态：`IN_STOCK`（入库未投放，`location_no` 为空）→ `DEPLOYED`（已投放）→ `FAULT`（故障停用）→ `RETIRED`（退役，终态）。

> ⚠️ `online_status`（`ONLINE/OFFLINE`）是**独立正交轴**，表示通信可达性，**不并入 `status`**。一台 `DEPLOYED` 的机柜可以 `OFFLINE`（断网），一台 `FAULT` 的机柜也可以 `ONLINE`（能通信但仓门卡死）。实时监控看的是两轴的组合。

### 9A.3 租借订单 `ord_rent.status`（**7 态全部保留**）

前端 mock 从未产出 `DISPENSING`，但**这不是枚举冗余，是 mock 数据覆盖不全**：

- `CREATED`（已下单待弹出）与 `DISPENSING`（弹出指令已下发、等设备确认）**必须分开** —— C端「等待弹出」页（`C-RT-04`）渲染的就是 `DISPENSING`，且弹出超时兜底（`C-RT-05` 撤销预授权、不产生费用）只能从这个态发起。合并后无法区分「支付成功但指令还没发」与「指令发了但设备没响应」，兜底逻辑就没有落点。
- `RETURNED`（已归还待结算）与 `SETTLED`（已结算）**必须分开** —— capture 请款是异步且可能失败挂账（`C-PAY-05` 欠费挂账、下次借出前结清）。归还与结算不是原子的，合并会让「已还但没扣到钱」的单无处安放。

**要求**：ops-web mock 补出 `DISPENSING` 样本，否则前端「等待弹出」链路永远测不到。

### 9A.4 工单 `wo_order.status`（**7 态全部保留** + 补 `close_reason`）

前端 mock 从未产出 `ACCEPTED` / `AUDITED`，同样是覆盖不全而非冗余：

- `ACCEPTED`（运维接单）是**响应 SLA 的度量终点** —— `wo_sla.respond_due_at` / `respond_breached` 判定的就是「派单到接单」这段。删掉 `ACCEPTED`，响应 SLA 就没有计时终点，整个 SLA 管理叶失去意义。
- `AUDITED`（验收）与 `CLOSED`（归档）分开 —— `AUDITED` 是**判定**（验收不通过可退回 `PROCESSING` 返工），`CLOSED` 是**终态**。而且 `workorder:wo:audit` 权限码单独存在，本身就隐含「完工人 ≠ 验收人」的角色分离（防虚假完工，尤其换件/换设备类工单）。

**新增列 `close_reason`**：告警误报开的单需要「无效关闭」，但**不新增 `CANCELLED` 态**（会让状态机多一条平行终态线）。改为 `CLOSED` + `close_reason`（`RESOLVED`(正常完结) / `INVALID`(误报) / `DUPLICATE`(重复单) / `WITHDRAWN`(撤单)）。

**要求**：ops-web mock 补出 `ACCEPTED` / `AUDITED` 样本。

### 9A.5 状态机实现落点

| 状态机 | 组件 | 现状 |
|---|---|---|
| `ord_rent` | `trade/OrdStateMachine` | ✅ 已实现 |
| `wo_order` | `wo/WoStateMachine` | ✅ 已实现（需补 `ACCEPTED`/`AUDITED` 迁移与 `close_reason`）|
| `dev_powerbank` | `dev/PowerbankStateMachine` | ⬜ **待建**（本节定稿后实现）|
| `dev_cabinet` | 同上 | ⬜ 待建 |

> 非法迁移一律返 **409**，不静默忽略 —— 与已实现的 `WoStateMachine` 行为一致。

---

## 十、分区 / 归档 / 索引基线

| 类别 | 表 | 策略 |
|------|----|------|
| 高频遥测 | `dev_heartbeat` `gw_message_log` | 按 `created_at` **月分区**，热 3 月，冷数据归档对象存储后 DROP PARTITION |
| 只增流水 | `acct_ledger` `usr_wallet_txn` `notify_log` `ad_impression` | 月分区，保留 **7 年**（财务/合规），不删 |
| 审计 | `audit_log` `usr_consent` | 月分区，**WORM**（仅 INSERT，无 UPDATE/DELETE 授权），保留 7 年 |
| 指令 | `gw_command_log` | 月分区，热 3 月；`command_id` 唯一索引常驻 |
| 数据权限热路径 | `ord_rent` `wo_order` `dev_cabinet` `dev_alarm` `loc_site` | 联合索引 **`(tenant_id, agent_no, status, created_at)`** —— 数据范围过滤 + 列表默认排序一次走完 |
| 属主查询（C端）| `ord_rent` `usr_wallet_txn` `usr_coupon` | `(tenant_id, c_user_no, created_at)` |
| 幂等 | `ord_refund.idempotency_key` `wo_order.source_ref` `gw_command_log.command_id` `pay_event_log(ref_no,event_type)` | UNIQUE，见 §1.6 |

---

## 十一、覆盖核对表（98 菜单叶 + C端 17 模块 → 表）

> 用法：**开发任一菜单叶前先查此表**确认落点；新增叶必须先在此表补行。`[读]` = 读模型无物理表。

### 11.1 运营端 98 叶 → 表

| 模块 | 叶 | 落表 |
|---|---|---|
| 经营看板 | 经营总览 / 收入趋势 / 实时告警 / 待办中心 / 排名榜单 | `[读]` 全域聚合（`ord_rent`/`dev_cabinet`/`dev_alarm`/`wo_order`/`ord_refund`/`stl_withdrawal`）|
| 设备管理 | 设备台账 | `dev_cabinet` |
| | 充电宝管理 | `dev_powerbank` |
| | 实时监控 | `[读]` `dev_cabinet`⋈`dev_shadow` |
| | 远程控制·指令记录 | `gw_command_log` |
| | 设备日志 | `[读]` `gw_command_log` ∪ `gw_message_log` |
| | 库存调拨 | `inv_warehouse` `inv_stock` `inv_transfer` `inv_transfer_item` |
| | 固件 OTA | `dev_ota_release` `dev_ota_rollout` `dev_ota_task` |
| | 设备编码 | `dev_code_batch` |
| 告警管理 | 告警记录 / 告警通知 / 告警代码 / 通知规则 | `dev_alarm` `dev_alarm_notice` `dev_alarm_code` `dev_alarm_rule` |
| 工单管理 | 工单列表 / 看板 | `wo_order` `wo_dispatch` `wo_handle` |
| | SLA 管理 | `wo_sla` `wo_sla_rule` |
| | 巡检计划 | `wo_inspection_plan` |
| 站点与点位 | 站点 / 点位 / 场地方 / 进场合同 | `loc_site` `loc_location` `loc_venue` `loc_contract` |
| | 站点坪效 | `[读]` `loc_site`⋈`ord_rent` |
| | 门店 Onboarding | `loc_venue_onboarding` |
| | 门店生命周期 | `loc_site_lifecycle` `loc_site_lifecycle_log` |
| | BD 拓展 CRM | `loc_lead` |
| 代理商管理 | 档案 / 账号 / 划拨 / 分润配置 | `agt_agent` `agt_account` `agt_assignment` `agt_commission` |
| | 代理收益结算（深链）| `stl_settlement`(payee_type=AGENT) |
| | 代理绩效 | `[读]` `agt_agent`⋈`ord_rent`/`dev_cabinet` |
| 订单管理 | 订单列表 / 详情时间线 | `ord_rent` `ord_event_log` |
| | 预约订单 | `ord_reservation` |
| | 异常订单 | `ord_exception` |
| | 投诉订单 | `ord_complaint` |
| | 退款记录 | `ord_refund`（+ `pay_refund` 执行）|
| | 押金与欠费 | `ord_deposit` `pay_auth` |
| | 免费订单 | `ord_rent.free_reason/waived_amount` 筛选 |
| 计费定价 | 计费模板 / 差异化 / 时段价 | `price_plan` `price_rule` `price_schedule` |
| 财务管理 | 分润规则 / 明细 | `share_rule` `share_record` |
| | 分润统计 | `[读]` `share_record` 按 dimension 聚合 |
| | 结算单 | `stl_settlement` `stl_settlement_detail` |
| | 账务分录 | `acct_account` `acct_ledger` |
| | 对账 | `recon_task` `recon_diff` |
| | 发票 | `fin_invoice` |
| | 提现审核 | `stl_withdrawal`（规则取 `sys_biz_rule(WITHDRAW)`）|
| | 用户钱包（深链）/ 充值订单 | `usr_wallet` `usr_recharge_order` |
| 用户管理 | 用户列表 | `usr_user` |
| | 风控用户 / 黑名单 / 白名单 | `usr_credit` `usr_blacklist` `usr_free_whitelist` |
| | 会员/次卡 / 钱包 / 充值套餐 | `mbr_plan` `usr_membership` / `usr_wallet` `usr_wallet_txn` / `usr_recharge_pkg` |
| 营销管理 | 公告 / 券 / 活动 / 推送 / 裂变 | `mkt_notice` `coupon_tpl` `usr_coupon` `mkt_campaign` `mkt_push` `mkt_referral` |
| | 广告位 / 广告活动 / 投放曝光 | `ad_slot` `ad_advertiser` `ad_campaign` `ad_creative` `ad_placement` `ad_impression` |
| 客服管理 | 报障受理 / 客服会话 | `cs_ticket` `cs_session` `cs_message` |
| 数据报表 | 设备分析 / 点位坪效 / 财务 / 大屏 / 自定义 / 消费者 | `[读]` 全部为聚合，**不建业务表**；如需提速引入 `rpt_*` 物化表（P2 再定）|
| 员工与权限 | 员工 / 组织 / 角色 / 审计 / 绩效 | `iam_employee` `iam_dept` `iam_role`+`iam_role_perm`+`iam_data_scope` `audit_log` `iam_staff_perf` |
| 系统设置 | 供应商接入 | `gw_vendor` `gw_vendor_config` |
| | 支付渠道 | `pay_channel` |
| | 通知模板 / 发送记录 / 触达拉黑 | `notify_template` `notify_log` `notify_blacklist` |
| | 业务规则 / 登录设置 / 应用版本 | `sys_biz_rule` `sys_login_setting` `sys_app_version` |
| | 参数字典 / 地区库 / 银行 / 问题 / 系统参数 | `dict_item` `md_region` `md_bank` `md_problem` `sys_param` |
| | 税率与发票 / 多国家市场 / OpenAPI | `sys_tax_setting` `md_market_country` `openapi_app` |

### 11.2 C端 17 模块 → 表

| 模块 | 表 |
|---|---|
| 1 账户与登录 | `usr_user` `usr_identity` `pii_user` `cred_credential` `usr_logoff` `usr_consent` `sys_login_setting` |
| 2 找柜与地图 | `loc_site` `dev_cabinet` `dev_shadow` `usr_favorite` |
| 3 扫码借出 | `ord_rent` `gw_command_log` `price_plan` |
| 4 免押与授权 | `pay_auth` `usr_credit` |
| 5 支付收银 | `pay_order` `pay_channel` `pay_event_log` |
| 6 使用中 | `ord_rent` `ord_event_log` |
| 7 归还与结算 | `ord_rent` `pay_auth` `share_record` |
| 8 订单与账单 | `ord_rent` `ord_event_log` `ord_deposit` |
| 9 钱包与押金 | `usr_wallet` `usr_wallet_txn` `usr_recharge_pkg` `usr_recharge_order` |
| 10 会员与权益 | `mbr_plan` `usr_membership` |
| 11 优惠券与营销 | `coupon_tpl` `usr_coupon` `mkt_campaign` `mkt_notice` |
| 12 报障与客服 | `cs_ticket` `cs_session` `cs_message` `md_problem` `ord_refund` |
| 13 发票 | `usr_invoice_title` `usr_invoice` `sys_tax_setting` |
| 14 消息触达 | `usr_message` `usr_push_token` `usr_notify_pref` `notify_log` `notify_blacklist` |
| 15 邀请与分享 | `mkt_referral` |
| 16 个人中心与设置 | `usr_user` `usr_credit` `usr_notify_pref` `sys_app_version` |
| 17 蓝牙近场借还 | `gw_command_log`（补单幂等）· 无新表 |

---

## 十二、v1 → v2 变更清单

### 12.1 新增 68 表（实数，逐张已落 `ddl/pb_core-v2-*.sql`）
`iam_staff_perf` `notify_log` `notify_blacklist` `md_bank` `md_problem` `md_market_country` `sys_biz_rule` `sys_login_setting` `sys_app_version` `sys_tax_setting` · `dev_code_batch` `dev_alarm_notice` `dev_alarm_code` `dev_alarm_rule` · `inv_transfer` `inv_transfer_item` · `loc_lead` `loc_venue_onboarding` `loc_site_lifecycle` `loc_site_lifecycle_log` · `agt_account`(v1 有名无字段) `agt_assignment` `agt_commission` · `wo_sla_rule` · `ord_exception` `ord_complaint` `ord_refund` `ord_reservation` `ord_deposit` · `price_rule`(v1 有名无字段) `price_schedule` · `pay_channel` · `stl_settlement_detail` `recon_task` `recon_diff` `fin_invoice` · `usr_blacklist` `usr_free_whitelist` `usr_recharge_pkg` `usr_recharge_order` · `mkt_notice` `mkt_campaign` `mkt_push` `mkt_referral` · `cs_ticket` `cs_session` `cs_message` · `usr_favorite` `usr_message` `usr_push_token` `usr_notify_pref` `usr_invoice_title` `usr_invoice` `usr_logoff` `usr_consent` · **覆盖核对时补漏 5 张**：`tenant` `tenant_config` `iam_dept` `iam_menu` `mbr_plan`（db-design v1 正文有、DDL 从未建）· **§1.7 关联表 6 张**：`pay_channel_scope` `usr_recharge_pkg_market` `agt_agent_region` `price_plan_scope` `fin_invoice_item` `inv_transfer_item`

### 12.2 改造 10 表
| 表 | 改动 |
|---|---|
| `dev_alert` → **`dev_alarm`** | 更名；补 `alarm_no`/`vendor_error_code`/`wo_no`/`agent_no`/`site_no`/`remark` |
| `ord_rent` | 补 `free_reason`/`waived_amount`/`coupon_no`/`location_name` |
| `usr_credit` | 补 `risk_no`/`risk_level`/`flagged_at`；`blacklisted` 移出到 `usr_blacklist` |
| `stl_withdrawal` | 补 `fee`/`auditor_no`/`auditor_name`/`audited_at`/`reject_reason`/`bank_code`/`payee_name`/`applicant_no`（`payee_type`/`payee_no` 现有 DDL 已有）|
| `pay_order` | `type` 枚举补 `RECHARGE`/`MEMBERSHIP`；补 `channel_code` |
| `pii_user` | 补 `subject_type`，从「仅 C 端」扩为承载员工/场地方/代理联系人 |
| `dev_powerbank` | **两套不兼容枚举合一为 7 态 + 补 `SOLD` 买断终态**（§9A.1）；补 `cycles`/`health` |
| `dev_cabinet` | `status` 补 `IN_STOCK`（§9A.2） |
| `wo_order` | 补 `close_reason`/`closed_at`/`audited_by`/`audited_at`（§9A.4） |
| `wo_sla_rule` | `wo_type` 由自由字符串收敛为 `WorkOrderType` 枚举 |

### 12.3 明确不建表的 9 处（读模型）
实时监控 · 设备日志 · 站点坪效 · 代理绩效 · 分润统计 · 免费订单 · 用户价值画像 · 数据报表 6 叶 · 经营看板

---

## 十三、待确认

1. **`ord_refund` 与 `pay_refund` 双表**是否接受（§5.1）？替代方案是单表加 `audit_*` 列，但会让「业务审批」与「渠道执行」两个生命周期挤在一张表，重试/对账时难区分。**本文取双表**。
2. **`pay_channel` 表归 trade 前缀、API 归 `/api/platform`**（§5.3）是全文唯一的前缀错位，是否接受？替代是改名 `sys_pay_channel` 保持前缀=API，但会让支付配置脱离支付域。
3. **数据报表是否引入 `rpt_*` 物化表**：目前 6 叶全走实时聚合，订单量上来后大屏/坪效会慢。建议 P2 评估，触发条件 = 单次聚合 >2s。
4. **`pii_user` 扩到员工/场地方/代理**后，运营端列表页展示的掩码值从业务表冗余列取（快）还是每次回查 pii（准）？**本文取冗余掩码列**，明文只在 pii。
5. `usr_logoff` 冷静期天数与到期清除范围（哪些表随注销物理清除、哪些匿名化保留用于财务留痕）——需法务确认后写入 PDPL 专篇。
6. 分区保留周期（§十）中「财务流水 7 年」需按 UAE/CBUAE 实际要求复核。
7. ~~前端枚举不一致~~ ✅ **已按业务梳理定稿，见 §九·A**：充电宝两套枚举合一为 7 态（补 `SOLD`）· 机柜补 `IN_STOCK` · 订单与工单 7 态全保留（mock 覆盖不全，非枚举冗余）· 工单补 `close_reason`。**剩余动作**：`wo_sla_rule.wo_type` 收敛为 `WorkOrderType` 枚举；ops-web 前端类型与 mock 需按 §九·A 对齐（见 §十四）。
8. ~~五个「只有读/审批、没有创建」的缺口~~ ✅ **已按业务梳理定稿，见 [api/README §六·A「写入口与生产者」](../api/README.md)**：五个入口的生产者、触发方式、权限码已逐条明确，端点全部就位。
9. **告警域三处待定**（建 DDL 时暴露）：
   - `dev_alarm_code` 是**全局表无 `tenant_id`**，而引用它的 `dev_alarm_rule` 有 —— 多租户下租户无法自定义告警码，只能改通知规则。MVP 单租户无影响，**拆库或启用租户前必须复核**。
   - `dev_alarm.level` 与 `dev_alarm_code.level` 双份：本文按「记录列 = 写入时快照，可被规则覆盖」建，字典 level 是默认值。若要以字典为准则记录列应去掉。
   - v1 `dev_alert.status` 是 `OPEN/ACK/RESOLVED`，v2 `dev_alarm` 是 `OPEN/ACKED/CLOSED`。迁移映射已写进 `ddl/pb_core-v2-ops-alarm.sql` 的注释草稿。
10. `wo_sla_rule` 是否允许**同一 `wo_type` 多条规则**（按优先级取）？DDL 当前建了 `UK(tenant_id, wo_type)` = 一类型一规则；若要多条需去掉该 UK 并加 `priority` 列。
11. 多币种：`currency` 已随表下沉，但**汇率表 `md_fx_rate` 未建** —— 单一 AED 时不需要，开城多国后必须补。触发条件 = `md_market_country` 出现第二个 `LIVE`。

---

## 十四、§九·A 定稿引出的下游改动清单（未做，供排期）

> 状态机定稿后，**前端类型/mock 与后端状态机需同步对齐**。本节是逐条待办，不做完则前后端切换时状态值对不上。

| # | 位置 | 改动 | 阻塞谁 |
|---|---|---|---|
| F1 | `ops-web/lib/types/device.ts` | 删除 6 值 `PowerbankStatus`，`Powerbank.status` 改为 §9A.1 的 7 值 | 充电宝管理 tab |
| F2 | 同上 | `CabinetStatus` 补 `IN_STOCK`（§9A.2） | 库存调拨 tab |
| F3 | `ops-web/lib/types/workorder.ts` | `WorkOrder` 补 `closeReason` 字段（§9A.4） | 工单关单 |
| F4 | `ops-web/lib/mock/db/device.ts` | 按新枚举重铺充电宝/机柜样本，补 `IN_STOCK`/`SOLD`/`LOST` 各态 | 前端自测 |
| F5 | `ops-web/lib/mock/db/order.ts` | **补 `DISPENSING` 态订单样本** —— 当前 0 条，C端「等待弹出」链路无从测试（§9A.3） | C端借出兜底 |
| F6 | `ops-web/lib/mock/db/workorder.ts` | **补 `ACCEPTED` / `AUDITED` 态工单样本** —— 当前 0 条，SLA 响应判定与验收退回链路无从测试（§9A.4） | SLA 管理 |
| F7 | `ops-web/lib/types/workorder.ts` | `SlaRule.woType` 由 `string` 收敛为 `WorkOrderType` | SLA 规则配置 |
| B1 | `backend` `dev/PowerbankStateMachine` | 新建（§9A.5），非法迁移返 409 | 充电宝生命周期 |
| B2 | `backend` `wo/WoStateMachine` | 补 `ACCEPTED`/`AUDITED` 迁移 + `close_reason` 校验（`CLOSED` 必填） | 工单闭环 G6 |
| D1 | `ddl/pb_core-v2-alter.sql` | `dev_powerbank.status` / `dev_cabinet.status` 的 COMMENT 与迁移、`wo_order.close_reason` 建列 | 建库 |

> ⚠️ **F1/F4 有并发风险**：本文定稿时 `ops-web/` 正被另一会话改动（工单闭环与数据权限方向）。动前先 `git status` 确认，避免与在途改动冲突。
