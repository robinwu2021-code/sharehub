-- ============================================================
-- V178 iam_role.code 的列注释不再写成固定词表
--
-- 原注释把七个内置角色码写成「A/B/C」词表形，于是 StoredValueInVocabularyTest 与
-- VocabularyWriteCoverageTest 把它当固定枚举查 —— 而角色码是**可扩展**的：IamSeeder 种了一个
-- 自定义角色样例（code=CUSTOM），运营也能建自定义角色（V135 起权限码进目录就是为了勾选自定义角色）。
-- 词表卡口的台账里早就记着「iam_role.code 是可扩展的角色码，不算固定枚举」，只是注释没跟上。
-- 内置角色清单的唯一真源是代码里的 RolePerms，不在注释里重复。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
ALTER TABLE iam_role
  MODIFY COLUMN code VARCHAR(32) NOT NULL COMMENT '角色码：内置角色与自定义角色共用，可扩展，不是固定词表（内置清单见 RolePerms）';
