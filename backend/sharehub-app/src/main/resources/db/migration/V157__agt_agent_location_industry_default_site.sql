-- 商家一期 B3（需求方案 D4 / D6 / D11，优化方案 §2.4）：商家档案补核心资料「位置、行业」，并记默认站点。
--
-- 核心资料四项是店名、联系人、位置、行业（需求方案 D6）。前两项建账号时已有（name / contact_name），
-- 这里补后两项。位置 = 地址 + 地图坐标，三者同时写入（地址给人看，排序与「找附近」按坐标）。
--
-- 默认站点：一期不做门店（D11），但分润生成与 C 端「找附近」都硬依赖站点。
-- 所以补齐位置时由系统给商家建一个站点，商家端不出现「站点」这个词；将来做门店时门店就是第二、第三个站点。
--
-- 写法：不用 MariaDB 的 `ADD COLUMN IF NOT EXISTS`，本机 MySQL 9.7 与 MariaDB 都能跑。

ALTER TABLE agt_agent
  ADD COLUMN address         VARCHAR(256)  NULL COMMENT '商家位置（地址）；与 lng/lat 同时写入' AFTER email,
  ADD COLUMN lng             DECIMAL(10,6) NULL COMMENT '经度，与 loc_site.lng 同型' AFTER address,
  ADD COLUMN lat             DECIMAL(10,6) NULL COMMENT '纬度' AFTER lng,
  ADD COLUMN industry        VARCHAR(32)   NULL COMMENT '行业词表，与 loc_venue.industry 同源：SHOPPING_MALL/COMMERCIAL_RE/AIRPORT/TRANSPORT_HUB/F_AND_B/HOTEL/MEDICAL/EDUCATION/OFFICE/ENTERTAINMENT/RETAIL/SCENIC/OTHER' AFTER lat,
  ADD COLUMN default_site_no VARCHAR(36)   NULL COMMENT '系统为商家建的默认站点（补齐位置时自动建，位置变更同步）；空 = 尚未补齐位置' AFTER industry;
