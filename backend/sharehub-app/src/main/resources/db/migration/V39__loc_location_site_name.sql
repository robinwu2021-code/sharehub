-- ============================================================
-- powerbank · 补 loc_location.site_name（V35 漏掉的同类项）
--
-- 背景：实体 LocLocation 有 siteName 字段，而**任何迁移都没建过这一列** ——
--       本机库里的是手工加的，干净库（生产）上一 SELECT 就报 Unknown column，
--       点位列表整个 500。V35 已给 loc_site / loc_contract 补过同类冗余名，漏了这张表。
--
-- 为什么名字可以冗余、而计数不行：名字变化极少且变了也不影响钱；
-- 计数是关系的聚合，存成列必然与实际脱节（db-design §1.4）。
-- 所以本脚本只补名字，pointCount/cabinetCount 改为查询时现算（见 LocService）。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE loc_location
  ADD COLUMN IF NOT EXISTS site_name VARCHAR(128) NULL COMMENT '站点名（冗余自 loc_site.name，仅展示）';

UPDATE loc_location l
SET l.site_name = (SELECT s.name FROM loc_site s WHERE s.site_no = l.site_no AND s.deleted = 0)
WHERE l.site_name IS NULL AND l.site_no IS NOT NULL;
