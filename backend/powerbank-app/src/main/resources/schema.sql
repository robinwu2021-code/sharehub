-- C 端用户与多渠道身份表（自建，幂等）。对齐 docs/technical/ddl/pb_core-user-ad-workorder.sql。
-- 由 spring.sql.init(mode=always) 每次启动执行；IF NOT EXISTS 保证幂等，不影响既有 loc_ 表。

CREATE TABLE IF NOT EXISTS usr_user (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  c_user_no    VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  openid       VARCHAR(64)      NULL,
  unionid      VARCHAR(64)      NULL,
  nickname     VARCHAR(64)      NULL,
  avatar       VARCHAR(256)     NULL,
  credit_score INT          NOT NULL DEFAULT 600,
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_c_user_no (c_user_no),
  KEY idx_user_openid (tenant_id, openid),
  KEY idx_user_unionid (unionid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 运营端动态权限：角色/权限码/角色-权限/数据范围/菜单（幂等，数据由 IamSeeder 灌）
CREATE TABLE IF NOT EXISTS iam_role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, role_no VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  code VARCHAR(32) NOT NULL, name VARCHAR(64) NOT NULL, builtin TINYINT(1) NOT NULL DEFAULT 0,
  data_scope VARCHAR(16) NOT NULL DEFAULT 'ALL', scope_refs VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version BIGINT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_role_no (role_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS iam_permission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, code VARCHAR(64) NOT NULL, module VARCHAR(32) NOT NULL, name VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), UNIQUE KEY uk_perm_code (code), KEY idx_perm_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS iam_role_perm (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, role_no VARCHAR(36) NOT NULL, perm_code VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), UNIQUE KEY uk_role_perm (role_no, perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS iam_data_scope (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, subject_type VARCHAR(16) NOT NULL, subject_no VARCHAR(36) NOT NULL,
  scope_type VARCHAR(16) NOT NULL, scope_refs VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), UNIQUE KEY uk_scope_subject (subject_type, subject_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话表（仅 token-store=mysql 模式用；memory/redis/ehcache 模式不落库）
CREATE TABLE IF NOT EXISTS sys_token (
  token VARCHAR(64) NOT NULL, realm VARCHAR(16) NOT NULL, subject_no VARCHAR(64) NULL,
  role_nos VARCHAR(256) NULL, perm_stamp BIGINT NOT NULL DEFAULT 0, payload TEXT NOT NULL,
  expire_at DATETIME(3) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (token), KEY idx_token_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS iam_menu (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, menu_no VARCHAR(36) NOT NULL, parent_no VARCHAR(36) NULL,
  name VARCHAR(64) NOT NULL, name_ar VARCHAR(64) NULL, type VARCHAR(8) NOT NULL DEFAULT 'MENU',
  path VARCHAR(128) NULL, icon VARCHAR(32) NULL, sort INT NOT NULL DEFAULT 0, perm VARCHAR(64) NULL,
  visible TINYINT(1) NOT NULL DEFAULT 1, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), UNIQUE KEY uk_menu_no (menu_no), KEY idx_menu_parent (parent_no, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS usr_identity (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  c_user_no    VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  provider     VARCHAR(16)  NOT NULL,
  provider_uid VARCHAR(128) NOT NULL,
  union_key    VARCHAR(128) NOT NULL,
  bound_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_identity_provider_uid (tenant_id, provider, provider_uid),
  KEY idx_identity_union (tenant_id, union_key),
  KEY idx_identity_user (c_user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
