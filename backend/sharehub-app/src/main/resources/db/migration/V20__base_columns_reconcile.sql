-- 补齐 BaseEntity 标准列（entity-column-diff 修好后暴露的 17 表 / 33 列）
--
-- 背景：`entity-column-diff.py` 此前**永远报绿** —— 查库缺 -p 密码 → Access denied →
-- 返回空列集 → 主流程当成「表不存在」静默跳过 → 135 张表全被略过。
-- 修好后一次性暴露真实缺口。**一个永远绿的卡口比没有卡口更危险。**
--
-- 分类依据是 db-design 的显式标注，不是猜：
--   · 全局表（§1.3 清单：tenant / iam_permission / dict_ / md_ / gw_vendor）→
--     缺 tenant_id 是**对的**，改为给实体加 @TableName(excludeProperty="tenantId")，本文件不建该列；
--   · 其余表缺 version/deleted/created_at → 库里漏建，本文件补。
--
-- 全部带默认值：存量行不需要回填，加列即完整。

SET NAMES utf8mb4;

-- ── 广告域 ──
ALTER TABLE ad_advertiser
  ADD COLUMN IF NOT EXISTS version BIGINT     NOT NULL DEFAULT 0 COMMENT '乐观锁',
  ADD COLUMN IF NOT EXISTS deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除';

ALTER TABLE ad_slot
  ADD COLUMN IF NOT EXISTS version BIGINT     NOT NULL DEFAULT 0 COMMENT '乐观锁',
  ADD COLUMN IF NOT EXISTS deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除';

-- ad_creative / ad_placement 在 db-design 里本就有 tenant_id（广告内容按租户隔离），
-- 库里漏建 → 补列而非豁免。
ALTER TABLE ad_creative
  ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(36) NULL COMMENT '租户隔离键',
  ADD COLUMN IF NOT EXISTS version   BIGINT      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS deleted   TINYINT(1)  NOT NULL DEFAULT 0;
UPDATE ad_creative SET tenant_id = 'MAIN' WHERE tenant_id IS NULL;

ALTER TABLE ad_placement
  ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(36) NULL COMMENT '租户隔离键',
  ADD COLUMN IF NOT EXISTS version   BIGINT      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS deleted   TINYINT(1)  NOT NULL DEFAULT 0;
UPDATE ad_placement SET tenant_id = 'MAIN' WHERE tenant_id IS NULL;

-- ── 资金域 ──
-- acct_account / stl_settlement 只缺 deleted：账户与结算单不物理删除，但需要软删标记
-- 才能与 BaseEntity 的 @TableLogic 一致（否则 MP 拼出的 WHERE deleted=0 会报错）。
ALTER TABLE acct_account
  ADD COLUMN IF NOT EXISTS deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除';

ALTER TABLE stl_settlement
  ADD COLUMN IF NOT EXISTS deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除';

-- share_record 在 db-design 里**未标 append**，故按普通业务表处理补齐两列。
-- （若后续确认它应为追加表，正确做法是改设计文档 + 给实体加排除，而不是在这里删列。）
ALTER TABLE share_record
  ADD COLUMN IF NOT EXISTS version BIGINT     NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS deleted TINYINT(1) NOT NULL DEFAULT 0;

-- ── 其余业务表 ──
ALTER TABLE notify_template
  ADD COLUMN IF NOT EXISTS version BIGINT     NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS deleted TINYINT(1) NOT NULL DEFAULT 0;

ALTER TABLE usr_coupon
  ADD COLUMN IF NOT EXISTS version BIGINT     NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS deleted TINYINT(1) NOT NULL DEFAULT 0;

ALTER TABLE wo_inspection_plan
  ADD COLUMN IF NOT EXISTS version BIGINT     NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS deleted TINYINT(1) NOT NULL DEFAULT 0;

-- ── 全局表：只补审计/版本列，tenant_id 由实体注解排除 ──
ALTER TABLE dict_item
  ADD COLUMN IF NOT EXISTS created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN IF NOT EXISTS version    BIGINT      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS deleted    TINYINT(1)  NOT NULL DEFAULT 0;

ALTER TABLE md_region
  ADD COLUMN IF NOT EXISTS created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN IF NOT EXISTS version    BIGINT      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS deleted    TINYINT(1)  NOT NULL DEFAULT 0;
