-- 线索跟进记录 + 合同附件元数据（前端契约 LeadFollowUp / ContractAttachment）

SET NAMES utf8mb4;

-- ── 线索跟进（append 表）──
-- **跟进是流水不是状态**：每次联系都留一条，「跟了几次、每次说了什么」是判断
-- 线索质量与销售投入的依据。覆盖式的「最近跟进」丢掉了这些。
CREATE TABLE IF NOT EXISTS loc_lead_follow (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  follow_no  VARCHAR(36)  NOT NULL COMMENT '业务键，前缀 LF',
  tenant_id  VARCHAR(36)  NULL,
  lead_no    VARCHAR(36)  NOT NULL,
  channel    VARCHAR(16)  NOT NULL COMMENT '联系渠道 CALL/VISIT/WECHAT/EMAIL',
  from_stage VARCHAR(24)  NULL COMMENT '来源阶段；首条建档跟进为 null',
  to_stage   VARCHAR(24)  NOT NULL COMMENT '跟进后阶段（可与来源相同=未推进）',
  owner      VARCHAR(64)  NULL COMMENT '跟进人',
  content    VARCHAR(500) NOT NULL COMMENT '跟进内容',
  next_at    DATE         NULL COMMENT '下次跟进计划日；空=未约',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lead_follow_no (follow_no),
  KEY idx_lead_follow (tenant_id, lead_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='线索跟进记录（append）';

-- ── 合同附件元数据 ──
-- **只存元数据，不存字节流**（前端契约明确：mock 阶段做假上传，故意没有 url/storageKey）。
-- 接对象存储时再补 storage_key 字段 —— 现在编一个假地址的话，
-- 「点开看不了」比「明确没有下载入口」更难查。
CREATE TABLE IF NOT EXISTS loc_contract_attach (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  attach_no   VARCHAR(36)  NOT NULL COMMENT '业务键，前缀 ATT',
  tenant_id   VARCHAR(36)  NULL,
  contract_no VARCHAR(36)  NOT NULL,
  file_name   VARCHAR(255) NOT NULL,
  size        BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
  uploaded_by VARCHAR(64)  NULL,
  uploaded_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(36)  NULL,
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(36)  NULL,
  version     BIGINT       NOT NULL DEFAULT 0,
  deleted     TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_contract_attach_no (attach_no),
  KEY idx_contract_attach (tenant_id, contract_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同附件元数据（不含字节流）';
