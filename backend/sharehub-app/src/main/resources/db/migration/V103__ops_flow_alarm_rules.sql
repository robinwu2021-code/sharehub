SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 业务告警默认通知规则（TDD-运营核心流程/05 §八：按域默认受众写成规则行，不在代码里硬编码）
--
-- target 写法：SITE_OWNER = 站点运维责任人（运营代理优先，暂停则落到员工）；大写角色码 = 该角色在职员工。
-- 通道先只用 PUSH（站内推送）；短信 / 邮件接入后由运营在「告警通知规则」页改。
--
-- 静默窗口：可借 / 可还类夜里 23:00–07:00 不推（营业时间已由判定侧过滤，这里防的是 24 小时站点半夜吵人）；
-- 安全类没有静默窗口（引擎对 SAFETY 域也会忽略静默）。
-- 升级：站点级「借不到 / 还不了」30 分钟没人确认 → 推给运维主管（OPS）；安全类 10 分钟。
--
-- 交易类（付了款没拿到宝 / 还了宝还在计费）不配推送：它们先自愈，失败转客服单，客服队列本身就是通知。
-- 无合同在营业配给 BD：待办已经派了，这条推送是提醒去看待办中心。
-- ============================================================
INSERT INTO dev_alarm_rule (rule_no, tenant_id, alarm_code, target, channel, method, quiet_start, quiet_end, escalate_minutes, status) VALUES
  ('AR-OPSFLOW-SITE-RENT',   'MAIN', 'SITE_UNRENTABLE',      'SITE_OWNER', 'PUSH', 'INSTANT', '23:00', '07:00', 30,   'ACTIVE'),
  ('AR-OPSFLOW-SITE-RETURN', 'MAIN', 'SITE_UNRETURNABLE',    'SITE_OWNER', 'PUSH', 'INSTANT', '23:00', '07:00', 30,   'ACTIVE'),
  ('AR-OPSFLOW-CAB-RENT',    'MAIN', 'CABINET_UNRENTABLE',   'SITE_OWNER', 'PUSH', 'INSTANT', '23:00', '07:00', NULL, 'ACTIVE'),
  ('AR-OPSFLOW-CAB-RETURN',  'MAIN', 'CABINET_UNRETURNABLE', 'SITE_OWNER', 'PUSH', 'INSTANT', '23:00', '07:00', NULL, 'ACTIVE'),
  ('AR-OPSFLOW-BATTERY',     'MAIN', 'BATTERY_HAZARD',       'SITE_OWNER,OPS', 'PUSH', 'INSTANT', NULL, NULL, 10,     'ACTIVE'),
  ('AR-OPSFLOW-OVERHEAT',    'MAIN', 'CABINET_OVERHEAT',     'SITE_OWNER,OPS', 'PUSH', 'INSTANT', NULL, NULL, 10,     'ACTIVE'),
  ('AR-OPSFLOW-NO-CONTRACT', 'MAIN', 'SITE_WITHOUT_CONTRACT', 'BD',        'PUSH', 'INSTANT', '20:00', '09:00', NULL, 'ACTIVE')
ON DUPLICATE KEY UPDATE target = VALUES(target), channel = VALUES(channel), quiet_start = VALUES(quiet_start),
  quiet_end = VALUES(quiet_end), escalate_minutes = VALUES(escalate_minutes);
