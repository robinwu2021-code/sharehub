-- 自动生成，勿手改：源文件 docs/technical/ddl/pb_core-loc-agt-iam.sql
-- 重新同步：python3 backend/scripts/sync-migrations.py
-- 已做的转换：剥离 USE/CREATE DATABASE · 幂等化(IF [NOT] EXISTS) · 剥离 AFTER 子句 ·
--            保留字列名加反引号

-- ============================================================
-- powerbank · pb_core 建表脚本：场所(loc_) · 代理商(agt_) · 认证权限(iam_)
-- 对齐 db-design.md / 系统领域模型.md / ADR-013(站点→点位) / ADR-012(代理) / 功能权限清单
-- MySQL 8 · InnoDB · utf8mb4_0900_ai_ci
-- 约定：id 库内物理主键；<x>_no 全局业务键(UK)；跨域逻辑引用不建物理 FK，仅索引。
--       金额 DECIMAL(18,2)+currency；比例 DECIMAL(5,4)；时间 DATETIME(3) UTC；软删 deleted。
--       tenant_id 默认 'MAIN'（单运营方，休眠口子 ADR-011）。
-- ============================================================
SET NAMES utf8mb4;

-- ============================================================
-- D4 场所 loc_ ：场地方 → 站点 → 点位 → (机柜)  [ADR-013 两层]
-- ============================================================

-- 场地方（提供物理场地的物业/商户）
CREATE TABLE IF NOT EXISTS loc_venue (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
  venue_no      VARCHAR(36)  NOT NULL                COMMENT '业务键',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN' COMMENT '租户(休眠口子)',
  region_id     VARCHAR(36)      NULL                COMMENT '区域',
  name          VARCHAR(128) NOT NULL                COMMENT '场地方名称',
  name_ar       VARCHAR(128)     NULL                COMMENT '名称(阿语)',
  contact       VARCHAR(64)      NULL                COMMENT '联系方式(明文脱敏;敏感落 pii)',
  industry      VARCHAR(32)      NULL                COMMENT '行业',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_venue_no (venue_no),
  KEY idx_venue_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场地方';

-- 站点/网点（物理经营场所，运营与归属单元）
CREATE TABLE IF NOT EXISTS loc_site (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  site_no       VARCHAR(36)  NOT NULL                COMMENT '业务键',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id     VARCHAR(36)      NULL                COMMENT '区域(数据权限)',
  venue_no      VARCHAR(36)      NULL                COMMENT '归属场地方(逻辑引用)',
  agent_no      VARCHAR(36)      NULL                COMMENT '归属代理(空=平台直营)',
  name          VARCHAR(128) NOT NULL                COMMENT '站点名称',
  name_ar       VARCHAR(128)     NULL,
  address       VARCHAR(256)     NULL,
  lng           DECIMAL(10,6)    NULL,
  lat           DECIMAL(10,6)    NULL,
  scene_type    VARCHAR(32)      NULL                COMMENT '商场/机场/餐饮/地铁/写字楼',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_site_no (site_no),
  KEY idx_site_tenant (tenant_id),
  KEY idx_site_venue (venue_no),
  KEY idx_site_agent (agent_no),
  KEY idx_site_region (region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点/网点';

-- 点位（站点内具体投放位）
CREATE TABLE IF NOT EXISTS loc_location (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  location_no   VARCHAR(36)  NOT NULL                COMMENT '业务键(点位)',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  site_no       VARCHAR(36)  NOT NULL                COMMENT '归属站点',
  agent_no      VARCHAR(36)      NULL                COMMENT '冗余(随站点,便于查询)',
  name          VARCHAR(128) NOT NULL                COMMENT '点位名(如 L1 东门)',
  spot_desc     VARCHAR(256)     NULL                COMMENT '位置描述',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_location_no (location_no),
  KEY idx_loc_tenant (tenant_id),
  KEY idx_loc_site (site_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点位';

-- 进场合同（场地方 × 站点）
CREATE TABLE IF NOT EXISTS loc_contract (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  contract_no   VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  venue_no      VARCHAR(36)  NOT NULL                COMMENT '场地方',
  site_no       VARCHAR(36)  NOT NULL                COMMENT '站点',
  share_rate    DECIMAL(5,4) NOT NULL DEFAULT 0      COMMENT '场地方分成率 0..1',
  entry_fee     DECIMAL(18,2) NOT NULL DEFAULT 0     COMMENT '进场费',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED',
  settle_period VARCHAR(16)      NULL                COMMENT 'MONTH/QUARTER',
  start_at      DATE             NULL,
  end_at        DATE             NULL,
  attach_url    VARCHAR(512)     NULL                COMMENT '合同附件',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_contract_no (contract_no),
  KEY idx_contract_site (site_no),
  KEY idx_contract_venue (venue_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='进场合同';

-- ============================================================
-- D2 代理商 agt_  [ADR-012]
-- ============================================================

-- 代理商（运营方体内经营伙伴）
CREATE TABLE IF NOT EXISTS agt_agent (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_no           VARCHAR(36)   NOT NULL,
  tenant_id          VARCHAR(36)   NOT NULL DEFAULT 'MAIN',
  name               VARCHAR(128)  NOT NULL              COMMENT '代理名称',
  contact            VARCHAR(64)       NULL              COMMENT '敏感明文落 pii',
  region_scope       JSON              NULL              COMMENT '辖域(区域数组)',
  default_share_rate DECIMAL(5,4)  NOT NULL DEFAULT 0    COMMENT '默认分润比例',
  settle_account     VARCHAR(128)      NULL              COMMENT '结算账户(nearpay 收款方)',
  status             VARCHAR(16)   NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/SUSPENDED',
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version            BIGINT        NOT NULL DEFAULT 0,
  deleted            TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_no (agent_no),
  KEY idx_agent_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理商';

-- 代理登录账号（AGENT 角色 + 自己 agent_no 数据范围）
CREATE TABLE IF NOT EXISTS agt_account (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  account_no    VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  agent_no      VARCHAR(36)  NOT NULL              COMMENT '所属代理',
  username      VARCHAR(64)  NOT NULL,
  cred_ref      VARCHAR(64)      NULL              COMMENT 'pb_auth.cred 引用(realm=AGENT)',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agt_account_no (account_no),
  UNIQUE KEY uk_agt_username (tenant_id, username),
  KEY idx_agt_account_agent (agent_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理登录账号';

-- ============================================================
-- D1 认证权限 iam_  [功能权限清单.md] 凭据在 pb_auth
-- ============================================================

-- 员工
CREATE TABLE IF NOT EXISTS iam_employee (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_no   VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  name          VARCHAR(64)  NOT NULL,
  phone         VARCHAR(32)      NULL              COMMENT '敏感明文落 pii',
  dept_no       VARCHAR(36)      NULL              COMMENT '所属部门',
  cred_ref      VARCHAR(64)      NULL              COMMENT 'pb_auth.cred 引用(realm=STAFF)',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/LEFT',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_employee_no (employee_no),
  KEY idx_emp_tenant (tenant_id),
  KEY idx_emp_dept (dept_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工';

-- 角色
CREATE TABLE IF NOT EXISTS iam_role (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  role_no       VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  code          VARCHAR(32)  NOT NULL              COMMENT 'ADMIN/OPS/CS/FINANCE/BD/VIEWER/AGENT',
  name          VARCHAR(64)  NOT NULL,
  builtin       TINYINT(1)   NOT NULL DEFAULT 0    COMMENT '内置角色只读',
  data_scope    VARCHAR(16)  NOT NULL DEFAULT 'ALL' COMMENT 'ALL/REGION/LOCATION/AGENT/SELF',
  scope_refs    JSON             NULL              COMMENT '范围明细(区域/站点/代理 列表)',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_no (role_no),
  UNIQUE KEY uk_role_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色(功能权限集+数据范围)';

-- 权限码字典（全局：模块:资源:动作）
CREATE TABLE IF NOT EXISTS iam_permission (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code          VARCHAR(64)  NOT NULL              COMMENT '如 order:refund:audit',
  module        VARCHAR(32)  NOT NULL              COMMENT '模块前缀',
  name          VARCHAR(64)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_perm_code (code),
  KEY idx_perm_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限码字典';

-- 角色-权限映射
CREATE TABLE IF NOT EXISTS iam_role_perm (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  role_no       VARCHAR(36)  NOT NULL,
  perm_code     VARCHAR(64)  NOT NULL              COMMENT '支持通配 * / device:*',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_perm (role_no, perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限映射';

-- 员工-角色映射
CREATE TABLE IF NOT EXISTS iam_employee_role (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_no   VARCHAR(36)  NOT NULL,
  role_no       VARCHAR(36)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_role (employee_no, role_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工角色映射';

-- 数据范围（可细到员工级覆盖角色默认）
CREATE TABLE IF NOT EXISTS iam_data_scope (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  subject_type  VARCHAR(16)  NOT NULL              COMMENT 'ROLE/EMPLOYEE(员工级覆盖角色默认，二者并集)',
  subject_no    VARCHAR(36)  NOT NULL,
  scope_type    VARCHAR(16)  NOT NULL              COMMENT 'ALL/REGION/SITE/LOCATION/VENUE/AGENT/SELF',
  scope_refs    JSON             NULL              COMMENT '区域/站点/点位/代理 列表',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_scope_subject (subject_type, subject_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据权限范围';

-- 操作审计（只增）
CREATE TABLE IF NOT EXISTS iam_audit_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  actor         VARCHAR(64)      NULL              COMMENT '操作人',
  action        VARCHAR(64)  NOT NULL              COMMENT '权限码/动作',
  target        VARCHAR(128)     NULL              COMMENT '对象业务键',
  detail        JSON             NULL              COMMENT '脱敏摘要',
  ip            VARCHAR(45)      NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_tenant_time (tenant_id, created_at),
  KEY idx_audit_actor (actor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计(append,可按月分区)';

-- ============================================================
-- 种子：内置角色（对齐 functions permissions / ops-web）
-- ============================================================
INSERT INTO iam_role (role_no, code, name, builtin, data_scope) VALUES
 ('R1','ADMIN','运营管理员',1,'ALL'),
 ('R2','OPS','运维',1,'REGION'),
 ('R3','CS','客服',1,'ALL'),
 ('R4','FINANCE','财务',1,'ALL'),
 ('R5','BD','拓展',1,'REGION'),
 ('R6','VIEWER','只读',1,'ALL'),
 ('R7','AGENT','代理商',1,'AGENT')
ON DUPLICATE KEY UPDATE role_no = role_no;   -- 幂等：迁移工具可能重跑，种子不能撞唯一键
