-- 会员权益 / 信用分变更流水 / 券发放记录（前端契约 MemberBenefit / CreditScoreChange / CouponIssueRecord）

SET NAMES utf8mb4;

-- ── 会员等级权益 ──
-- **权益必须随等级单调变好**（写入时由 service 强制）：
-- 黄金比铂金还便宜的话，会员体系当场失去意义。
CREATE TABLE IF NOT EXISTS mbr_benefit (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id       VARCHAR(36)   NULL,
  level           VARCHAR(16)   NOT NULL COMMENT 'SILVER/GOLD/PLATINUM，由低到高',
  name            VARCHAR(32)   NOT NULL COMMENT '等级展示名，与页面徽标共用一份文案',
  rent_discount   DECIMAL(5,4)  NOT NULL DEFAULT 1 COMMENT '租金折扣；0.9=九折，1=不打折',
  free_minutes    INT           NOT NULL DEFAULT 0 COMMENT '每单免费时长（分钟）',
  deposit_free    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否免押金',
  monthly_coupons INT           NOT NULL DEFAULT 0 COMMENT '每月赠券张数',
  points_rate     DECIMAL(8,2)  NOT NULL DEFAULT 1 COMMENT '消费 1 元累计积分数',
  upgrade_points  INT           NOT NULL DEFAULT 0 COMMENT '升到本级所需累计积分；最低档为 0',
  status          VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)   NULL,
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(36)   NULL,
  version         BIGINT        NOT NULL DEFAULT 0,
  deleted         TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mbr_benefit_level (tenant_id, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员等级权益';

-- ── 信用分变更流水（append 表）──
-- **只追加不修改**：分是怎么变到今天这个数的，是风控争议时唯一的依据。
-- 故无 version/deleted。
CREATE TABLE IF NOT EXISTS usr_credit_change (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  change_no     VARCHAR(36)  NOT NULL COMMENT '业务键，前缀 CSC',
  tenant_id     VARCHAR(36)  NULL,
  c_user_no     VARCHAR(36)  NOT NULL,
  score_before  INT          NOT NULL,
  score_after   INT          NOT NULL,
  delta         INT          NOT NULL COMMENT 'after - before；正=加分，负=减分',
  reason        VARCHAR(255) NOT NULL COMMENT '**必填** —— 没有原因的调分等于没有记录',
  operator_name VARCHAR(64)  NULL COMMENT '服务端取登录态，不信入参',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_credit_change_no (change_no),
  KEY idx_credit_change_user (tenant_id, c_user_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用分变更流水（append）';

-- ── 券发放记录（append 表）──
CREATE TABLE IF NOT EXISTS usr_coupon_issue (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  issue_no      VARCHAR(36)  NOT NULL COMMENT '业务键，前缀 CIS',
  tenant_id     VARCHAR(36)  NULL,
  coupon_no     VARCHAR(36)  NOT NULL COMMENT '券模板号（coupon_tpl.tpl_no）',
  coupon_name   VARCHAR(64)  NULL,
  target_type   VARCHAR(24)  NOT NULL COMMENT '人群类型 ALL/SEGMENT/USER_LIST',
  target_desc   VARCHAR(255) NULL COMMENT '人群口径的可读描述（含规模）',
  quantity      INT          NOT NULL DEFAULT 0 COMMENT '本次发放张数',
  operator_name VARCHAR(64)  NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_issue_no (issue_no),
  KEY idx_coupon_issue_tpl (tenant_id, coupon_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='券发放记录（append）';
