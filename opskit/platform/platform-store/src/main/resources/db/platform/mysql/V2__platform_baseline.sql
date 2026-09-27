-- 平台基线（21/04 §3.1）。MySQL 版逐字取自设计文档；PG / H2 版由同一份按固定规则转换：
-- 自增 → IDENTITY · JSON → JSONB(PG) · TINYINT → SMALLINT(PG) · MEDIUMTEXT → TEXT(PG) / CLOB(H2) · 表内 KEY → 表外 CREATE INDEX（索引名加表名前缀，两库索引名全库唯一）。
CREATE TABLE plat_account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  kind VARCHAR(16) NOT NULL,                        -- PARTNER / DIRECT
  plan VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
  quotas JSON NULL,                                 -- {workspaces, records, apiRate}
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE / SUSPENDED / ARCHIVED
  created_at_ms BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_account (account_id)
);

CREATE TABLE plat_workspace (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  external_ref VARCHAR(128) NULL,                   -- 伙伴那边的租户号
  default_zone VARCHAR(64) NOT NULL DEFAULT 'UTC',
  default_locale VARCHAR(16) NOT NULL DEFAULT 'zh',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at_ms BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_ws (workspace_id),
  UNIQUE KEY uk_ext (account_id, external_ref)
);

CREATE TABLE plat_app (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, app_id VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  credential_hash VARCHAR(128) NOT NULL, credential_hint VARCHAR(8) NULL,
  all_workspaces TINYINT NOT NULL DEFAULT 0,        -- 1 = 可访问账户下全部空间（伙伴应用）
  callback_base_url VARCHAR(512) NULL,
  signing_secret_enc VARCHAR(512) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at_ms BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_app (app_id),
  UNIQUE KEY uk_cred (credential_hash)
);

CREATE TABLE plat_app_workspace (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  app_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_app_ws (app_id, workspace_id)
);

CREATE TABLE plat_idp (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL,
  issuer VARCHAR(256) NOT NULL,                     -- 令牌 iss → 账户（全局唯一）
  kind VARCHAR(16) NOT NULL,                        -- JWT / INTROSPECT
  config JSON NOT NULL,                             -- {jwksUrl, audience} / {introspectUrl, clientId, clientSecretEnc}
  claim_mapping JSON NOT NULL,                      -- {member, workspace, groups, roles, perms, scopes, attrs}
  jit_enabled TINYINT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_issuer (issuer)
);

CREATE TABLE plat_member (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  member_id VARCHAR(32) NOT NULL,
  external_ref VARCHAR(128) NOT NULL,               -- 宿主里的用户编号
  display_name VARCHAR(128) NULL,
  attrs JSON NULL,                                  -- 租户自定义属性，如 {"partyType":"AGENT"}；不存联系方式明文
  scopes JSON NULL,                                 -- 目录维护的可见标签（与令牌声明取并集）
  source VARCHAR(16) NOT NULL,                      -- JIT / SYNC / CONSOLE
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE / DISABLED
  last_seen_at_ms BIGINT NULL,
  created_at_ms BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_member (workspace_id, member_id),
  UNIQUE KEY uk_ext (workspace_id, external_ref)
);

CREATE TABLE plat_group (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  group_code VARCHAR(64) NOT NULL,
  name JSON NOT NULL,
  kind VARCHAR(16) NOT NULL DEFAULT 'TEAM',         -- TEAM / QUEUE
  claim_timeout_sec INT NULL,                       -- QUEUE 用
  order_by VARCHAR(16) NULL,                        -- QUEUE 用：PRIORITY / DUE / CREATED
  external_ref VARCHAR(128) NULL,
  source VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_group (workspace_id, group_code)
);

CREATE TABLE plat_group_member (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  group_code VARCHAR(64) NOT NULL, member_id VARCHAR(32) NOT NULL,
  source VARCHAR(16) NOT NULL,                      -- CLAIM / SYNC / CONSOLE
  updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_gm (workspace_id, group_code, member_id),
  KEY idx_member (workspace_id, member_id)
);

CREATE TABLE plat_role (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL,
  workspace_id VARCHAR(32) NOT NULL DEFAULT '',     -- '' = 账户级模板角色
  role_code VARCHAR(64) NOT NULL,
  name JSON NOT NULL,
  permissions JSON NOT NULL,                        -- 产品权限目录中的码
  updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_role (account_id, workspace_id, role_code)
);

CREATE TABLE plat_role_binding (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL,
  role_code VARCHAR(64) NOT NULL,
  principal VARCHAR(80) NOT NULL,                   -- member:{id} / group:{code}
  source VARCHAR(16) NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_binding (workspace_id, role_code, principal),
  KEY idx_principal (workspace_id, principal)
);

CREATE TABLE plat_calendar (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL DEFAULT '',
  calendar_code VARCHAR(64) NOT NULL,
  zone VARCHAR(64) NOT NULL,
  weekly JSON NOT NULL,                             -- [{dow, from, to}]
  holidays JSON NULL,
  updated_by VARCHAR(80) NOT NULL, updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_cal (account_id, workspace_id, calendar_code)
);

CREATE TABLE plat_webhook_subscription (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL,
  workspace_id VARCHAR(32) NOT NULL DEFAULT '',     -- '' = 账户下全部空间
  app_id VARCHAR(32) NOT NULL,
  event_type VARCHAR(64) NOT NULL,                  -- '*' 或 'ticket.*'
  url VARCHAR(512) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_sub (account_id, workspace_id, app_id, event_type, url)
);

CREATE TABLE plat_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  idem_key VARCHAR(160) NOT NULL,
  payload MEDIUMTEXT NOT NULL,                      -- 故意不用 JSON：JSON 列会改写内容（MySQL 重排键、PG JSONB 排序加空格），投递的字节会随库不同
  attempts INT NOT NULL DEFAULT 0, next_at_ms BIGINT NOT NULL,
  done_at_ms BIGINT NULL, failed TINYINT NOT NULL DEFAULT 0, last_error VARCHAR(512) NULL,
  created_at_ms BIGINT NOT NULL,
  UNIQUE KEY uk_idem (workspace_id, event_type, idem_key),
  KEY idx_due (done_at_ms, failed, next_at_ms)
);

CREATE TABLE plat_webhook_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL,
  outbox_id BIGINT NOT NULL, subscription_id BIGINT NOT NULL, attempt INT NOT NULL,
  http_status INT NULL, latency_ms INT NULL, error VARCHAR(512) NULL,
  at_ms BIGINT NOT NULL,
  KEY idx_outbox (outbox_id, attempt),
  KEY idx_ws_at (workspace_id, at_ms)
);

CREATE TABLE plat_idempotency (
  caller VARCHAR(80) NOT NULL,                      -- app:{id} / member:{id}
  idem_key VARCHAR(128) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  http_status INT NOT NULL,                         -- 0 = 处理中（占位）
  response_body JSON NULL,
  at_ms BIGINT NOT NULL,
  PRIMARY KEY (caller, idem_key),
  KEY idx_at (at_ms)
);

CREATE TABLE plat_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(32) NOT NULL DEFAULT '',
  actor VARCHAR(80) NOT NULL,                       -- member:/app:/ops:
  action VARCHAR(64) NOT NULL,                      -- definition.published / role.updated / credential.rotated …
  target VARCHAR(160) NOT NULL,
  detail JSON NULL,
  at_ms BIGINT NOT NULL,
  KEY idx_ws_at (account_id, workspace_id, at_ms)
);
