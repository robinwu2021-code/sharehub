-- V31：B4 字段对齐批次的三个 DDL 缺口（TDD-后端API对齐实现 §B4）
--
-- 31.1 wo_order：工单闭环（G6）读侧缺的三列 ——
--      expected_at 开单时录入但此前无处可落；audit_note/audit_result 验收留痕
--      （audited_by/audited_at V8 已有，唯独结论与说明没落点，验收等于白记）。
ALTER TABLE wo_order
  ADD COLUMN IF NOT EXISTS expected_at  DATETIME(3)  NULL COMMENT '期望完成时间(开单时填,超期提示用)',
  ADD COLUMN IF NOT EXISTS audit_result VARCHAR(16)  NULL COMMENT '验收结论：PASS/PASS_WITH_ISSUE/FAIL（与前端 WoAuditResult 同值域）',
  ADD COLUMN IF NOT EXISTS audit_note   VARCHAR(255) NULL COMMENT '验收说明';

-- 31.1b recon_task：差错处置留痕五列 —— 此前 /resolve 只翻 resolved 标记，
--       页面传的 action/handleNote/operatorName 被静默丢弃（http.ts T0-2 注释「语义缩水」）。
ALTER TABLE recon_task
  ADD COLUMN IF NOT EXISTS handle_status VARCHAR(16)  NULL COMMENT '处置进度：OPEN/HANDLING/RESOLVED/IGNORED,NULL=已平无差错',
  ADD COLUMN IF NOT EXISTS handle_result VARCHAR(16)  NULL COMMENT '处置结论：VERIFIED_OK/PLATFORM_ERROR/CHANNEL_ERROR/COMPENSATED',
  ADD COLUMN IF NOT EXISTS handle_note   VARCHAR(255) NULL COMMENT '处置说明(金额/凭证号/对接人)',
  ADD COLUMN IF NOT EXISTS handled_by    VARCHAR(36)  NULL COMMENT '处置人',
  ADD COLUMN IF NOT EXISTS handled_at    DATETIME(3)  NULL COMMENT '处置时间';

-- 31.1c ord_exception：处置结果四列（前端 OrderException.handleAction/handleResult/refundNo/workOrderNo，
--       此前只有 handled_by/handled_at，处置「做了什么、产出了哪张下游单」无处落）。
ALTER TABLE ord_exception
  ADD COLUMN IF NOT EXISTS handle_action VARCHAR(16)  NULL COMMENT '处置动作：REFUND/COMPENSATE/WORK_ORDER/IGNORE',
  ADD COLUMN IF NOT EXISTS handle_result VARCHAR(255) NULL COMMENT '处置结果说明',
  ADD COLUMN IF NOT EXISTS refund_no     VARCHAR(36)  NULL COMMENT '产出的退款单号(handle_action=REFUND)',
  ADD COLUMN IF NOT EXISTS work_order_no VARCHAR(36)  NULL COMMENT '产出的工单号(handle_action=WORK_ORDER)';

-- 31.1d stl_settlement：确认留痕两列（confirm 动作此前只改 status，谁确认的无处落）。
ALTER TABLE stl_settlement
  ADD COLUMN IF NOT EXISTS confirmed_by VARCHAR(36) NULL COMMENT '确认人',
  ADD COLUMN IF NOT EXISTS confirmed_at DATETIME(3) NULL COMMENT '确认时间';

-- 31.1e wo_inspection_plan：上次执行回显三列（/run 产出工单后列表要能看到跑没跑、产出了什么）。
ALTER TABLE wo_inspection_plan
  ADD COLUMN IF NOT EXISTS last_run_at     DATETIME(3)  NULL COMMENT '上次执行时间',
  ADD COLUMN IF NOT EXISTS last_run_period VARCHAR(16)  NULL COMMENT '上次执行覆盖的周期标识',
  ADD COLUMN IF NOT EXISTS last_run_wo_nos VARCHAR(512) NULL COMMENT '上次执行产出的工单号,逗号分隔';

-- 31.1f dev_alarm_notice：重发链路两列（/resend 产生新行时要能指回源行；幂等键防重复触发）。
ALTER TABLE dev_alarm_notice
  ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64) NULL COMMENT '触发幂等键',
  ADD COLUMN IF NOT EXISTS resend_of       VARCHAR(36) NULL COMMENT '重发来源 notice_no,NULL=首发';

-- 31.2 mkt_referral_rule：邀请裂变规则（D-3 勘定属实 —— mkt_referral 只是邀请记录，
--      规则此前无表，referral-rules 端点翻的是错误数据源）。
CREATE TABLE IF NOT EXISTS mkt_referral_rule (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_no       VARCHAR(36)   NOT NULL,
  tenant_id     VARCHAR(36)   NOT NULL DEFAULT 'MAIN',
  name          VARCHAR(64)   NOT NULL,
  inviter_reward DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '邀请人奖励金额',
  invitee_reward DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '被邀请人奖励金额',
  currency      VARCHAR(8)    NOT NULL DEFAULT 'AED',
  reward_type   VARCHAR(16)   NOT NULL DEFAULT 'BONUS' COMMENT 'BONUS 赠金/COUPON 券',
  coupon_tpl_no VARCHAR(36)       NULL COMMENT 'reward_type=COUPON 时关联券模板',
  cap_per_user  INT           NOT NULL DEFAULT 0 COMMENT '单人可得奖励上限次数,0=不限',
  status        VARCHAR(16)   NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  start_at      DATETIME(3)       NULL,
  end_at        DATETIME(3)       NULL,
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)       NULL COMMENT '创建人',
  updated_by    VARCHAR(36)       NULL COMMENT '最后修改人',
  version       BIGINT        NOT NULL DEFAULT 0,
  deleted       TINYINT(1)    NOT NULL DEFAULT 0,
  archived_at   DATETIME(3)       NULL COMMENT '归档时间,NULL=在用',
  PRIMARY KEY (id),
  UNIQUE KEY uk_referral_rule_no (rule_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请裂变规则（mkt_referral 是邀请记录，本表是规则）';

-- 31.3 loc_site：C 端找柜（/mp/nearby）缺的地理与营业信息 ——
--      无此三列时 distanceM/lat/lng/openHours 只能如实出 0/空（见 MpNearbyController 注释）。
ALTER TABLE loc_site
  ADD COLUMN IF NOT EXISTS lat        DECIMAL(10,7) NULL COMMENT '纬度(WGS84)',
  ADD COLUMN IF NOT EXISTS lng        DECIMAL(10,7) NULL COMMENT '经度(WGS84)',
  ADD COLUMN IF NOT EXISTS open_hours VARCHAR(64)   NULL COMMENT '营业时段展示文本,如 09:00-22:00';
