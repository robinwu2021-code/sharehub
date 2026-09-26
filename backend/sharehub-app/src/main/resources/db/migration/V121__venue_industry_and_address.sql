-- 场地管理优化：行业收成词表 · 场地方与商机补地址坐标。
--
-- ── 一、行业从自由文本收成词表 ────────────────────────────────────
-- `loc_venue.industry` 一直是纯文本框，生产 5 行的实际值是：
--   Emaar Malls（商业地产集团）→ 零售
--   Majid Al Futtaim（商业地产）→ 航空
--   DXB Airports（机场）        → 地产
-- 三行里没有一行是对的。自由文本的问题不是「填错了」——是**填错了没人拦、也没人看得出来**，
-- 而这一列将来要用来按行业看坪效、按行业定价、按行业分派 BD。
--
-- 存量值按字面映射，**不替业务猜**（DXB Airports 映射成「商业地产」看着就是错的，
-- 但那是它本来就填错了；现在有了下拉，改它是一次点击的事）。
--
-- ⚠️ 词表写进列注释，这是本仓库判定词表的唯一依据
-- （StoredValueInVocabularyTest 拿库里实际存的值跟这行注释比）。
--
-- ── 二、场地方与商机补地址坐标 ────────────────────────────────────
-- `loc_venue` 此前**没有任何地址列**；`loc_lead.address` 有地址但没有经纬度。
-- 后者的代价是实打实的：签约转化走
--     SiteReq(..., address, null, null, ...)   ← lng / lat 恒为空
-- 于是转化建出来的站点在地图上没有落点，C 端「找附近」也排不到它 ——
-- 而这件事不报错，只是那个站点悄悄地不出现在任何按距离排的列表里。
--
-- 经纬度用 DECIMAL(10,6) 与 `loc_site.lng/lat` 同型（约 0.1 米精度，足够放柜子）。

-- ── 场地方：地址 + 坐标 ──
ALTER TABLE loc_venue
  ADD COLUMN IF NOT EXISTS address VARCHAR(256) NULL COMMENT '场地方地址（地图选点写入，与 lng/lat 同时更新）' AFTER contact,
  ADD COLUMN IF NOT EXISTS lng DECIMAL(10,6) NULL COMMENT '经度。与 address 必须一致——地址给人看，排序按坐标' AFTER address,
  ADD COLUMN IF NOT EXISTS lat DECIMAL(10,6) NULL COMMENT '纬度' AFTER lng;

-- ── 商机：补坐标（address 早就有） ──
ALTER TABLE loc_lead
  ADD COLUMN IF NOT EXISTS lng DECIMAL(10,6) NULL COMMENT '经度。签约转化时随 address 一起带进新建站点' AFTER address,
  ADD COLUMN IF NOT EXISTS lat DECIMAL(10,6) NULL COMMENT '纬度。同上——此前恒为空，转化出来的站点在地图上没有落点' AFTER lng;

-- ── 行业：存量值归一 ──
-- 顺序无所谓（这几个值互不包含）。没列到的值保持原样，由下面的兜底兜住。
UPDATE loc_venue SET industry = 'RETAIL'        WHERE industry = '零售';
UPDATE loc_venue SET industry = 'AIRPORT'       WHERE industry = '航空';
UPDATE loc_venue SET industry = 'COMMERCIAL_RE' WHERE industry = '地产';
UPDATE loc_venue SET industry = 'F_AND_B'       WHERE industry = '餐饮';
UPDATE loc_venue SET industry = 'SHOPPING_MALL' WHERE industry IN ('购物中心', '商场');
UPDATE loc_venue SET industry = 'HOTEL'         WHERE industry = '酒店';
UPDATE loc_venue SET industry = 'MEDICAL'       WHERE industry IN ('医疗', '医院');
UPDATE loc_venue SET industry = 'OFFICE'        WHERE industry IN ('办公', '写字楼');
UPDATE loc_venue SET industry = 'TRANSPORT_HUB' WHERE industry IN ('交通枢纽', '地铁');
-- 兜底：仍不在词表里的一律归 OTHER。
-- **不能留着不管** —— 留一个词表外的值，StoredValueInVocabularyTest 当场红，
-- 而且运营端的下拉也选不中它（显示空白，一保存就被改成别的值）。
UPDATE loc_venue
   SET industry = 'OTHER'
 WHERE industry IS NOT NULL AND industry <> ''
   AND industry NOT IN ('SHOPPING_MALL','COMMERCIAL_RE','AIRPORT','TRANSPORT_HUB','F_AND_B',
                        'HOTEL','MEDICAL','EDUCATION','OFFICE','ENTERTAINMENT','RETAIL','SCENIC','OTHER');

-- 入驻申请那一列同源同词表（申请通过后会建档成场地方，两边词表不同就会在建档那一刻失配）
UPDATE loc_venue_onboarding SET industry = 'RETAIL'        WHERE industry = '零售';
UPDATE loc_venue_onboarding SET industry = 'AIRPORT'       WHERE industry = '航空';
UPDATE loc_venue_onboarding SET industry = 'COMMERCIAL_RE' WHERE industry = '地产';
UPDATE loc_venue_onboarding SET industry = 'F_AND_B'       WHERE industry = '餐饮';
UPDATE loc_venue_onboarding SET industry = 'SHOPPING_MALL' WHERE industry IN ('购物中心', '商场');
UPDATE loc_venue_onboarding
   SET industry = 'OTHER'
 WHERE industry IS NOT NULL AND industry <> ''
   AND industry NOT IN ('SHOPPING_MALL','COMMERCIAL_RE','AIRPORT','TRANSPORT_HUB','F_AND_B',
                        'HOTEL','MEDICAL','EDUCATION','OFFICE','ENTERTAINMENT','RETAIL','SCENIC','OTHER');

-- ── 行业：词表写进列注释 ──
ALTER TABLE loc_venue
  MODIFY COLUMN industry VARCHAR(32) NULL
  COMMENT '行业词表：SHOPPING_MALL/COMMERCIAL_RE/AIRPORT/TRANSPORT_HUB/F_AND_B/HOTEL/MEDICAL/EDUCATION/OFFICE/ENTERTAINMENT/RETAIL/SCENIC/OTHER';

ALTER TABLE loc_venue_onboarding
  MODIFY COLUMN industry VARCHAR(32) NULL
  COMMENT '行业词表，与 loc_venue.industry 同源：SHOPPING_MALL/COMMERCIAL_RE/AIRPORT/TRANSPORT_HUB/F_AND_B/HOTEL/MEDICAL/EDUCATION/OFFICE/ENTERTAINMENT/RETAIL/SCENIC/OTHER';
