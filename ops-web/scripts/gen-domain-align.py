#!/usr/bin/env python3
"""生成 docs/api/领域对象-端点-库表对齐.md（人工裁决字典 M/LEFTOVER + 机器完整性核验）。

在 ops-web/ 目录下运行：python3 scripts/gen-domain-align.py
输入：../docs/api/运营端接口清单.md（端点全集，先跑 gen-api-inventory.py）· ../docs/technical/ddl/*.sql（表全集）
维护方式：新增端点/新表会触发断言失败——把新资源补进 M、新表补进 M 的库表列或 LEFTOVER 归位后重跑。
"""
import pathlib, collections, re

# —— 自提取：端点 → 资源组 ——
inv = pathlib.Path("../docs/api/运营端接口清单.md").read_text()
NS = {"alarms", "cs", "reports", "marketing", "ledger", "iam", "auth"}
resources = collections.defaultdict(list)
for verb, p in re.findall(r'\| (GET|POST|PUT) \| `([^`]+)`', inv):
    segs = [s for s in p.split("/") if s and not s.startswith("{")]
    body = segs[2:] if segs[0] in ("api", "internal") else segs
    if not body: continue
    key = "/".join(body[:2]) if body[0] in NS and len(body) > 1 else body[0]
    resources[key].append(f"{verb} {p}")

# —— 自提取：DDL 表全集 ——
ddl = set()
for f in pathlib.Path("../docs/technical/ddl").glob("*.sql"):
    ddl |= {m.group(1) for m in re.finditer(r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?', f.read_text())}

# 资源 → (域, 领域对象/前端类型, [库表] 或 ("读", 说明), 备注)
# 库表列表里的表 = 该资源的读写落点；"读" 表示无物理表的读模型
M = {
 # —— 设备管理 ——
 "cabinets": ("设备管理","Cabinet / Slot",["dev_cabinet","dev_slot"],"详情含仓位明细（dev_slot）；指令端点转 access-gateway"),
 "powerbanks": ("设备管理","Powerbank",["dev_powerbank"],"状态 7 态合一（db-design §9A.1）"),
 "cabinet-monitor": ("设备管理","CabinetMonitor",("读","`dev_cabinet` ⋈ `dev_shadow`"),"db-design 定稿不建表"),
 "command-records": ("设备管理","CommandRecord",["gw_command_log"],""),
 "device-logs": ("设备管理","DeviceLog",("读","`gw_command_log` ∪ `gw_message_log` 按 occurred_at 排序"),"双流合一时间轴"),
 "device-code-batches": ("设备管理","DeviceCodeBatch",["dev_code_batch"],""),
 "inventory-transfers": ("设备管理","InventoryTransfer",["inv_transfer","inv_transfer_item"],"仓库/库存字典（`inv_warehouse` `inv_stock`）无独立端点，随调拨单出参"),
 "ota-releases": ("设备管理","OtaRelease",["dev_ota_release"],""),
 "ota-rollouts": ("设备管理","OtaRollout / OtaTask",["dev_ota_rollout","dev_ota_task"],"/{no}/tasks 读逐设备任务"),
 "vendors": ("设备管理","Vendor",["gw_vendor","gw_vendor_config"],"`/internal/gw`；密钥出参掩码；/{code}/test 连通探测"),
 # —— 告警治理 ——
 "alarms/records": ("告警治理","AlarmRecord",["dev_alarm"],"v1 `dev_alert` 更名改造而来"),
 "alarms/codes": ("告警治理","AlarmCode",["dev_alarm_code"],"含建议处置 + 自动开单开关"),
 "alarms/rules": ("告警治理","AlarmRule",["dev_alarm_rule"],"静默窗口 + 升级策略"),
 "alarms/notices": ("告警治理","AlarmNotice",["dev_alarm_notice"],"/{no}/resend 重发"),
 "alarms/auto-work-orders": ("告警治理","AutoWorkOrderResult",("读","`dev_alarm` ⋈ `wo_order`（source=ALARM）"),"自动开单联动记录"),
 # —— 工单管理 ——
 "work-orders": ("工单管理","WorkOrder",["wo_order","wo_dispatch","wo_handle","wo_sla"],"9 端点全状态机；SLA 计时逐单落 `wo_sla`"),
 "sla-rules": ("工单管理","SlaRule",["wo_sla_rule"],""),
 "inspection-plans": ("工单管理","InspectionPlan",["wo_inspection_plan"],"/{no}/run 执行产出 `wo_order`（source=INSPECTION）"),
 # —— 站点与点位 ——
 "sites": ("站点与点位","Site",["loc_site"],""),
 "locations": ("站点与点位","SitePoint",["loc_location"],""),
 "site-analysis": ("站点与点位","SiteAnalysis",("读","`ord_rent` ⋈ `loc_site` 聚合"),"坪效/回本天数"),
 "venues": ("站点与点位","Venue",["loc_venue"],""),
 "contracts": ("站点与点位","Contract / ContractAttachment",["loc_contract"],"⚠️ 附件在 mock 是 `attachments` 数组，db-design `loc_contract` 未列该列——落 JSON 列或拆表需定稿（缺口 D-6）"),
 "venue-onboardings": ("站点与点位","VenueOnboarding",["loc_venue_onboarding"],"审核通过建 `loc_venue` 回填"),
 "site-lifecycles": ("站点与点位","SiteLifecycle",["loc_site_lifecycle","loc_site_lifecycle_log"],"阶段流转留痕"),
 "leads": ("站点与点位","Lead / LeadFollowUp",["loc_lead"],"⚠️ 跟进流水（follow-ups 端点）db-design 无表——缺口 D-5"),
 # —— 代理商 ——
 "agents": ("代理商","Agent",["agt_agent","agt_agent_region"],"辖区从表 `agt_agent_region`"),
 "accounts": ("代理商","AgentAccount",["agt_account"],""),
 "assignments": ("代理商","AgentAssignment(Record)",["agt_assignment"],"`?view=log` 切划拨流水读模型"),
 "assignable-assets": ("代理商","AssignableAsset",("读","`dev_cabinet` + `loc_site`（未划拨筛选）"),""),
 "commissions": ("代理商","AgentCommission",["agt_commission"],""),
 "performance": ("代理商","AgentPerformance",("读","`ord_rent`/`dev_cabinet`/`share_record` 按 agent_no 聚合"),""),
 # —— 订单管理 ——
 "orders": ("订单管理","RentOrder",["ord_rent","ord_event_log"],"timeline 读 `ord_event_log`；v3 定稿将拆 `ord_order`+`ord_rent_ext`（未建）"),
 "reservations": ("订单管理","Reservation",["ord_reservation"],"仅 PENDING 可取消，服务端复校"),
 "order-exceptions": ("订单管理","OrderException",["ord_exception"],""),
 "order-interventions": ("订单管理","OrderIntervention",[],"⚠️ mock 有独立集合，库无表——缺口 D-2（建议 `ord_intervention` 或并入 `ord_event_log` 定稿）"),
 "complaints": ("订单管理","OrderComplaint",["ord_complaint"],"转工单幂等（source_ref UK）"),
 "refunds": ("订单管理","RefundRecord",["ord_refund"],"业务审批单；通过后才生成渠道 `pay_refund`（1:1）"),
 "deposits": ("订单管理","DepositRecord",["ord_deposit"],"/{no}/buyout 买断、/{no}/dun 催缴"),
 "free-orders": ("订单管理","FreeOrder",("读","`ord_rent`（free_reason 非空）"),"stats 为全量口径"),
 # —— 计费定价 ——
 "price-plans": ("计费定价","PricePlan",["price_plan","price_plan_scope"],"订单落快照，改动仅影响新单"),
 "pricing-diffs": ("计费定价","PricingDiff",["price_rule"],"⚠️ 资源名≠表名"),
 "pricing-schedules": ("计费定价","PricingSchedule",["price_schedule"],""),
 # —— 财务管理 ——
 "share-rules": ("财务管理","ShareRule",["share_rule"],""),
 "share-records": ("财务管理","ShareRecord",["share_record"],""),
 "share-summaries": ("财务管理","ShareSummary",("读","`share_record` 按 dimension 聚合"),"受控排序白名单"),
 "settlements": ("财务管理","Settlement",["stl_settlement","stl_settlement_detail"],"generate 为周期出账入口"),
 "withdrawals": ("财务管理","Withdrawal",["stl_withdrawal"],"手续费口径唯一取 `sys_biz_rule(WITHDRAW)`"),
 "ledger": ("财务管理","LedgerEntry",["acct_ledger","acct_account"],"账户余额出参自 `acct_account`（无独立端点）"),
 "ledger/vouchers": ("财务管理","VoucherDetail",("读","`acct_ledger` 按 voucher_no 分组"),"建凭证 = 写多行分录"),
 "reconciles": ("财务管理","Reconcile / ReconDiff",["recon_task","recon_diff"],"stats 全量口径"),
 "invoices": ("财务管理","Invoice",["fin_invoice","fin_invoice_item"],"issue/void 开票红冲"),
 # —— 用户管理 ——
 "users": ("用户管理","CUser / UserProfile",["usr_user","usr_identity","pii_user"],"明文 PII 只在 `pb_pii`，列表出掩码；profile 为聚合出参"),
 "risk-users": ("用户管理","UserRisk",("读","`usr_user` ⋈ `usr_credit`"),""),
 "credit-score-changes": ("用户管理","CreditScoreChange",[],"⚠️ mock 有调分留痕集合，库无表——缺口 D-1（建议 `usr_credit_log`）"),
 "credit": ("用户管理","—（内部写入口）",["usr_blacklist","usr_credit"],"`/internal/user/credit/blacklist` 拉黑/解除"),
 "blacklist": ("用户管理","UserBlacklist",["usr_blacklist"],""),
 "free-whitelist": ("用户管理","FreeUserWhitelist",["usr_free_whitelist"],"revoke 软删除留记录"),
 "members": ("用户管理","Member",["usr_membership","mbr_plan"],""),
 "member-cards": ("用户管理","MemberCard",["usr_membership"],"孤儿端点（读侧并入 profile）；发卡 grantMemberCard 在用"),
 "member-benefits": ("用户管理","MemberBenefit",[],"⚠️ mock 有权益矩阵集合，库无表——缺口 D-4（建议 `mbr_benefit` 或定稿落 `mbr_plan.rights` JSON）"),
 "wallets": ("用户管理","Wallet / WalletTxn",["usr_wallet","usr_wallet_txn"],"含用户价值画像（聚合不落列）"),
 "recharge-packages": ("用户管理","RechargePackage",["usr_recharge_pkg","usr_recharge_pkg_market"],"适用市场从表"),
 "recharge-orders": ("用户管理","RechargeOrder",["usr_recharge_order"],""),
 # —— 营销管理 ——
 "marketing/notices": ("营销管理","Notice",["mkt_notice"],"三语 + 置顶；前缀在 /api/ops（沿用前端现状）"),
 "coupons": ("营销管理","Coupon",["coupon_tpl","usr_coupon"],"模板 + 定向发券"),
 "coupon-issue-records": ("营销管理","CouponIssueRecord",["usr_coupon"],"发放明细即用户券"),
 "campaigns": ("营销管理","Campaign",["mkt_campaign"],""),
 "push-messages": ("营销管理","PushMessage",["mkt_push"],"/{no}/send 触发发送"),
 "referrals": ("营销管理","Referral",["mkt_referral"],""),
 "referral-rules": ("营销管理","ReferralRule",[],"⚠️ mock 有规则集合，库无表——缺口 D-3（建议 `mkt_referral_rule`）"),
 "ad-slots": ("营销管理","AdSlot",["ad_slot"],"挂 cabinetNo，故前缀 /api/ops"),
 "ad-campaigns": ("营销管理","AdCampaign",["ad_campaign","ad_advertiser","ad_creative","ad_placement"],"广告主/创意/排期"),
 "ad-deliveries": ("营销管理","AdDelivery",("读","`ad_impression` ⋈ `ad_placement` 聚合"),"投放与曝光统计"),
 # —— 客服管理 ——
 "cs/tickets": ("客服管理","CsTicket",["cs_ticket"],"唯一受理单；转工单/转退款幂等出口"),
 "cs/sessions": ("客服管理","CsSession / CsMessage",["cs_session","cs_message"],""),
 # —— 数据报表（全部 [读]，db-design §11.1）——
 "reports/device": ("数据报表","ReportDevice",("读","`dev_cabinet`/`ord_rent`/`dev_alarm` 聚合"),""),
 "reports/location": ("数据报表","ReportLocation",("读","`ord_rent` ⋈ `loc_location` 聚合"),""),
 "reports/finance": ("数据报表","ReportFinance",("读","`ord_rent`/`share_record`/`stl_settlement` 聚合"),""),
 "reports/screen": ("数据报表","ReportScreen",("读","全域聚合"),"孤儿端点，已被 screen-board 取代"),
 "reports/screen-board": ("数据报表","ScreenBoard",("读","全域聚合"),""),
 "reports/custom": ("数据报表","ReportCustom",("读","自选维度×指标聚合"),""),
 "reports/consumer-segments": ("数据报表","ConsumerSegment",("读","`usr_user` ⋈ `ord_rent` 聚合"),""),
 "reports/consumer-insight": ("数据报表","ConsumerInsight",("读","`usr_user` ⋈ `ord_rent` 聚合"),""),
 "reports/metrics": ("数据报表","ReportMetricDef",("读","指标目录（配置）"),""),
 "reports/trend": ("数据报表","ReportTrend",("读","按 kind 分流聚合"),""),
 "dashboard": ("数据报表","DashboardStats",("读","全域聚合（`ord_rent`/`dev_cabinet`/`dev_alarm`/`wo_order`/`ord_refund`/`stl_withdrawal`）"),"待办中心三数跨域"),
 # —— 员工与权限 ——
 "employees": ("员工与权限","Employee",["iam_employee","iam_employee_role"],""),
 "departments": ("员工与权限","Department",["iam_dept"],""),
 "roles": ("员工与权限","RoleRow",["iam_role","iam_role_perm"],"内置角色拒绝归档"),
 "iam": ("员工与权限","—（bootstrap 聚合）",("读","`iam_role`+`iam_permission`+`iam_role_perm`+`iam_data_scope`"),""),
 "iam/permissions": ("员工与权限","PermissionItem",["iam_permission"],"权限目录（全局）"),
 "iam/roles": ("员工与权限","—（角色授权）",["iam_role_perm"],"PUT 全量替换权限集"),
 "audit-logs": ("员工与权限","AuditEntry / AuditTrail",["iam_audit_log"],"WORM 只增"),
 "staff-performance": ("员工与权限","StaffPerformance",["iam_staff_perf"],""),
 "login": ("员工与权限","—（认证）",["cred_credential","iam_employee"],"凭据在 `pb_auth` 库；动态菜单读 `iam_menu`（切后端后启用）"),
 # —— 系统设置 ——
 "payment-channels": ("系统设置","PaymentChannel",["pay_channel","pay_channel_scope"],"密钥只出掩码"),
 "notify-templates": ("系统设置","NotifyTemplate",["notify_template"],"preview/test-send 不落库"),
 "notify-logs": ("系统设置","NotifyLog",["notify_log"],"/{no}/resend；目标存储即脱敏"),
 "notify-blacklist": ("系统设置","NotifyBlacklist",["notify_blacklist"],"release 软删除"),
 "biz-rules": ("系统设置","BizRules",["sys_biz_rule"],"单例三分区；提现手续费唯一来源"),
 "login-settings": ("系统设置","LoginSetting",["sys_login_setting"],"至少留一种登录方式"),
 "app-versions": ("系统设置","AppVersion",["sys_app_version"],"rollback 软回滚"),
 "dict-entries": ("系统设置","DictEntry",["dict_item"],"⚠️ 资源名≠表名"),
 "regions": ("系统设置","Region / RegionNode",["md_region"],"/tree 树形读"),
 "banks": ("系统设置","BankEntry",["md_bank"],"IBAN 长度供提现校验"),
 "problems": ("系统设置","ProblemEntry",["md_problem"],"suggested_action 驱动诉求分流"),
 "sys-params": ("系统设置","SysParam",["sys_param"],""),
 "tax-settings": ("系统设置","TaxSetting",["sys_tax_setting"],""),
 "markets": ("系统设置","MarketCountry",["md_market_country"],"⚠️ 资源名≠表名"),
 "openapi-apps": ("系统设置","OpenApiApp",["openapi_app"],"/{no}/reset-secret"),
}

# —— 未被任何运营端资源引用的表：逐张归位 ——
LEFTOVER = {
 "C端专属（/mp 端点，见 README §八）": ["usr_favorite","usr_message","usr_push_token","usr_notify_pref","usr_invoice","usr_invoice_title","usr_logoff","usr_consent"],
 "支付编排（/internal/trade + /notify，委托 nearpay · ADR-005）": ["pay_order","pay_refund","pay_auth","pay_event_log"],
 "网关南向（/gw 回调 + 驱动，access-gateway）": ["gw_device_binding","gw_message_log","dev_heartbeat","dev_shadow"],
 "库存字典（库存调拨叶落表，暂无独立端点）": ["inv_warehouse","inv_stock"],
 "动态菜单（切后端后 /api/auth/menus 启用）": ["iam_menu"],
 "数据权限（⚠️ 缺口 G7：前端抽屉 onSave 不调 API，`PUT /api/platform/data-scopes/**` 待前端补）": ["iam_data_scope"],
 "休眠（🔒 无 UI · ADR-011）": ["tenant","tenant_config"],
 "遗留（v2 更名 `dev_alarm`，迁移后弃）": ["dev_alert"],
 "广告曝光 append 流（`[读]` ad-deliveries 的聚合源，C端/网关侧写入）": ["ad_impression"],
}

# ---------- 完整性核验 ----------
assert set(M) == set(resources), f"资源未全覆盖: {set(resources)^set(M)}"
referenced = {t for _,_,tabs,_ in M.values() if isinstance(tabs,list) for t in tabs}
placed = referenced | {t for ts in LEFTOVER.values() for t in ts}
missing = ddl - placed
extra = placed - ddl
assert not missing, f"库表未归位: {sorted(missing)}"
assert not extra, f"引用了不存在的表: {sorted(extra)}"
ep_total = sum(len(v) for v in resources.values())

# ---------- 输出 ----------
out=[]
out.append("# 领域对象 ↔ API 端点 ↔ 库表 对齐\n")
out.append("> 状态：**梳理定稿**（2026-08-04）· 人工逐条裁决 + 脚本完整性核验（267 端点全覆盖 / 133 表全归位，核验脚本 `ops-web/scripts/gen-domain-align.py`，改契约/DDL 后重跑）")
out.append("> 输入三源：[运营端接口清单](./运营端接口清单.md)（267 端点 · 111 资源组）· [db-design](../technical/db-design.md) + `ddl/*.sql`（**v2 现状 133 表**）· `ops-web/lib/types/*.ts`（前端领域类型）")
out.append("> 口径：对齐基线是 **v2 现状**；[设计 v3 数据库变更](../technical/设计v3-数据库变更.md)（133→145 表，`ord_rent` 拆 `ord_order`）已定稿**未建表**，不在本表。")
out.append("> 分工：db-design **§11.1 是「菜单叶 → 表」**；本文是「**API 资源 → 领域对象 → 表**」，两者互为补充。`[读]` = 读模型无物理表（与 §11.1 口径一致）。\n")

out.append("## 一、领域对象总表（111 资源组 · 按域）\n")
order = ["设备管理","告警治理","工单管理","站点与点位","代理商","订单管理","计费定价","财务管理","用户管理","营销管理","客服管理","数据报表","员工与权限","系统设置"]
bydom = collections.defaultdict(list)
for res,(dom,typ,tabs,note) in M.items(): bydom[dom].append((res,typ,tabs,note))
for dom in order:
    out.append(f"### {dom}\n")
    out.append("| API 资源 | 端点数 | 领域对象（前端类型） | 库表 | 备注 |\n|---|---:|---|---|---|")
    for res,typ,tabs,note in sorted(bydom[dom]):
        n = len(resources[res])
        t = f"`[读]` {tabs[1]}" if isinstance(tabs,tuple) else (" ".join(f"`{x}`" for x in tabs) or "**—（缺表）**")
        out.append(f"| `{res}` | {n} | {typ} | {t} | {note} |")
    out.append("")

reads = [(res,d[1],d[2][1]) for res,d in sorted(M.items()) if isinstance(d[2],tuple)]
out.append(f"## 二、读模型端点（`[读]` 无物理表，{len(reads)} 个资源组）\n")
out.append("> 后端实现为实时聚合查询；单次聚合 >2s 再考虑物化（db-design §11.1 约定）。\n")
out.append("| API 资源 | 领域对象 | 聚合来源 |\n|---|---|---|")
for res,typ,src in reads: out.append(f"| `{res}` | {typ} | {src} |")
out.append("")

out.append("## 三、缺口清单（本次对齐发现）\n")
out.append("### 3.1 前端领域对象有、库表无（D-1 ~ D-6，建表前后端都无法贯通）\n")
out.append("""| # | 领域对象 | mock 落点 | 建议 |
|---|---|---|---|
| D-1 | CreditScoreChange 信用分流水 | `lib/mock/db/user.ts` `creditScoreChanges` | 建 `usr_credit_log`（调分留痕 + 阈值重算风险等级） |
| D-2 | OrderIntervention 干预记录 | `lib/mock/db/order.ts` `orderInterventions` | 建 `ord_intervention`，或并入 `ord_event_log` 需定稿字段 |
| D-3 | ReferralRule 邀请规则 | `lib/mock/db/marketing.ts` `referralRules` | 建 `mkt_referral_rule`（`mkt_referral` 只是邀请记录） |
| D-4 | MemberBenefit 会员权益矩阵 | `lib/mock/db/user.ts` `memberBenefits` | 建 `mbr_benefit`，或定稿落 `mbr_plan.rights` JSON |
| D-5 | LeadFollowUp 商机跟进流水 | `lib/mock/db`（lead-followup） | 建 `loc_lead_followup`（`loc_lead` 只有 next_follow_at） |
| D-6 | ContractAttachment 合同附件 | `loc_contract.attachments` 数组 | db-design `loc_contract` 未列附件列——补 JSON 列定义或拆 `loc_contract_attachment` |
""")
out.append("### 3.2 库表有、前端无调用\n")
out.append("| 表 | 现状 | 备注 |\n|---|---|---|")
out.append("| `iam_data_scope` | **G7**：数据权限抽屉 onSave 只 invalidate 不调 API | 后端仍须实现 `PUT /api/platform/data-scopes/{subjectType}/{subjectNo}`（README §7.1），前端补调用 |")
out.append("| `iam_menu` | 前端用本地 nav.ts | 切后端后 `GET /api/auth/menus` 启用（迁移指引见前端-动态菜单权限接入指引） |")
out.append("| `inv_warehouse` `inv_stock` | 调拨页无仓库/库存独立端点 | 若调拨单需仓库下拉与库存校验，需补 `GET /api/ops/warehouses`（待裁决） |")
out.append("")
out.append("### 3.3 命名错位（资源名 ≠ 表名，后端照表名建实体、照资源名开路由）\n")
out.append("""| API 资源 | 表 | | API 资源 | 表 |
|---|---|---|---|---|
| `pricing-diffs` | `price_rule` | | `dict-entries` | `dict_item` |
| `markets` | `md_market_country` | | `ad-deliveries` | `ad_impression` |
| `reconciles` | `recon_task` | | `coupons` | `coupon_tpl`+`usr_coupon` |
| `members` | `usr_membership`+`mbr_plan` | | `alarms/records` | `dev_alarm` |
""")

out.append("## 四、库表全集归位核对（133 表，无运营端资源的逐张归属）\n")
out.append(f"> §一 已引用 **{len(referenced)}** 张；其余 **{len(ddl)-len(referenced)}** 张归位如下（每张表在本文只出现一处归属）。\n")
out.append("| 归属 | 表 |\n|---|---|")
for cat, ts in LEFTOVER.items():
    out.append(f"| {cat} | " + " ".join(f"`{t}`" for t in sorted(ts)) + " |")
out.append("")

doc = pathlib.Path("../docs/api/领域对象-端点-库表对齐.md")
doc.write_text("\n".join(out))
print(f"written {doc} · {ep_total} endpoints in {len(M)} resources · tables referenced {len(referenced)} + leftover {len(ddl)-len(referenced)} = {len(ddl)}")
