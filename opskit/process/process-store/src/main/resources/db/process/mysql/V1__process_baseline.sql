-- 由 scripts/ddl-from-doc.py 从 21/04「3.3 流程」生成，勿手改；改文档后重新生成。
CREATE TABLE proc_definition (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL,
  workspace_id VARCHAR(32) NOT NULL DEFAULT '',     -- '' = 账户级模板
  kind VARCHAR(16) NOT NULL,                        -- PROCESS / SLA_POLICY
  code VARCHAR(64) NOT NULL,
  version VARCHAR(16) NOT NULL,                     -- 规范化 JSON 的 SHA-256 前 16 位
  name JSON NULL,
  body JSON NOT NULL,
  latest TINYINT NOT NULL DEFAULT 1,
  channel VARCHAR(16) NOT NULL DEFAULT 'API',       -- API / CONSOLE
  note VARCHAR(512) NULL,
  published_by VARCHAR(80) NOT NULL, published_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_def (account_id, workspace_id, kind, code, version),
  KEY idx_latest (account_id, workspace_id, kind, latest, code)
);

CREATE TABLE proc_definition_status (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL DEFAULT '',
  kind VARCHAR(16) NOT NULL, code VARCHAR(64) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(80) NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_code (account_id, workspace_id, kind, code)
);

CREATE TABLE proc_instance (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  instance_no VARCHAR(40) NOT NULL,
  definition_id BIGINT NOT NULL,                    -- 绑定到具体定义行（含版本；模板或空间级）
  process_code VARCHAR(64) NOT NULL, process_version VARCHAR(16) NOT NULL,
  subject_type VARCHAR(64) NULL, subject_ref VARCHAR(128) NULL,  -- 外部引用（业务对象）
  subject_display JSON NULL,
  status VARCHAR(64) NOT NULL,
  status_category VARCHAR(16) NOT NULL,             -- NEW / IN_PROGRESS / WAITING / DONE / CANCELLED
  is_open TINYINT NOT NULL DEFAULT 1,
  fields JSON NULL,                                 -- 条件求值用的字段（工单时由工单模块写入同样的值）
  gate_kind VARCHAR(16) NULL,                       -- 当前状态上的门：APPROVAL / EXTERNAL / TIMER
  gate_epoch INT NOT NULL DEFAULT 0,
  confirm_token_hash VARCHAR(64) NULL,              -- 外部确认门：一次性令牌（只存哈希）
  confirm_result VARCHAR(16) NULL,                  -- 首次回复结果，重复回复返回它
  event_seq INT NOT NULL DEFAULT 0,
  created_by VARCHAR(80) NOT NULL, created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL, closed_at_ms BIGINT NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_no (workspace_id, instance_no),
  UNIQUE KEY uk_confirm (confirm_token_hash),
  KEY idx_subject (workspace_id, subject_type, subject_ref),
  KEY idx_list (workspace_id, process_code, is_open, status_category, created_at_ms)
);

CREATE TABLE proc_instance_scope (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  instance_no VARCHAR(40) NOT NULL,
  scope_tag VARCHAR(128) NOT NULL,                  -- '维度:值'
  UNIQUE KEY uk_tag (workspace_id, instance_no, scope_tag),
  KEY idx_tag (workspace_id, scope_tag, instance_no)
);

CREATE TABLE proc_participant (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  instance_no VARCHAR(40) NOT NULL,
  role VARCHAR(32) NOT NULL,                        -- REQUESTER / OWNER / ASSIGNEE / WATCHER / 自定义
  principal VARCHAR(80) NOT NULL,                   -- member:{id} / group:{code} / contact:{id}
  added_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_part (workspace_id, instance_no, role, principal),
  KEY idx_principal (workspace_id, principal, instance_no)
);

CREATE TABLE proc_task (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  task_no VARCHAR(40) NOT NULL,
  kind VARCHAR(16) NOT NULL,                        -- WORK / APPROVAL
  instance_no VARCHAR(40) NOT NULL,
  approval_no VARCHAR(40) NULL, round_no INT NULL, step_code VARCHAR(64) NULL,
  queue_group VARCHAR(64) NULL,                     -- 候选为 QUEUE 组时
  title VARCHAR(256) NULL, priority_ord INT NULL,   -- 选项集里的序号，便于排序
  status VARCHAR(16) NOT NULL,                      -- OPEN / CLAIMED / ASSIGNED / DONE / CANCELLED
  assignee VARCHAR(80) NULL,                        -- member:{id}
  claimed_at_ms BIGINT NULL, claim_epoch INT NOT NULL DEFAULT 0,
  decision VARCHAR(16) NULL, reason VARCHAR(1000) NULL,
  completed_by VARCHAR(80) NULL, completed_at_ms BIGINT NULL,
  due_at_ms BIGINT NULL, due_epoch INT NOT NULL DEFAULT 0,
  created_at_ms BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_no (workspace_id, task_no),
  KEY idx_instance (workspace_id, instance_no, status),
  KEY idx_approval (workspace_id, approval_no, round_no),
  KEY idx_assignee (workspace_id, assignee, status),
  KEY idx_queue (workspace_id, queue_group, status, priority_ord, created_at_ms),
  KEY idx_claim_timeout (status, claimed_at_ms)
);

CREATE TABLE proc_task_candidate (                  -- 待办：一条任务 N 行候选；与任务同事务维护
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  task_no VARCHAR(40) NOT NULL, instance_no VARCHAR(40) NOT NULL,
  candidate_key VARCHAR(160) NOT NULL,              -- member:x / group:y / role:z / perm:p / participant:OWNER
  is_open TINYINT NOT NULL DEFAULT 1,
  task_kind VARCHAR(16) NOT NULL,
  origin VARCHAR(16) NOT NULL,                      -- PROCESS / TICKET（待办筛选；由调用方给出）
  priority_ord INT NULL, due_at_ms BIGINT NULL, created_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_task_cand (workspace_id, task_no, candidate_key),
  KEY idx_mine (workspace_id, candidate_key, is_open, due_at_ms)
);

CREATE TABLE proc_approval (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  approval_no VARCHAR(40) NOT NULL,
  instance_no VARCHAR(40) NOT NULL, gate_status VARCHAR(64) NOT NULL,   -- 挂在哪个状态的门上
  pending_key VARCHAR(120) NULL,                    -- 待审时 = instance_no:gate_status，否则 NULL → 同一门同时至多一张待审
  current_round INT NOT NULL DEFAULT 1, current_step VARCHAR(64) NULL,
  status VARCHAR(16) NOT NULL,                      -- PENDING / APPROVED / REJECTED / WITHDRAWN
  requested_by VARCHAR(80) NOT NULL, requested_at_ms BIGINT NOT NULL,
  finished_at_ms BIGINT NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_no (workspace_id, approval_no),
  UNIQUE KEY uk_pending (workspace_id, pending_key),
  KEY idx_instance (workspace_id, instance_no),
  KEY idx_list (workspace_id, status, requested_at_ms),
  KEY idx_requester (workspace_id, requested_by, requested_at_ms)
);

CREATE TABLE proc_approval_round (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  approval_no VARCHAR(40) NOT NULL, round_no INT NOT NULL,
  plan JSON NOT NULL,                               -- 本轮实际要走的步骤（被条件跳过的不在内）
  fields_snapshot JSON NULL,
  approvers_snapshot JSON NULL,                     -- 会签时进入各步的组员快照
  submitted_by VARCHAR(80) NOT NULL, submitted_at_ms BIGINT NOT NULL,
  result VARCHAR(16) NULL, finished_at_ms BIGINT NULL,
  UNIQUE KEY uk_round (workspace_id, approval_no, round_no)
);

CREATE TABLE proc_sla_clock (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  clock_id VARCHAR(40) NOT NULL,
  instance_no VARCHAR(40) NOT NULL,
  policy_code VARCHAR(64) NOT NULL, policy_version VARCHAR(16) NOT NULL,
  metric_code VARCHAR(32) NOT NULL,                 -- FIRST_RESPONSE / RESOLUTION / 自定义
  target_ms BIGINT NULL, calendar_code VARCHAR(64) NULL,
  state VARCHAR(16) NOT NULL,                       -- PENDING / RUNNING / PAUSED / BREACHED / MET / CANCELLED
  started_at_ms BIGINT NULL, due_at_ms BIGINT NULL, warn_at_ms BIGINT NULL,
  paused_ms BIGINT NOT NULL DEFAULT 0, paused_since_ms BIGINT NULL,
  breached_at_ms BIGINT NULL, stopped_at_ms BIGINT NULL,
  epoch INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_clock (workspace_id, clock_id),
  UNIQUE KEY uk_metric (workspace_id, instance_no, policy_code, metric_code),
  KEY idx_due (state, due_at_ms)                    -- 兜底扫描（跨空间）
);

CREATE TABLE proc_transition_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  entity_kind VARCHAR(16) NOT NULL,                 -- INSTANCE / TASK / APPROVAL / SLA / GATE
  entity_no VARCHAR(40) NOT NULL,
  instance_no VARCHAR(40) NOT NULL,
  subject_type VARCHAR(64) NULL, subject_ref VARCHAR(128) NULL,
  action VARCHAR(64) NOT NULL,                      -- 迁移代号 / claim / decide / breached …
  from_value VARCHAR(64) NULL, to_value VARCHAR(64) NULL,
  seq INT NULL,
  actor VARCHAR(80) NOT NULL, on_behalf_of VARCHAR(80) NULL,
  reason VARCHAR(1000) NULL, payload JSON NULL,
  at_ms BIGINT NOT NULL,
  KEY idx_instance (workspace_id, instance_no, at_ms),
  KEY idx_subject (workspace_id, subject_type, subject_ref, at_ms)
);
