-- 由 scripts/ddl-from-doc.py 从 21/04「3.4 工单」生成，勿手改；改文档后重新生成。
CREATE TABLE tkt_definition (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL,
  workspace_id VARCHAR(32) NOT NULL DEFAULT '',     -- '' = 账户级模板
  kind VARCHAR(16) NOT NULL,                        -- TICKET_TYPE / OPTION_SET
  code VARCHAR(64) NOT NULL,
  version VARCHAR(16) NOT NULL,
  name JSON NULL,
  body JSON NOT NULL,                               -- 类型：processCode · fields · priorityOptions · resolutionOptions · numberPrefix · assignment · slaPolicies · scopeFrom · dedupe
  latest TINYINT NOT NULL DEFAULT 1,
  channel VARCHAR(16) NOT NULL DEFAULT 'API', note VARCHAR(512) NULL,
  published_by VARCHAR(80) NOT NULL, published_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_def (account_id, workspace_id, kind, code, version),
  KEY idx_latest (account_id, workspace_id, kind, latest, code)
);

CREATE TABLE tkt_definition_status (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL DEFAULT '',
  kind VARCHAR(16) NOT NULL, code VARCHAR(64) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(80) NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_code (account_id, workspace_id, kind, code)
);

CREATE TABLE tkt_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  ticket_no VARCHAR(40) NOT NULL,
  type_definition_id BIGINT NOT NULL,
  type_code VARCHAR(64) NOT NULL, type_version VARCHAR(16) NOT NULL,
  instance_no VARCHAR(40) NOT NULL,
  current_task_no VARCHAR(40) NULL,
  title VARCHAR(256) NOT NULL, description TEXT NULL,
  priority VARCHAR(32) NULL, priority_ord INT NULL,
  resolution VARCHAR(32) NULL,
  fields JSON NULL,                                 -- 全部字段值（整存整取）
  requester VARCHAR(80) NULL, owner VARCHAR(80) NULL, assignee VARCHAR(80) NULL,
  -- ↓ 只读快照：由流程事件同步（同进程同事务），工单写路径不得修改
  status VARCHAR(64) NOT NULL, status_category VARCHAR(16) NOT NULL,
  is_open TINYINT NOT NULL DEFAULT 1,
  sla_state VARCHAR(16) NULL,                       -- 各指标中最差的：RUNNING / WARNING / BREACHED / MET / PAUSED
  sla_next_due_at_ms BIGINT NULL,                   -- 各运行中指标里最近的到期
  sla_started_at_ms BIGINT NULL,                    -- 同一指标（到期最近者）的起算时刻：列表画「剩余比例」用
  sla_paused_ms BIGINT NOT NULL DEFAULT 0,          -- 同一指标已暂停的累计时长
  -- ↑
  escalated TINYINT NOT NULL DEFAULT 0,
  merged_into VARCHAR(40) NULL,
  dedupe_key VARCHAR(160) NULL,
  dedupe_open_key VARCHAR(160) NULL,                -- 未结时 = dedupe_key，关单置 NULL → 同键至多一张未结
  created_by VARCHAR(80) NOT NULL, created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL, closed_at_ms BIGINT NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_no (workspace_id, ticket_no),
  UNIQUE KEY uk_instance (workspace_id, instance_no),
  UNIQUE KEY uk_dedupe_open (workspace_id, dedupe_open_key),
  KEY idx_list (workspace_id, type_code, is_open, priority_ord, created_at_ms),
  KEY idx_assignee (workspace_id, assignee, is_open),
  KEY idx_requester (workspace_id, requester, created_at_ms),
  KEY idx_sla (workspace_id, is_open, sla_state, sla_next_due_at_ms),
  KEY idx_stats (workspace_id, assignee, closed_at_ms)
);

CREATE TABLE tkt_field_value (                      -- 只存 filterable 字段；多选 / 多成员一值一行
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  ticket_no VARCHAR(40) NOT NULL, type_code VARCHAR(64) NOT NULL,
  field_code VARCHAR(64) NOT NULL,
  v_str VARCHAR(255) NULL,                          -- 文本 · 选项 · 成员 · 组 · 外部引用
  v_num DECIMAL(24,6) NULL,                         -- 数字 · 金额（最小单位另存 currency 于 v_str）
  v_time BIGINT NULL,                               -- 日期时间（毫秒）
  UNIQUE KEY uk_val (workspace_id, ticket_no, field_code, v_str, v_num, v_time),
  KEY idx_str (workspace_id, type_code, field_code, v_str),
  KEY idx_num (workspace_id, type_code, field_code, v_num),
  KEY idx_time (workspace_id, type_code, field_code, v_time)
);

CREATE TABLE tkt_ticket_scope (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  ticket_no VARCHAR(40) NOT NULL,
  scope_tag VARCHAR(128) NOT NULL,
  UNIQUE KEY uk_tag (workspace_id, ticket_no, scope_tag),
  KEY idx_tag (workspace_id, scope_tag, ticket_no)
);

CREATE TABLE tkt_comment (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  ticket_no VARCHAR(40) NOT NULL, seq INT NOT NULL,
  visibility VARCHAR(16) NOT NULL,                  -- PUBLIC / INTERNAL
  kind VARCHAR(16) NOT NULL DEFAULT 'COMMENT',      -- COMMENT / DEDUPE_HIT / MERGE / SYSTEM
  author VARCHAR(80) NOT NULL, on_behalf_of VARCHAR(80) NULL,
  body TEXT NULL, attachments JSON NULL,
  at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_seq (workspace_id, ticket_no, seq)
);

CREATE TABLE tkt_assign_cursor (                    -- 轮转派单游标（条件更新防并发重复）
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  type_code VARCHAR(64) NOT NULL, group_code VARCHAR(64) NOT NULL,
  last_member_id VARCHAR(32) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_cursor (workspace_id, type_code, group_code)
);
