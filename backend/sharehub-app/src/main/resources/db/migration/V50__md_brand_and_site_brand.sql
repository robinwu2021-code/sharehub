-- ============================================================
-- ShareHub · 品牌落地（链条方案 B1 / 领域模型 §五）
--
-- 【品牌是什么】
-- 运营方对 C 端呈现的经营身份：名称、Logo、App、客服、协议。
-- **归运营方，代理商不得拥有**（领域模型 §一）—— 代理商用运营方的品牌，C 端无感知。
--
-- 【两条 2026-09-23 定的边界】
--  ① **一站一品牌（硬约束）**：brand_no 是 `loc_site` 上的一列，不是关系表。
--     分成、坪效、工单都按站点统计，一站两品牌会让「这笔钱算哪个品牌的」没有答案；
--     而 ADR-028 的取价把 brand_no 当过滤条件，一台设备必须能解出唯一品牌。
--  ② **品牌是呈现层，不是隔离层**（领域模型 §五 方案 A）：
--     设备网络与用户账户全平台共享，**异地归还跨品牌照常**。
--     做成隔离层等于把 ADR-026 刚砍掉的租户换个名字建回来（历史上 tenant.brand_name 就是这么来的）。
--
-- 【market_code 留列不校验】
-- 「品牌绑不绑市场」本期不定：区域→市场→币种那条链（S2）还没做，绑了也验证不了。
-- 留列是因为事后补列要再停一次机，而空列不花钱。
-- ============================================================
SET NAMES utf8mb4;

-- 全局表，**不带 tenant_id**：与 md_bank / dict_item / iam_permission 同族。
-- ADR-026 取消租户后那一列只是历史常量，新表不该再把它建回来。
-- 实体侧用 @TableName(excludeProperty = "tenantId") 排除继承来的字段（md_bank 的注释有详细说明）。
CREATE TABLE IF NOT EXISTS md_brand (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  brand_no    VARCHAR(36)  NOT NULL COMMENT '业务键，前缀 BR',
  name        VARCHAR(64)  NOT NULL COMMENT '品牌名（中文）',
  name_en     VARCHAR(96)  NOT NULL DEFAULT '' COMMENT '品牌名（English）',
  name_ar     VARCHAR(96)  NOT NULL DEFAULT '' COMMENT '品牌名（العربية）',
  logo_url    VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'C 端展示的 Logo',
  support_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '该品牌的客服电话（C 端「联系客服」用）',
  market_code VARCHAR(16)      NULL COMMENT '归属市场；**本期不校验**，待 S2 区域→市场链路',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(36)      NULL,
  updated_by  VARCHAR(36)      NULL,
  archived_at DATETIME(3)      NULL COMMENT '软删（G1：归档而非删除）',
  version     BIGINT       NOT NULL DEFAULT 0,
  deleted     TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_brand_no (brand_no),
  UNIQUE KEY uk_brand_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品牌（运营方对 C 端的经营身份）';

-- 站点以哪个品牌运营。一站一品牌 → 一列。
ALTER TABLE loc_site
  ADD COLUMN IF NOT EXISTS brand_no VARCHAR(36) NULL COMMENT '→ md_brand.brand_no；一站一品牌（硬约束）';
ALTER TABLE loc_site ADD KEY IF NOT EXISTS idx_site_brand (brand_no);

-- 默认品牌：**必须有一个**，否则建站点时「品牌」这个必填项无从选起。
-- 名字取平台名，运营改名即可；不硬编码成「默认品牌」这种一看就是占位的文案。
INSERT INTO md_brand (brand_no, name, name_en, status, created_by, updated_by)
VALUES ('BR-DEFAULT', 'ShareHub', 'ShareHub', 'ENABLED', 'SYSTEM', 'SYSTEM')
ON DUPLICATE KEY UPDATE brand_no = brand_no;

-- 存量站点一律挂默认品牌：留 NULL 的话，取价的 brand 过滤与 C 端呈现都要处理「没有品牌」
-- 这个不存在的业务状态。硬约束就要从第一天起是真的。
UPDATE loc_site SET brand_no = 'BR-DEFAULT' WHERE brand_no IS NULL OR brand_no = '';
