SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 批次 B：合同与商机（对齐清单 §七 B1–B9）
--
--   B1 合同到期提醒：启用 CONTRACT_EXPIRING（60 天起报，30 / 7 天升级），BD 待办 + BD / 运营推送
--   B2 提前终止走审批：term_req_* 记申请与审批；审批中合同照常生效，到终止日由定时任务置 TERMINATED
--   B3 条件加签财务：运营审批通过后，比例 / 进场费超阈值或带保底 → audit_stage=FINANCE，财务会签后才 SIGNED
--   B4 补充协议：contract_kind=SUPPLEMENT + parent_contract_no；生效时把原合同置 EXPIRED（原版保留）
--   B5–B9 商机：阶段状态机、LOST 原因、90 天查重、跟进提醒与线索池、签约转化、竞品排他到期重新激活
--
-- 阈值与天数全部走 sys_param（运营可调），代码里的默认值与这里的种子一致。
-- ============================================================

-- —— 合同 ——
ALTER TABLE loc_contract
  ADD COLUMN IF NOT EXISTS contract_kind          VARCHAR(16)  NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN 主合同 / SUPPLEMENT 补充协议',
  ADD COLUMN IF NOT EXISTS parent_contract_no     VARCHAR(36)      NULL COMMENT '补充协议所补充的原合同',
  ADD COLUMN IF NOT EXISTS audit_stage            VARCHAR(16)      NULL COMMENT 'OPS 待运营审批 / FINANCE 待财务会签（仅 PENDING 时有值）',
  ADD COLUMN IF NOT EXISTS finance_audited_by     VARCHAR(36)      NULL,
  ADD COLUMN IF NOT EXISTS finance_audited_at     DATETIME(3)      NULL,
  ADD COLUMN IF NOT EXISTS finance_audit_note     VARCHAR(512)     NULL,
  ADD COLUMN IF NOT EXISTS term_req_status        VARCHAR(16)      NULL COMMENT 'PENDING 待审批 / APPROVED 已批待执行 / REJECTED 已驳回（提前终止申请）',
  ADD COLUMN IF NOT EXISTS term_req_reason        VARCHAR(512)     NULL,
  ADD COLUMN IF NOT EXISTS term_req_effective_at  DATE             NULL COMMENT '申请的终止日（当天起不再生效）',
  ADD COLUMN IF NOT EXISTS term_req_by            VARCHAR(36)      NULL,
  ADD COLUMN IF NOT EXISTS term_req_at            DATETIME(3)      NULL,
  ADD COLUMN IF NOT EXISTS term_audit_by          VARCHAR(36)      NULL,
  ADD COLUMN IF NOT EXISTS term_audit_at          DATETIME(3)      NULL,
  ADD COLUMN IF NOT EXISTS term_audit_note        VARCHAR(512)     NULL,
  ADD KEY IF NOT EXISTS idx_contract_parent (parent_contract_no),
  ADD KEY IF NOT EXISTS idx_contract_term (term_req_status, term_req_effective_at);

ALTER TABLE loc_contract_log
  MODIFY COLUMN event VARCHAR(16) NOT NULL
    COMMENT 'CREATE 新建 / UPDATE 编辑 / SUBMIT 提交 / WITHDRAW 撤回 / APPROVE 通过 / REJECT 驳回 / COSIGN 财务会签 / COSIGN_REJECT 财务驳回 / SIGN 签署归档 / ACTIVATE 生效 / EXPIRE 到期 / TERMINATE 终止 / RENEW 续签 / SUPPLEMENT 补充协议 / TERM_REQUEST 终止申请 / TERM_APPROVE 终止获批 / TERM_REJECT 终止驳回';

-- —— 商机 ——
ALTER TABLE loc_lead
  ADD COLUMN IF NOT EXISTS address                     VARCHAR(255)  NULL COMMENT '场地地址（查重用）',
  ADD COLUMN IF NOT EXISTS venue_no                    VARCHAR(36)   NULL COMMENT '签约转化时生成 / 关联的场地方',
  ADD COLUMN IF NOT EXISTS contract_no                 VARCHAR(36)   NULL COMMENT '签约转化生成的合同草稿',
  ADD COLUMN IF NOT EXISTS lost_reason                 VARCHAR(512)  NULL COMMENT '丢单原因（LOST 必填）',
  ADD COLUMN IF NOT EXISTS lost_at                     DATETIME(3)   NULL,
  ADD COLUMN IF NOT EXISTS last_follow_at              DATETIME(3)   NULL COMMENT '最近一次跟进；提醒与回收按它计',
  ADD COLUMN IF NOT EXISTS remind_at                   DATETIME(3)   NULL COMMENT '最近一次「久未跟进」提醒',
  ADD COLUMN IF NOT EXISTS in_pool                     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 = 已回收到公共线索池（无负责人）',
  ADD COLUMN IF NOT EXISTS pooled_at                   DATETIME(3)   NULL,
  ADD COLUMN IF NOT EXISTS prev_owner                  VARCHAR(64)   NULL COMMENT '回收前的负责人',
  ADD COLUMN IF NOT EXISTS competitor_name             VARCHAR(128)  NULL COMMENT '已签竞品',
  ADD COLUMN IF NOT EXISTS competitor_exclusive_until  DATE          NULL COMMENT '竞品独家到期日；到期前 N 天自动重新激活',
  ADD COLUMN IF NOT EXISTS reactivated_at              DATETIME(3)   NULL,
  ADD COLUMN IF NOT EXISTS share_mode                  VARCHAR(16)   NULL COMMENT '谈判条款：SHARE 纯分成 / ENTRY_FEE 固定进场费 / GUARANTEE 保底加分成 / FREE 免费入驻',
  ADD COLUMN IF NOT EXISTS share_rate                  DECIMAL(6,4)  NULL COMMENT '谈判条款：场地方分成比例',
  ADD COLUMN IF NOT EXISTS entry_fee                   DECIMAL(18,2) NULL COMMENT '谈判条款：进场费',
  ADD COLUMN IF NOT EXISTS guarantee_amount            DECIMAL(18,2) NULL COMMENT '谈判条款：保底金额',
  ADD COLUMN IF NOT EXISTS term_months                 INT           NULL COMMENT '谈判条款：合同期限（月）',
  ADD COLUMN IF NOT EXISTS exclusive_flag              TINYINT(1)    NULL COMMENT '谈判条款：场内排他',
  ADD KEY IF NOT EXISTS idx_lead_pool (in_pool, stage),
  ADD KEY IF NOT EXISTS idx_lead_venue_name (venue_name),
  ADD KEY IF NOT EXISTS idx_lead_address (address);

-- 存量商机：最近跟进取跟进记录的最后一条，没有就取更新时间 —— 否则上线第一天全部被判「久未跟进」
UPDATE loc_lead l
   SET l.last_follow_at = COALESCE((SELECT MAX(f.created_at) FROM loc_lead_follow f WHERE f.lead_no = l.lead_no), l.updated_at)
 WHERE l.last_follow_at IS NULL;

-- —— 系统参数 ——
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'contract.cosign.share_rate', '合同加签财务：场地方分成比例超过', '0.30', '合同'),
  ('MAIN', 'contract.cosign.entry_fee',  '合同加签财务：进场费超过',         '10000', '合同'),
  ('MAIN', 'contract.cosign.guarantee',  '合同加签财务：保底金额超过（0 = 带保底即加签）', '0', '合同'),
  ('MAIN', 'lead.follow.remind_days',    '商机超 N 天无跟进提醒负责人',       '7', '商机'),
  ('MAIN', 'lead.pool.recycle_days',     '商机超 M 天无跟进回收到线索池',     '30', '商机'),
  ('MAIN', 'lead.dedup.days',            '商机查重窗口（天）',               '90', '商机'),
  ('MAIN', 'lead.reactivate.before_days', '竞品独家到期前 N 天重新激活',      '60', '商机')
ON DUPLICATE KEY UPDATE label = VALUES(label), group_name = VALUES(group_name);

-- —— 合同到期提醒（B1）——
ALTER TABLE dev_alarm
  MODIFY COLUMN cause VARCHAR(16) NULL
    COMMENT 'OFFLINE 离线 / NO_STOCK 无宝 / FULL 满柜 / FAULT 故障停用 / MIXED 混合 / NO_CONTRACT 无合同 / EXPIRING 合同将到期 / CANCEL_FAILED 撤单失败 / SN_SEEN 已识别未结单 / HAZARD 安全隐患 / OVERHEAT 过热';

UPDATE dev_alarm_code SET enabled = 1, threshold = 60, hold_minutes = 0, recover_rule = 'SIGNAL_CLEAR', recover_hold_minutes = 0,
       suggestion = '续签或撤场评估：60 天起报，30 / 7 天升级；续签合同提交后自动消除'
 WHERE code = 'CONTRACT_EXPIRING';

INSERT INTO dev_alarm_route (alarm_code, cause, disposition, wo_type, priority_delta, fallback) VALUES
  ('CONTRACT_EXPIRING', '*', 'TODO', NULL, 0, NULL)
ON DUPLICATE KEY UPDATE disposition = VALUES(disposition), wo_type = VALUES(wo_type), fallback = VALUES(fallback);

INSERT INTO dev_alarm_rule (rule_no, tenant_id, alarm_code, target, channel, method, quiet_start, quiet_end, escalate_minutes, status) VALUES
  ('AR-OPSFLOW-CONTRACT-EXP', 'MAIN', 'CONTRACT_EXPIRING', 'BD,OPS', 'PUSH', 'INSTANT', '20:00', '09:00', NULL, 'ACTIVE')
ON DUPLICATE KEY UPDATE target = VALUES(target), channel = VALUES(channel), quiet_start = VALUES(quiet_start),
  quiet_end = VALUES(quiet_end), escalate_minutes = VALUES(escalate_minutes);

-- —— 权限码目录（B3 财务会签）：新码被 @perm.can 强制，就必须能在角色勾选树上选得到（PermCatalogCoverageTest）——
INSERT INTO iam_permission (code, name, module) VALUES
  ('location:contract:cosign', '合同 财务会签（超阈值加签）', 'location'),
  ('location:contract:terminate', '合同 提前终止（申请）', 'location')
ON DUPLICATE KEY UPDATE name = VALUES(name), module = VALUES(module);
