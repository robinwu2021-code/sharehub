-- ============================================================
-- powerbank · 回填分成链上的编号列（M1「分润生成链路」的数据侧）
--
-- 背景：代码补齐之后实测仍然分不出钱 —— 链路上的**编号列全是 NULL**：
--   · loc_site.venue_no      种子只写了 venue_name
--   · loc_contract.site_no / venue_no   同上，只写了名字
-- 于是「订单 → 站点 → 场地方 → 合同费率」这条链在第二跳就断了。
--
-- ⚠️ **本脚本按名字回填** —— 而分润本身绝不按名字连（同名场地方会把钱分错家）。
-- 这里只是一次性把历史数据接上：名字是这批数据里唯一存在的线索。为此加了两道闸：
--   1. 只在**名字唯一命中一行**时才回填（同名的一律留空，宁可缺也不错）；
--   2. 只填 NULL，不覆盖任何已有值。
-- 新数据不走这条路：`SeedData` 与 `LocService.saveSite` 现在都直接写 venue_no。
-- ============================================================
SET NAMES utf8mb4;

-- ── 1. 站点 → 场地方 ──
UPDATE loc_site s
SET s.venue_no = (SELECT v.venue_no FROM loc_venue v
                  WHERE v.name = s.venue_name AND v.deleted = 0)
WHERE s.venue_no IS NULL
  AND s.venue_name IS NOT NULL
  AND (SELECT COUNT(*) FROM loc_venue v WHERE v.name = s.venue_name AND v.deleted = 0) = 1;

-- ── 2. 合同 → 站点 / 场地方 ──
UPDATE loc_contract c
SET c.site_no = (SELECT s.site_no FROM loc_site s
                 WHERE s.name = c.site_name AND s.deleted = 0)
WHERE (c.site_no IS NULL OR c.site_no = '')
  AND c.site_name IS NOT NULL
  AND (SELECT COUNT(*) FROM loc_site s WHERE s.name = c.site_name AND s.deleted = 0) = 1;

UPDATE loc_contract c
SET c.venue_no = (SELECT v.venue_no FROM loc_venue v
                  WHERE v.name = c.venue_name AND v.deleted = 0)
WHERE (c.venue_no IS NULL OR c.venue_no = '')
  AND c.venue_name IS NOT NULL
  AND (SELECT COUNT(*) FROM loc_venue v WHERE v.name = c.venue_name AND v.deleted = 0) = 1;

-- ── 3. 站点经纬度 ──
-- 空坐标的后果不在运营端，是 C 端「找附近的柜」整个入口算不出距离。
-- 按 site_no 末两位散布在迪拜城区网格（25.05~25.28N / 55.12~55.40E），
-- **只填空值** —— 已经有真实坐标的站点不动。
UPDATE loc_site
SET lng = 55.120000 + (CAST(RIGHT(site_no, 2) AS UNSIGNED) % 12) * 0.023000,
    lat = 25.050000 + (CAST(RIGHT(site_no, 2) AS UNSIGNED) % 12) * 0.019000
WHERE (lng IS NULL OR lat IS NULL)
  AND site_no REGEXP '[0-9]{2}$';
