-- 设备库存权限落地（docs/requirements/设备管理-运营端菜单角色功能矩阵.md §六，用户 2026-10-29 Q1 ~ Q5「按建议定」）。
--
-- 只登记现在就有端点强制的两个新码（perm-ssot-align 只拦「声明未强制」）：
--   · device:qc:inspect     —— 质检。此前判 device:cabinet:update / device:powerbank:update，
--                              给仓管质检就等于给了上线、改设备（Q2）
--   · device:inventory:diff —— 资产差异处置。此前判 device:inventory:transfer（发货），
--                              运维收回发货后仍要处置撤机清点差异（Q1）
-- 入库 / 签收 / 调整 / 盘点复核 / 仓库管理 / 库存报表 六个码随 S4 / S6 的端点一起登记。
--
-- 新角色「仓管」WAREHOUSE（Q3），数据范围按区域。运维的 device:inventory:* 暂留（Q1 过渡：仓管有人之前不收回）。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
SET NAMES utf8mb4;

INSERT IGNORE INTO iam_permission (code, name, module) VALUES
  ('device:qc:inspect', '设备质检（到货 / 回仓）', 'device'),
  ('device:inventory:diff', '资产差异 处置', 'device');

INSERT INTO iam_role (role_no, code, name, builtin, data_scope) VALUES
  ('R8', 'WAREHOUSE', '仓管', 1, 'REGION')
ON DUPLICATE KEY UPDATE role_no = role_no;

INSERT IGNORE INTO iam_role_perm (role_no, perm_code) VALUES
  -- 仓管：看设备与库存，到货质检，发货，盘点录入，期初导入
  ('WAREHOUSE', 'dashboard:overview:read'),
  ('WAREHOUSE', 'dashboard:todo:read'),
  ('WAREHOUSE', 'device:cabinet:read'),
  ('WAREHOUSE', 'device:cabinet:import'),
  ('WAREHOUSE', 'device:slot:read'),
  ('WAREHOUSE', 'device:powerbank:read'),
  ('WAREHOUSE', 'device:qc:inspect'),
  ('WAREHOUSE', 'device:inventory:read'),
  ('WAREHOUSE', 'device:inventory:transfer'),
  ('WAREHOUSE', 'device:inventory:stocktake'),
  -- 运维：质检（原经 device:cabinet:* / device:powerbank:* 隐含）
  ('OPS', 'device:qc:inspect'),
  -- 只读：库存、充电宝、台账导出（真源表早已给，实现漏配）
  ('VIEWER', 'device:inventory:read'),
  ('VIEWER', 'device:powerbank:read'),
  ('VIEWER', 'device:cabinet:export'),
  -- 财务：库存只读（资产对账，Q4）
  ('FINANCE', 'device:inventory:read');
