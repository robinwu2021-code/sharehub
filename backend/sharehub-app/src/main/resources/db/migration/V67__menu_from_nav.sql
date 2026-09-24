-- ============================================================
-- ShareHub · 菜单真源从 ops-web/lib/nav.ts 迁到 iam_menu
--
-- 【为什么要迁】
-- 菜单此前有三处真源：前端 nav.ts（**唯一在用**）、iam_menu 表、
-- 以及读它的两个端点（GET /api/auth/menus、GET /api/platform/iam/menus，
-- 前端一次都没调过）。2026-09-24 定：**改成后端下发**。
--
-- 【迁之前，那张表已经漂了，而且没人发现】
-- 生产 iam_menu 只有 12 行、全是顶级、一个叶子都没有；
-- 其中「站点与点位」「计费定价」两个菜单 nav.ts 里根本不存在，
-- 同时缺 告警管理 / 运营管理 / 客服管理 / 数据报表 / 场地方与拓展 五个。
-- 它是首次启动时灌的一版存货，此后 nav.ts 改了无数次，
-- **没有任何东西会红** —— 这正是"三处真源"的代价先兑现的那一半。
-- 所以下面是整表重灌，不是增量补：存货没有一行值得保留。
--
-- 【六个新列】
-- nav.ts 上有而这张表没有的，逐个量过只有六个，合计承载 35 个值：
--   module(19) modules(1) match_paths(3) ready(8) pin_bottom(1) portal_for(3)
-- 其中 modules / match_paths / portal_for 是数组，存 JSON。
--
-- ⚠️ **soon 没有列，也不该有**：它由前端 opLeaves 调 pageReady(page, useMock)
-- 算出来，取决于前端跑的是 mock 还是真后端 —— 那是构建的属性，不是菜单的属性。
-- 给它建一列就等于把一个会随环境变的值冻进库里。
--
-- 【下面那段 INSERT 是生成的，不要手改】
-- backend/scripts/gen-menu-seed.py 从 nav.ts 导出的 JSON 生成。
-- 切换那一刻两边必须**逐节点相等** —— 手写 127 行做不到"相等"，
-- 只能做到"看起来差不多"，而差的那几行不会有任何东西报错。
-- 卡口：ops-web/lib/nav-seed.test.ts 拿本文件与 NAV 逐节点比对。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS module      VARCHAR(32)  NULL COMMENT '权限码模块前缀（canModule 过滤用，仅 MENU 行）';
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS modules     JSON         NULL COMMENT '跨模块 section 的全部模块前缀，任一可见即显示';
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS match_paths JSON         NULL COMMENT '路径归属前缀；缺省取 path 的 path 部分';
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS ready       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '就绪度覆盖：无视 phase 直接解锁（逐叶推进）';
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS pin_bottom  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Rail 固定底部';
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS portal_for  JSON         NULL COMMENT '专属门户：命中的角色只看得到门户 section';

-- 整表重灌（见上：存货全是错的）。DELETE 而非 TRUNCATE —— 表上有外键语义的
-- parent_no 自引用，且 TRUNCATE 在部分 MariaDB 配置下不可回滚。
DELETE FROM iam_menu;

-- 本段由 backend/scripts/gen-menu-seed.py 从 ops-web/lib/nav.ts 生成，请勿手改。
-- 共 127 行（18 个 section + 109 个叶子）。
INSERT INTO iam_menu (menu_no, parent_no, name, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for) VALUES
  ('M_my-biz', NULL, '我的经营', 'MENU', '/', 'LayoutDashboard', NULL, 1, NULL, 1, 0, 'dashboard', NULL, NULL, 0, '["AGENT"]'),
  ('M_my-biz__1', 'M_my-biz', '我的看板', 'ITEM', '/', NULL, '经营概览', 1, 'dashboard:overview:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_my-biz__2', 'M_my-biz', '我的收益', 'ITEM', '/finance?tab=records', NULL, '经营概览', 2, 'finance:share_record:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_my-biz__3', 'M_my-biz', '我的结算', 'ITEM', '/finance?tab=settlements', NULL, '经营概览', 3, 'agent:settlement:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_my-biz__4', 'M_my-biz', '申请提现', 'ITEM', '/finance?tab=withdrawals', NULL, '经营概览', 4, 'finance:withdrawal:apply', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_my-asset', NULL, '我的资产', 'MENU', '/devices', 'Server', NULL, 2, NULL, 1, 0, 'device', NULL, NULL, 0, '["AGENT"]'),
  ('M_my-asset__1', 'M_my-asset', '我的设备', 'ITEM', '/devices', NULL, '设备与订单', 1, 'device:cabinet:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_my-asset__2', 'M_my-asset', '我的订单', 'ITEM', '/orders', NULL, '设备与订单', 2, 'order:order:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_my-service', NULL, '我的服务', 'MENU', '/work-orders', 'Wrench', NULL, 3, NULL, 1, 0, 'workorder', NULL, NULL, 0, '["AGENT"]'),
  ('M_my-service__1', 'M_my-service', '设备报修', 'ITEM', '/work-orders?view=list', NULL, '报修与跟进', 1, 'workorder:wo:create', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_dashboard', NULL, '经营看板', 'MENU', '/', 'LayoutDashboard', NULL, 4, NULL, 1, 0, 'dashboard', NULL, NULL, 0, NULL),
  ('M_operation', NULL, '运营管理', 'MENU', '/operation/overview', 'Store', NULL, 5, NULL, 1, 0, 'location', '["location","pricing","finance","system","marketing"]', '["/operation","/locations"]', 0, NULL),
  ('M_operation__1', 'M_operation', '站点概览', 'ITEM', '/operation/overview', NULL, '场站管理', 1, 'location:overview:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__2', 'M_operation', '站点管理', 'ITEM', '/operation/sites', NULL, '场站管理', 2, 'location:poi:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__3', 'M_operation', '点位管理', 'ITEM', '/locations?tab=points', NULL, '场站管理', 3, 'location:poi:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__4', 'M_operation', '站点坪效', 'ITEM', '/locations?tab=analysis', NULL, '场站管理', 4, 'location:analysis:read', 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__5', 'M_operation', '收费方案', 'ITEM', '/operation/fee-plans', NULL, '计费与调价', 5, 'pricing:plan:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__6', 'M_operation', '预约调价', 'ITEM', '/operation/fee-adjustments', NULL, '计费与调价', 6, 'pricing:adjustment:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__7', 'M_operation', '应用版本', 'ITEM', '/operation/app-versions', NULL, '基础管理', 7, 'system:app_version:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__8', 'M_operation', '银行管理', 'ITEM', '/operation/banks', NULL, '基础管理', 8, 'system:bank:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__9', 'M_operation', '品牌管理', 'ITEM', '/operation/brands', NULL, '基础管理', 9, 'system:brand:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__10', 'M_operation', '问题管理', 'ITEM', '/operation/problems', NULL, '基础管理', 10, 'system:problem:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__11', 'M_operation', '公告管理', 'ITEM', '/operation/notices', NULL, '基础管理', 11, 'marketing:notice:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__12', 'M_operation', '站点分成', 'ITEM', '/operation/site-sharing', NULL, '分成', 12, 'finance:share_rule:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_operation__13', 'M_operation', '分成方分成', 'ITEM', '/operation/payee-sharing', NULL, '分成', 13, 'finance:share_rule:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_device', NULL, '设备管理', 'MENU', '/devices', 'Server', NULL, 6, NULL, 1, 0, 'device', NULL, NULL, 0, NULL),
  ('M_device__1', 'M_device', '设备台账', 'ITEM', '/devices', NULL, '资产台账', 1, 'device:cabinet:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_device__2', 'M_device', '充电宝管理', 'ITEM', '/devices?tab=powerbanks', NULL, '资产台账', 2, 'device:powerbank:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_device__3', 'M_device', '实时监控', 'ITEM', '/devices?tab=monitor', NULL, '在线运行', 3, 'device:cabinet:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_device__4', 'M_device', '远程控制·指令记录', 'ITEM', '/devices?tab=commands', NULL, '在线运行', 4, 'device:command:send', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_device__5', 'M_device', '设备日志', 'ITEM', '/devices?tab=logs', NULL, '在线运行', 5, 'device:cabinet:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_device__6', 'M_device', '库存调拨', 'ITEM', '/devices?tab=inventory', NULL, '资产流转', 6, 'device:inventory:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_device__7', 'M_device', '固件 OTA', 'ITEM', '/devices?tab=ota', NULL, '资产流转', 7, 'device:ota:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_device__8', 'M_device', '设备编码', 'ITEM', '/devices?tab=codes', NULL, '资产流转', 8, 'device:cabinet:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_alarm', NULL, '告警管理', 'MENU', '/alarms', 'BellRing', NULL, 7, NULL, 1, 0, 'workorder', NULL, NULL, 0, NULL),
  ('M_alarm__1', 'M_alarm', '告警记录', 'ITEM', '/alarms', NULL, '告警处置', 1, 'workorder:wo:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_alarm__2', 'M_alarm', '告警通知', 'ITEM', '/alarms?tab=notices', NULL, '告警处置', 2, 'workorder:wo:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_alarm__3', 'M_alarm', '告警代码', 'ITEM', '/alarms?tab=codes', NULL, '规则配置', 3, 'workorder:wo:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_alarm__4', 'M_alarm', '通知规则', 'ITEM', '/alarms?tab=rules', NULL, '规则配置', 4, 'workorder:wo:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_workorder', NULL, '工单管理', 'MENU', '/work-orders', 'Wrench', NULL, 8, NULL, 1, 0, 'workorder', NULL, NULL, 0, NULL),
  ('M_workorder__1', 'M_workorder', '工单列表', 'ITEM', '/work-orders?view=list', NULL, NULL, 1, 'workorder:wo:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_workorder__2', 'M_workorder', '工单看板', 'ITEM', '/work-orders?view=board', NULL, NULL, 2, 'workorder:wo:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_workorder__3', 'M_workorder', 'SLA 管理', 'ITEM', '/work-orders?view=sla', NULL, NULL, 3, NULL, 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_workorder__4', 'M_workorder', '巡检计划', 'ITEM', '/work-orders?view=inspection', NULL, NULL, 4, NULL, 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_venue', NULL, '场地方与拓展', 'MENU', '/venues?tab=venues', 'Building2', NULL, 9, NULL, 1, 0, 'location', NULL, '["/venues"]', 0, NULL),
  ('M_venue__1', 'M_venue', '场地方', 'ITEM', '/venues?tab=venues', NULL, '机构档案', 1, 'location:venue:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_venue__2', 'M_venue', '进场合同', 'ITEM', '/venues?tab=contracts', NULL, '机构档案', 2, 'location:contract:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_venue__3', 'M_venue', 'BD 拓展 CRM', 'ITEM', '/venues?tab=crm', NULL, '拓展', 3, 'location:lead:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_venue__4', 'M_venue', '门店 Onboarding', 'ITEM', '/venues?tab=onboarding', NULL, '拓展', 4, 'location:venue:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_venue__5', 'M_venue', '门店生命周期', 'ITEM', '/venues?tab=lifecycle', NULL, '拓展', 5, 'location:venue:read', 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_agent', NULL, '代理商管理', 'MENU', '/agents', 'Handshake', NULL, 10, NULL, 1, 0, 'agent', NULL, NULL, 0, NULL),
  ('M_agent__1', 'M_agent', '入驻审核', 'ITEM', '/agents?tab=applies', NULL, '机构档案', 1, 'agent:apply:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_agent__2', 'M_agent', '代理商档案', 'ITEM', '/agents', NULL, '机构档案', 2, 'agent:agent:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_agent__3', 'M_agent', '代理账号管理', 'ITEM', '/agents?tab=accounts', NULL, '机构档案', 3, 'agent:agent:update', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_agent__4', 'M_agent', '设备/点位划拨', 'ITEM', '/agents?tab=assign', NULL, '机构档案', 4, 'agent:scope:assign', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_agent__5', 'M_agent', '分润配置', 'ITEM', '/agents?tab=commission', NULL, '机构收益', 5, 'agent:share:config', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_agent__6', 'M_agent', '代理收益结算', 'ITEM', '/finance?tab=settlements', NULL, '机构收益', 6, 'agent:settlement:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_agent__7', 'M_agent', '代理绩效', 'ITEM', '/agents?tab=performance', NULL, '机构经营', 7, 'agent:performance:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_order', NULL, '订单管理', 'MENU', '/orders', 'ReceiptText', NULL, 11, NULL, 1, 0, 'order', NULL, NULL, 0, NULL),
  ('M_order__1', 'M_order', '订单列表', 'ITEM', '/orders', NULL, '交易流水', 1, 'order:order:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_order__2', 'M_order', '预约订单', 'ITEM', '/orders?tab=reservations', NULL, '交易流水', 2, 'order:order:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_order__3', 'M_order', '异常订单', 'ITEM', '/orders?tab=exceptions', NULL, '售后处置', 3, 'order:exception:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_order__4', 'M_order', '投诉订单', 'ITEM', '/orders?tab=complaints', NULL, '售后处置', 4, 'order:exception:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_order__5', 'M_order', '退款记录', 'ITEM', '/orders?tab=refunds', NULL, '售后处置', 5, 'order:refund:audit', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_order__6', 'M_order', '押金与欠费', 'ITEM', '/orders?tab=deposit', NULL, '特殊单据', 6, 'order:order:read', 1, 1, NULL, NULL, NULL, 0, NULL),
  ('M_order__7', 'M_order', '免费订单', 'ITEM', '/orders?tab=free', NULL, '特殊单据', 7, 'order:order:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance', NULL, '财务管理', 'MENU', '/finance', 'Wallet', NULL, 12, NULL, 1, 0, 'finance', NULL, NULL, 0, NULL),
  ('M_finance__1', 'M_finance', '分润规则', 'ITEM', '/finance?tab=rules', NULL, '分润与结算', 1, 'finance:share_rule:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__2', 'M_finance', '分润明细', 'ITEM', '/finance?tab=records', NULL, '分润与结算', 2, 'finance:share_record:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__3', 'M_finance', '分润统计', 'ITEM', '/finance?tab=summary', NULL, '分润与结算', 3, 'finance:share_record:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__4', 'M_finance', '结算单', 'ITEM', '/finance?tab=settlements', NULL, '分润与结算', 4, 'finance:settlement:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__5', 'M_finance', '账务分录', 'ITEM', '/finance?tab=ledger', NULL, '平台账', 5, 'finance:ledger:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__6', 'M_finance', '对账', 'ITEM', '/finance?tab=reconcile', NULL, '平台账', 6, 'finance:recon:read', 1, 1, NULL, NULL, NULL, 0, NULL),
  ('M_finance__7', 'M_finance', '发票', 'ITEM', '/finance?tab=invoices', NULL, '平台账', 7, 'finance:invoice:read', 2, 1, NULL, NULL, NULL, 0, NULL),
  ('M_finance__8', 'M_finance', '代理分润配置', 'ITEM', '/agents?tab=commission', NULL, '伙伴账', 8, 'agent:share:config', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__9', 'M_finance', '提现审核', 'ITEM', '/finance?tab=withdrawals', NULL, '伙伴账', 9, 'finance:withdrawal:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__10', 'M_finance', '收款账户', 'ITEM', '/finance?tab=payout-accounts', NULL, '伙伴账', 10, 'finance:payout_account:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__11', 'M_finance', '用户钱包', 'ITEM', '/users?tab=wallets', NULL, '用户账', 11, 'user:wallet:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_finance__12', 'M_finance', '充值订单', 'ITEM', '/finance?tab=recharges', NULL, '用户账', 12, 'user:wallet:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_user', NULL, '用户管理', 'MENU', '/users', 'UserCircle', NULL, 13, NULL, 1, 0, 'user', NULL, NULL, 0, NULL),
  ('M_user__1', 'M_user', '用户列表', 'ITEM', '/users', NULL, '用户主体', 1, 'user:cuser:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_user__2', 'M_user', '风控用户', 'ITEM', '/users?tab=risk', NULL, '风险治理', 2, 'user:risk:read', 1, 1, NULL, NULL, NULL, 0, NULL),
  ('M_user__3', 'M_user', '黑名单', 'ITEM', '/users?tab=blacklist', NULL, '风险治理', 3, 'user:risk:update', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_user__4', 'M_user', '免费用户白名单', 'ITEM', '/users?tab=whitelist', NULL, '风险治理', 4, 'user:risk:update', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_user__5', 'M_user', '会员/次卡', 'ITEM', '/users?tab=members', NULL, '用户资产', 5, 'user:member:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_user__6', 'M_user', '钱包', 'ITEM', '/users?tab=wallets', NULL, '用户资产', 6, 'user:wallet:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_user__7', 'M_user', '充值套餐', 'ITEM', '/users?tab=recharge', NULL, '用户资产', 7, 'user:wallet:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_marketing', NULL, '营销管理', 'MENU', '/marketing', 'Ticket', NULL, 14, NULL, 1, 0, 'marketing', NULL, NULL, 0, NULL),
  ('M_marketing__1', 'M_marketing', '优惠券', 'ITEM', '/marketing', NULL, '促销玩法', 1, 'marketing:coupon:read', 2, 1, NULL, NULL, NULL, 0, NULL),
  ('M_marketing__2', 'M_marketing', '活动', 'ITEM', '/marketing?tab=campaigns', NULL, '促销玩法', 2, NULL, 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_marketing__3', 'M_marketing', '推送触达', 'ITEM', '/marketing?tab=push', NULL, '促销玩法', 3, 'marketing:push:send', 3, 1, NULL, NULL, NULL, 0, NULL),
  ('M_marketing__4', 'M_marketing', '邀请裂变', 'ITEM', '/marketing?tab=referral', NULL, '促销玩法', 4, NULL, 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_marketing__5', 'M_marketing', '广告位管理', 'ITEM', '/marketing?tab=ad-slots', NULL, '广告经营', 5, NULL, 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_marketing__6', 'M_marketing', '广告活动', 'ITEM', '/marketing?tab=ad-campaigns', NULL, '广告经营', 6, NULL, 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_marketing__7', 'M_marketing', '投放与曝光', 'ITEM', '/marketing?tab=ad-delivery', NULL, '广告经营', 7, NULL, 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_cs', NULL, '客服管理', 'MENU', '/cs', 'Headset', NULL, 15, NULL, 1, 0, 'cs', NULL, NULL, 0, NULL),
  ('M_cs__1', 'M_cs', '报障受理', 'ITEM', '/cs', NULL, NULL, 1, NULL, 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_cs__2', 'M_cs', '客服会话', 'ITEM', '/cs?tab=sessions', NULL, NULL, 2, NULL, 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_cs__3', 'M_cs', '退款/补偿', 'ITEM', '/orders', NULL, NULL, 3, 'order:refund:apply', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_cs__4', 'M_cs', '黑名单处理', 'ITEM', '/users', NULL, NULL, 4, 'user:risk:update', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_report', NULL, '数据报表', 'MENU', '/reports', 'ChartColumn', NULL, 16, NULL, 1, 0, 'report', NULL, NULL, 0, NULL),
  ('M_report__1', 'M_report', '设备运营分析', 'ITEM', '/reports?tab=device', NULL, NULL, 1, 'report:device:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_report__2', 'M_report', '点位坪效', 'ITEM', '/reports?tab=location', NULL, NULL, 2, 'report:location:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_report__3', 'M_report', '财务报表', 'ITEM', '/reports?tab=finance', NULL, NULL, 3, NULL, 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_report__4', 'M_report', '实时大屏', 'ITEM', '/reports?tab=screen', NULL, NULL, 4, NULL, 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_report__5', 'M_report', '自定义报表', 'ITEM', '/reports?tab=custom', NULL, NULL, 5, NULL, 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_report__6', 'M_report', '消费者分析', 'ITEM', '/reports?tab=consumer', NULL, NULL, 6, NULL, 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_org', NULL, '员工与权限', 'MENU', '/employees', 'Users', NULL, 17, NULL, 1, 0, 'org', NULL, NULL, 0, NULL),
  ('M_org__1', 'M_org', '员工', 'ITEM', '/employees?tab=employees', NULL, '人与组织', 1, 'org:employee:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_org__2', 'M_org', '组织架构', 'ITEM', '/employees?tab=org', NULL, '人与组织', 2, 'org:employee:read', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_org__3', 'M_org', '角色权限', 'ITEM', '/employees?tab=roles', NULL, '授权', 3, 'org:role:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_org__4', 'M_org', '操作审计', 'ITEM', '/employees?tab=audit', NULL, '留痕与考核', 4, 'org:audit:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_org__5', 'M_org', '绩效报表', 'ITEM', '/employees?tab=performance', NULL, '留痕与考核', 5, 'org:employee:read', 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system', NULL, '系统设置', 'MENU', '/system?tab=vendors', 'Settings', NULL, 18, NULL, 1, 0, 'system', NULL, '["/system"]', 1, NULL),
  ('M_system__1', 'M_system', '供应商接入', 'ITEM', '/system?tab=vendors', NULL, '接入与支付', 1, 'device:vendor:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__2', 'M_system', '支付渠道', 'ITEM', '/system?tab=payment', NULL, '接入与支付', 2, 'system:payment_channel:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__3', 'M_system', '通知模板', 'ITEM', '/system?tab=notify', NULL, '消息触达', 3, 'system:notify_template:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__4', 'M_system', '发送记录', 'ITEM', '/system?tab=notify-log', NULL, '消息触达', 4, 'system:notify_log:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__5', 'M_system', '触达拉黑', 'ITEM', '/system?tab=notify-blacklist', NULL, '消息触达', 5, 'system:notify_blacklist:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__6', 'M_system', '业务规则', 'ITEM', '/system?tab=rules', NULL, '业务规则', 6, 'system:biz_rule:update', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__7', 'M_system', '登录设置', 'ITEM', '/system?tab=login', NULL, '业务规则', 7, 'system:login_setting:update', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__8', 'M_system', '参数字典', 'ITEM', '/system?tab=dict', NULL, '基础字典', 8, 'system:dict:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__9', 'M_system', '地区库', 'ITEM', '/system?tab=region', NULL, '基础字典', 9, 'system:dict:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__10', 'M_system', '系统参数', 'ITEM', '/system?tab=params', NULL, '基础字典', 10, 'system:param:read', 1, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__11', 'M_system', '税率与发票', 'ITEM', '/system?tab=tax', NULL, '开放与市场', 11, 'system:tax:update', 2, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__12', 'M_system', '多国家市场', 'ITEM', '/system?tab=markets', NULL, '开放与市场', 12, 'system:market:read', 3, 0, NULL, NULL, NULL, 0, NULL),
  ('M_system__13', 'M_system', 'OpenAPI 应用', 'ITEM', '/system?tab=openapi', NULL, '开放与市场', 13, 'system:openapi:read', 3, 0, NULL, NULL, NULL, 0, NULL);
