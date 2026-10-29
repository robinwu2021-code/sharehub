-- 导入台账改判 device:cabinet:import（V186 同批，此前判 device:cabinet:create）。
-- 这个码此前没有任何端点强制，所以从未进过 iam_permission 目录；现在强制了，
-- 不在目录里管理员就在角色勾选树上选不到它（PermCatalogCoverageTest）。
-- 名称同步写进 backend/scripts/perm-catalog-names.tsv。
SET NAMES utf8mb4;

INSERT IGNORE INTO iam_permission (code, name, module) VALUES
  ('device:cabinet:import', '机柜台账 导入（期初）', 'device');
