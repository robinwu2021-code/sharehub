-- 区域字典的种子。
--
-- `md_region` 一行都没有 —— 没有迁移灌过它，seed 包里也没有。而「新增站点」的
-- 区域是**必填**、且只列 level=3 的叶子层（挂到「阿联酋」这种国家节点等于没收敛数据权限），
-- 于是那个下拉永远是空的，**站点在真后端建不出来**。本地跑 mock 看不出来：
-- mock 的 system.ts 里一直有这 12 行。
--
-- 所以这里灌的就是 mock 那一份，两边同一套编号 —— 否则前端测试用的 `AE-DU`、
-- `DU-DT` 这些 ID 在真后端一个都不存在，接入时又是一轮「本地好的、线上空的」。
--
-- 区域属**参照数据**不是演示数据：它必须在生产存在，所以走迁移而不是 seeder
-- （同 V91 灌权限目录的口径）。
--
-- name_ar 留空：阿语译名要本地化的人给，编一个比空着糟 —— 空着界面回落中文名，
-- 编错了就是错的却看不出来。level 1/2 的 name_en 是客观译名，一并给上。
INSERT IGNORE INTO md_region (region_id, name, name_en, parent_id, level, city_count) VALUES
  ('AE',     '阿联酋',   'United Arab Emirates', NULL,    1, 7),
  ('AE-DU',  '迪拜',     'Dubai',                'AE',    2, 1),
  ('AE-AZ',  '阿布扎比', 'Abu Dhabi',            'AE',    2, 1),
  ('AE-SH',  '沙迦',     'Sharjah',              'AE',    2, 1),
  ('AE-AJ',  '阿治曼',   'Ajman',                'AE',    2, 1),
  ('DU-MAR', 'Dubai Marina',   NULL, 'AE-DU', 3, 0),
  ('DU-DEI', 'Deira',          NULL, 'AE-DU', 3, 0),
  ('DU-DT',  'Downtown Dubai', NULL, 'AE-DU', 3, 0),
  ('DU-DXB', 'DXB 机场',       NULL, 'AE-DU', 3, 0),
  ('AZ-YAS', 'Yas Island',     NULL, 'AE-AZ', 3, 0),
  ('AZ-COR', 'Corniche',       NULL, 'AE-AZ', 3, 0),
  ('SH-CIT', 'Sharjah City',   NULL, 'AE-SH', 3, 0);
