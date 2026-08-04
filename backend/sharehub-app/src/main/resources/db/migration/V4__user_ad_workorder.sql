-- 自动生成，勿手改：源文件 docs/technical/ddl/pb_core-user-ad-workorder.sql
-- 重新同步：python3 backend/scripts/sync-migrations.py
-- 已做的转换：剥离 USE/CREATE DATABASE · 幂等化(IF [NOT] EXISTS) · 剥离 AFTER 子句 ·
--            保留字列名加反引号

-- ============================================================
-- powerbank · pb_core 建表：用户 usr_/coupon_ · 营销广告 ad_ · 工单 wo_ · 平台 notify_/dict_
-- 对齐 db-design.md §3.4/§6/§6.1 / 系统领域模型 D10-D13
-- ============================================================
SET NAMES utf8mb4;

-- ---------- D10 用户 usr_ / coupon_（PII 落 pb_pii）----------
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
  KEY idx_user_openid (tenant_id, openid),  -- 唯一性移交 usr_identity；openid/unionid 仅主渠道冗余展示
  KEY idx_user_unionid (unionid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端用户(多渠道登录,身份见 usr_identity)';

-- 多渠道身份绑定：App/小程序/H5 各渠道身份落此表，unionid 归并同一 c_user_no（TDD-认证鉴权-实现细节 Part B）
CREATE TABLE IF NOT EXISTS usr_identity (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  c_user_no    VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  provider     VARCHAR(16)  NOT NULL              COMMENT 'WECHAT_MP/WECHAT_OA/APPLE/GOOGLE/PHONE',
  provider_uid VARCHAR(128) NOT NULL              COMMENT 'openid / apple·google sub / hash(phone)',
  union_key    VARCHAR(128) NOT NULL              COMMENT '微信 unionid，或 provider:uid（跨渠道归并键）',
  bound_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_identity_provider_uid (tenant_id, provider, provider_uid),
  KEY idx_identity_union (tenant_id, union_key),
  KEY idx_identity_user (c_user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端多渠道身份绑定(登录归并)';

CREATE TABLE IF NOT EXISTS usr_credit (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  c_user_no   VARCHAR(36) NOT NULL,
  tenant_id   VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  score       INT         NOT NULL DEFAULT 600,
  blacklisted TINYINT(1)  NOT NULL DEFAULT 0,
  reason      VARCHAR(256)    NULL,
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_credit_user (c_user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用/黑名单';

CREATE TABLE IF NOT EXISTS usr_wallet (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  wallet_no      VARCHAR(36) NOT NULL,
  tenant_id      VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  c_user_no      VARCHAR(36) NOT NULL,
  balance        DECIMAL(18,2) NOT NULL DEFAULT 0,
  gift_balance   DECIMAL(18,2) NOT NULL DEFAULT 0,
  deposit_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency       VARCHAR(8)  NOT NULL DEFAULT 'AED',
  version        BIGINT      NOT NULL DEFAULT 0,
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_wallet_no (wallet_no),
  UNIQUE KEY uk_wallet_user (c_user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包';

CREATE TABLE IF NOT EXISTS usr_wallet_txn (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  wallet_no  VARCHAR(36) NOT NULL,
  direction  VARCHAR(8)  NOT NULL COMMENT 'IN/OUT',
  amount     DECIMAL(18,2) NOT NULL,
  biz_type   VARCHAR(24)     NULL,
  biz_no     VARCHAR(36)     NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_wtxn_wallet (wallet_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包流水(append)';

CREATE TABLE IF NOT EXISTS coupon_tpl (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tpl_no     VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  name       VARCHAR(64) NOT NULL,
  type       VARCHAR(16) NOT NULL COMMENT 'CUT/DISCOUNT',
  value      DECIMAL(18,2) NOT NULL DEFAULT 0,
  threshold  DECIMAL(18,2) NOT NULL DEFAULT 0,
  valid_rule JSON            NULL,
  stock      INT         NOT NULL DEFAULT 0,
  issued     INT         NOT NULL DEFAULT 0,
  status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  deleted    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tpl_no (tpl_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='券模板';

CREATE TABLE IF NOT EXISTS usr_coupon (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  coupon_no     VARCHAR(36) NOT NULL,
  tenant_id     VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  c_user_no     VARCHAR(36) NOT NULL,
  tpl_no        VARCHAR(36) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED/EXPIRED',
  used_order_no VARCHAR(36)     NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_no (coupon_no),
  KEY idx_coupon_user (c_user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户券';

CREATE TABLE IF NOT EXISTS usr_membership (
  id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  mbr_no    VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  c_user_no VARCHAR(36) NOT NULL,
  plan_no   VARCHAR(36) NOT NULL,
  start_at  DATE            NULL,
  end_at    DATE            NULL,
  status    VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_mbr_no (mbr_no),
  KEY idx_mbr_user (c_user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员/次卡';

-- ---------- D11 营销广告 ad_（P2 未来,设备投屏）----------
CREATE TABLE IF NOT EXISTS ad_slot (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ad_slot_no  VARCHAR(36) NOT NULL,
  tenant_id   VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  cabinet_no  VARCHAR(36) NOT NULL,
  type        VARCHAR(16) NOT NULL DEFAULT 'SCREEN' COMMENT 'SCREEN/BODY',
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ad_slot_no (ad_slot_no),
  KEY idx_adslot_cabinet (cabinet_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告位';

CREATE TABLE IF NOT EXISTS ad_advertiser (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  advertiser_no VARCHAR(36) NOT NULL,
  tenant_id     VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  name          VARCHAR(128) NOT NULL,
  contact       VARCHAR(64)     NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_advertiser_no (advertiser_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告主';

CREATE TABLE IF NOT EXISTS ad_campaign (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  campaign_no   VARCHAR(36) NOT NULL,
  tenant_id     VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  advertiser_no VARCHAR(36) NOT NULL,
  budget        DECIMAL(18,2) NOT NULL DEFAULT 0,
  target        JSON            NULL COMMENT '定向 region/site/scene',
  start_at      DATE            NULL,
  end_at        DATE            NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT      NOT NULL DEFAULT 0,
  deleted       TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_campaign_no (campaign_no),
  KEY idx_campaign_adv (advertiser_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告活动';

CREATE TABLE IF NOT EXISTS ad_creative (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  creative_no VARCHAR(36) NOT NULL,
  campaign_no VARCHAR(36) NOT NULL,
  media_url   VARCHAR(512)    NULL,
  duration    INT             NULL,
  mime        VARCHAR(32)     NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_creative_no (creative_no),
  KEY idx_creative_campaign (campaign_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告创意';

CREATE TABLE IF NOT EXISTS ad_placement (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  placement_no VARCHAR(36) NOT NULL,
  campaign_no  VARCHAR(36) NOT NULL,
  creative_no  VARCHAR(36) NOT NULL,
  ad_slot_no   VARCHAR(36) NOT NULL,
  schedule     JSON            NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_placement_no (placement_no),
  KEY idx_placement_slot (ad_slot_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告投放排期';

CREATE TABLE IF NOT EXISTS ad_impression (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  placement_no VARCHAR(36) NOT NULL,
  cabinet_no   VARCHAR(36)     NULL,
  played_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  duration     INT             NULL,
  PRIMARY KEY (id),
  KEY idx_impr_placement_time (placement_no, played_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告曝光(append,月分区)';

-- ---------- D12 工单 wo_ ----------
CREATE TABLE IF NOT EXISTS wo_order (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  wo_no       VARCHAR(36) NOT NULL,
  tenant_id   VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  region_id   VARCHAR(36)     NULL,
  type        VARCHAR(16) NOT NULL COMMENT 'FAULT/REFILL/INSPECT/INSTALL/REMOVE/COMPLAINT/CLEAN',
  source      VARCHAR(16) NOT NULL COMMENT 'ALERT/USER/VENUE/MANUAL',
  priority    VARCHAR(8)  NOT NULL DEFAULT 'MEDIUM',
  cabinet_no  VARCHAR(36)     NULL,
  site_no     VARCHAR(36)     NULL COMMENT '站点归属(派单)',
  agent_no    VARCHAR(36)     NULL COMMENT '归属代理(冗余·数据权限过滤)',
  status      VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/DISPATCHED/ACCEPTED/PROCESSING/DONE/AUDITED/CLOSED',
  assignee_id VARCHAR(36)     NULL,
  sla_due_at  DATETIME(3)     NULL,
  description VARCHAR(512)    NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version     BIGINT      NOT NULL DEFAULT 0,
  deleted     TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wo_no (wo_no),
  KEY idx_wo_tenant_status (tenant_id, status),
  KEY idx_wo_site (site_no),
  KEY idx_wo_agent (tenant_id, agent_no),
  KEY idx_wo_assignee (assignee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单(聚合根)';

CREATE TABLE IF NOT EXISTS wo_dispatch (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  wo_no        VARCHAR(36) NOT NULL,
  assignee_id  VARCHAR(36) NOT NULL,
  strategy     VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'NEAREST/LOAD/MANUAL/GRAB',
  action       VARCHAR(24)     NULL,
  dispatched_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_wdisp_wo (wo_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='派单记录';

CREATE TABLE IF NOT EXISTS wo_sla (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  wo_no          VARCHAR(36) NOT NULL,
  respond_due_at DATETIME(3)     NULL,
  resolve_due_at DATETIME(3)     NULL,
  respond_breached TINYINT(1) NOT NULL DEFAULT 0,
  resolve_breached TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sla_wo (wo_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA 计时';

CREATE TABLE IF NOT EXISTS wo_handle (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  wo_no         VARCHAR(36) NOT NULL,
  assignee_id   VARCHAR(36) NOT NULL,
  photos        JSON            NULL,
  note          VARCHAR(512)    NULL,
  part_changed  TINYINT(1)  NOT NULL DEFAULT 0,
  device_changed TINYINT(1) NOT NULL DEFAULT 0,
  handled_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_whandle_wo (wo_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='现场处理';

CREATE TABLE IF NOT EXISTS wo_inspection_plan (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  plan_no     VARCHAR(36) NOT NULL,
  tenant_id   VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  route       JSON            NULL COMMENT '站点/点位路线',
  cron        VARCHAR(64)     NULL,
  assignee_id VARCHAR(36)     NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_insp_plan_no (plan_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡检计划';

-- ---------- D13 平台支撑 notify_/dict_/md_ ----------
CREATE TABLE IF NOT EXISTS notify_template (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  template_no VARCHAR(36) NOT NULL,
  tenant_id   VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  channel     VARCHAR(16) NOT NULL COMMENT 'PUSH/SMS/SUBSCRIBE/INAPP',
  code        VARCHAR(64) NOT NULL,
  content     TEXT            NULL,
  content_ar  TEXT            NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_notify_tpl (tenant_id, channel, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知模板(多语)';

CREATE TABLE IF NOT EXISTS dict_item (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dict_type  VARCHAR(32) NOT NULL,
  dict_key   VARCHAR(64) NOT NULL,
  dict_value VARCHAR(128) NOT NULL,
  value_ar   VARCHAR(128)    NULL,
  sort       INT         NOT NULL DEFAULT 0,
  status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dict (dict_type, dict_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='参数字典';

CREATE TABLE IF NOT EXISTS md_region (
  id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  region_id VARCHAR(36) NOT NULL,
  name      VARCHAR(64) NOT NULL,
  name_ar   VARCHAR(64)     NULL,
  parent_id VARCHAR(36)     NULL,
  level     INT         NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_region_id (region_id),
  KEY idx_region_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='地区库';

-- ============================================================
-- pb_pii（独立库/KMS · PDPL）· pb_auth（独立库/KMS · auth-core）
-- ============================================================
-- CREATE DATABASE pb_pii; USE pb_pii;
-- CREATE TABLE IF NOT EXISTS pii_user ( id ..., c_user_no VARCHAR(36), tenant_id VARCHAR(36),
--   phone VARBINARY(256) /*KMS*/, real_name VARBINARY(256) /*KMS*/, id_no VARBINARY(256) /*KMS*/,
--   UNIQUE KEY uk_pii_user (c_user_no) );
-- CREATE DATABASE pb_auth; USE pb_auth;   -- 结构由 neargo-auth-core 定义
-- CREATE TABLE IF NOT EXISTS cred_credential ( id ..., cred_no VARCHAR(36), realm VARCHAR(16) /*STAFF/CONSUMER/AGENT*/,
--   login_type VARCHAR(16), identifier VARCHAR(128), secret VARBINARY(256) /*KMS*/, status VARCHAR(16),
--   UNIQUE KEY uk_cred (realm, login_type, identifier) );
