SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 合同走审批（2026-09-25 定；TDD-运营核心流程/02）
--
-- 状态从 ACTIVE/EXPIRED 扩为六态，存量值与含义不变。
-- 列默认值保留 ACTIVE：种子与旧写入路径不带 status 时仍按旧语义；
-- 新建合同一律由 ContractService 显式写 DRAFT。
--
-- ⚠️ 同一站点多份 ACTIVE 的存量（测试库里有，生产库无业务数据）不在此修：
-- 修错合同 = 修错分成依据，不能由脚本替人决定。检测：
--   SELECT site_no, COUNT(*) FROM loc_contract WHERE status='ACTIVE' AND deleted=0 GROUP BY site_no HAVING COUNT(*)>1;
-- 新规则只在「生效」迁移时保证：激活新合同先把同站点旧合同 EXPIRE。
-- ============================================================
ALTER TABLE loc_contract
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    COMMENT 'DRAFT 草稿 / PENDING 待审批 / SIGNED 已批待生效 / ACTIVE 生效中 / EXPIRED 已到期 / TERMINATED 已终止',
  ADD COLUMN IF NOT EXISTS share_mode       VARCHAR(16)   NOT NULL DEFAULT 'SHARE' COMMENT 'SHARE 纯分成 / ENTRY_FEE 固定进场费 / GUARANTEE 保底加分成 / FREE 免费入驻',
  ADD COLUMN IF NOT EXISTS share_base       VARCHAR(16)   NOT NULL DEFAULT 'NET' COMMENT 'NET 实收 / GROSS 应收',
  ADD COLUMN IF NOT EXISTS guarantee_amount DECIMAL(18,2)     NULL COMMENT '保底金额（每结算周期）',
  ADD COLUMN IF NOT EXISTS deposit_amount   DECIMAL(18,2)     NULL COMMENT '押金',
  ADD COLUMN IF NOT EXISTS deposit_terms    VARCHAR(512)      NULL COMMENT '押金退还条件',
  ADD COLUMN IF NOT EXISTS exclusive_flag   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '场内排他',
  ADD COLUMN IF NOT EXISTS device_quota     INT               NULL COMMENT '约定设备台数',
  ADD COLUMN IF NOT EXISTS placement_note   VARCHAR(512)      NULL COMMENT '约定摆放位置',
  ADD COLUMN IF NOT EXISTS auto_renew       TINYINT(1)    NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS signer_name      VARCHAR(64)       NULL COMMENT '场地方签约人',
  ADD COLUMN IF NOT EXISTS signed_at        DATE              NULL COMMENT '盖章签署日期',
  ADD COLUMN IF NOT EXISTS submitted_by     VARCHAR(36)       NULL,
  ADD COLUMN IF NOT EXISTS submitted_at     DATETIME(3)       NULL,
  ADD COLUMN IF NOT EXISTS audited_by       VARCHAR(36)       NULL,
  ADD COLUMN IF NOT EXISTS audited_at       DATETIME(3)       NULL,
  ADD COLUMN IF NOT EXISTS audit_note       VARCHAR(512)      NULL COMMENT '最近一次审批意见（全部在 loc_contract_log）',
  ADD COLUMN IF NOT EXISTS activated_at     DATETIME(3)       NULL,
  ADD COLUMN IF NOT EXISTS ended_at         DATETIME(3)       NULL COMMENT '到期或终止时刻',
  ADD COLUMN IF NOT EXISTS end_reason       VARCHAR(512)      NULL COMMENT '终止原因 / 被续签合同替代 / 终止计划',
  ADD COLUMN IF NOT EXISTS prev_contract_no VARCHAR(36)       NULL COMMENT '续签来源合同',
  ADD COLUMN IF NOT EXISTS source_lead_no   VARCHAR(36)       NULL COMMENT '来源商机',
  ADD COLUMN IF NOT EXISTS remark           VARCHAR(512)      NULL,
  ADD KEY IF NOT EXISTS idx_contract_status_end (status, end_at),
  ADD KEY IF NOT EXISTS idx_contract_status_start (status, start_at);

CREATE TABLE IF NOT EXISTS loc_contract_log (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  contract_no  VARCHAR(36)  NOT NULL,
  event        VARCHAR(16)  NOT NULL COMMENT 'CREATE 新建 / UPDATE 编辑 / SUBMIT 提交 / WITHDRAW 撤回 / APPROVE 通过 / REJECT 驳回 / SIGN 签署归档 / ACTIVATE 生效 / EXPIRE 到期 / TERMINATE 终止 / RENEW 续签',
  from_status  VARCHAR(16)      NULL COMMENT 'DRAFT / PENDING / SIGNED / ACTIVE / EXPIRED / TERMINATED',
  to_status    VARCHAR(16)  NOT NULL COMMENT 'DRAFT / PENDING / SIGNED / ACTIVE / EXPIRED / TERMINATED',
  operator     VARCHAR(36)  NOT NULL COMMENT '员工号；系统触发为 SYSTEM',
  note         VARCHAR(512)     NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by   VARCHAR(36)      NULL,
  PRIMARY KEY (id),
  KEY idx_contract_log (contract_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同状态迁移日志（追加）';
