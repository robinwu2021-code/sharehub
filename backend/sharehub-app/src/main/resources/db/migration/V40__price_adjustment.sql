-- ============================================================
-- powerbank · 预约调价（运营管理清单 OM-S4）
--
-- 它解决的问题：节假日上调、活动期降价这类**到点自动改价、到期自动改回**的需求。
-- 没有它，运营只能设个闹钟半夜手工改方案，改完还得记得改回来 —— 忘了就一直按活动价卖。
--
-- ## 为什么要存 before_snapshot
--
-- 恢复原价不能靠「再算一次」：调价生效期间方案可能被人手工改过，
-- 按 patch 反推会把别人的改动一起抹掉。生效那一刻把**被改字段的原值**快照下来，
-- 恢复时只写回这几个字段。
--
-- ## 为什么 patch / snapshot 存 JSON 而不是拆成列
--
-- 可调字段是「方案的一个子集」，会随计费模型演进（将来加能量费、占位费）。
-- 拆成列意味着每加一个可调字段就要改表；而这张表本身不参与任何按字段的查询与聚合 ——
-- 它只按 plan_no / status / 时间点查。
-- ============================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS price_adjustment (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  adjust_no       VARCHAR(36)  NOT NULL COMMENT '业务键，前缀 PA',
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  plan_no         VARCHAR(36)  NOT NULL COMMENT '目标收费方案 price_plan.plan_no',
  name            VARCHAR(128) NOT NULL COMMENT '调价单名称，如「国庆上调」',
  patch           JSON             NULL COMMENT '要改成什么：{freeMinutes,unitMinutes,unitPrice,capDaily,buyoutPrice} 的子集',
  before_snapshot JSON             NULL COMMENT '生效那刻被改字段的原值；未生效为 NULL',
  effective_at    DATETIME(3)  NOT NULL COMMENT '生效时刻(UTC)',
  revert_at       DATETIME(3)      NULL COMMENT '自动恢复时刻(UTC)；NULL = 不自动恢复',
  reason          VARCHAR(255)     NULL COMMENT '调价原因',
  status          VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED/APPLIED/REVERTED/CANCELLED/FAILED',
  applied_at      DATETIME(3)      NULL,
  reverted_at     DATETIME(3)      NULL,
  fail_reason     VARCHAR(255)     NULL COMMENT '执行失败原因；运营据此决定改单还是重试',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)      NULL,
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(36)      NULL,
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_adjust_no (adjust_no),
  -- 调度器每分钟按 (status, 时刻) 扫一遍，这两个索引是它的全部依赖
  KEY idx_pa_due    (status, effective_at),
  KEY idx_pa_revert (status, revert_at),
  KEY idx_pa_plan   (tenant_id, plan_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约调价单';

-- 站点暂停营业的原因。运营端要求必填，而此前无处可存 ——
-- 状态从 ACTIVE 变成 PAUSED 之后，「为什么停」只能靠问人。
ALTER TABLE loc_site
  ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(255) NULL COMMENT '暂停营业原因；恢复营业时清空',
  ADD COLUMN IF NOT EXISTS paused_at    DATETIME(3)  NULL COMMENT '暂停时刻';
