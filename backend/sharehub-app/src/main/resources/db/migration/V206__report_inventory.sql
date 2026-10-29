-- 库存报表（库存缺口 P1 #5）：数据报表 › 库存报表，权限码 report:inventory:read（功能权限清单 §报表 report:*:read）。
-- 一页看全：阶段分布、各仓 × 类型期初 / 入 / 出 / 期末与安全库存、期间处置（按类型 / 原因）、期间采购金额（按币种）。
-- 授权：ADMIN（*）、OPS（与 report:device:read 同组）、WAREHOUSE（仓管看自己的库存账）。
SET NAMES utf8mb4;

INSERT IGNORE INTO iam_permission (code, name, module) VALUES
  ('report:inventory:read', '库存报表 查看', 'report');

INSERT IGNORE INTO iam_role_perm (role_no, perm_code) VALUES
  ('OPS', 'report:inventory:read'),
  ('WAREHOUSE', 'report:inventory:read');

-- 菜单：排在财务报表（sort 3）之后；原 4 起顺延
UPDATE iam_menu SET menu_no = CONCAT('M_tmp206_', id) WHERE parent_no = 'M_report';
UPDATE iam_menu SET sort = sort + 1 WHERE menu_no LIKE 'M_tmp206_%' AND sort >= 4;
UPDATE iam_menu SET menu_no = CONCAT('M_report__', sort) WHERE menu_no LIKE 'M_tmp206_%';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
VALUES ('M_report__4', 'M_report', '库存报表', 'Inventory Report', 'تقرير المخزون', 'ITEM', '/reports?tab=inventory', NULL, NULL, 4,
        'report:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL);
