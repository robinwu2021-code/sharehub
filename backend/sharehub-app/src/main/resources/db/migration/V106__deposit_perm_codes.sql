-- 押金处置的三个动作换回真源表的码，并补目录与角色授权。
--
-- 【原状】三个端点用的码与真源表 §4 完全不同：
--   /deposits/{no}/buyout  → order:deposit:update     ← 真源表里根本没有这个码
--   /deposits/{no}/dun     → order:deposit:update     ← 与买断同码
--   /deposits/{no}/release → order:intervene:execute  ← 客服持有的码
--
-- 实测后果**正好反了**：
--   · 财务**不能催缴、不能买断**（order:deposit:update 没有任何角色持有）
--   · 客服**能解冻押金**（order:intervene:execute 是客服的码）——而解冻是放弃用户押金
--   · 买断与催缴同码，正是真源表那条注释要防的事：
--     「合成一个码会让客服为了催缴而拿到买断权」
--
-- 而运营端一直按真源表的码渲染按钮（order:deposit:manage / order:arrears:dun），
-- 那两个码没有任何端点检查 —— 持有的人按钮全亮、点下去全 403。
-- 三套口径：前端一套、后端一套、真源表一套。
--
-- 【改法】以真源表为准（它写得早、写了理由、且前端已经照它实现）：
--   解冻 / 买断 → order:deposit:manage（仅 FIN）
--   催缴       → order:arrears:dun（CS · FIN）
--
-- 本迁移补两件：把两个新码灌进 iam_permission（否则勾选树上授不出去），
-- 以及给 FIN/CS 授权。端点与 RolePerms 的改动在代码里。
SET NAMES utf8mb4;

INSERT INTO iam_permission (code, name, module) VALUES
  ('order:deposit:manage', '押金 解冻/买断', 'order'),
  ('order:arrears:dun',    '欠费 催缴',      'order')
ON DUPLICATE KEY UPDATE name = VALUES(name), module = VALUES(module);

INSERT IGNORE INTO iam_role_perm (role_no, perm_code) VALUES
  ('FINANCE', 'order:deposit:manage'),
  ('FINANCE', 'order:arrears:dun'),
  ('CS',      'order:arrears:dun');
