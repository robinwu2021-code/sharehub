-- V35：补齐 loc_site / loc_contract 的**冗余快照名列**。
--
-- 背景：LocSite 实体有 venueName、LocContract 实体有 venueName + siteName，
-- 但对应表里只有 venue_no / site_no（外键号，非名）—— 实体/schema 长期漂移，
-- 导致 LocSeeder / LocService.create / ReportMappers.activeRates 分别踩过：
--   - LocSite:      Unknown column 'venue_name' in 'INSERT INTO'
--   - LocContract:  Unknown column 'c.site_name' in 'SELECT'（首页看板 SQL）
--
-- 语义：这两列是**「不随源改名回溯」的快照**（对齐 LocSite.venueName 注释的原意），
-- 由 LocService / LocSeeder 在写入时冗余落入。空 = 从未回填（进件流程尚未接上），
-- 报表 SQL 用 IS NOT NULL 兜底（见 ReportMappers.activeRates）。
--
-- 语法：项目其它迁移都用 MariaDB 的 ADD COLUMN IF NOT EXISTS（部署跑在 MariaDB
-- 12.3.2；MySQL 9 不支持），保持一致以便任何环境重跑幂等。

ALTER TABLE loc_site
  ADD COLUMN IF NOT EXISTS venue_name VARCHAR(128) NULL COMMENT '场地方名快照(冗余·不随源改名回溯)';

ALTER TABLE loc_contract
  ADD COLUMN IF NOT EXISTS venue_name VARCHAR(128) NULL COMMENT '场地方名快照(冗余·不随源改名回溯)',
  ADD COLUMN IF NOT EXISTS site_name  VARCHAR(128) NULL COMMENT '站点名快照(冗余·不随源改名回溯)';
