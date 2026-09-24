-- ============================================================
-- ShareHub · iam_menu.type 的列注释描述的是一套没人实现过的分类
--
-- 【注释与实现从一开始就对不上】
-- V5 建表时写的是 DOMAIN(L1)/MODULE(L2)/MENU(L3)/DEEPLINK，
-- 而代码里实现的一直是两档：MENU（分组）/ ITEM（叶子）——
--   · IamSeeder 建的分组行写 MENU；
--   · V67（菜单真源迁进 iam_menu）建的叶子行写 ITEM，
--     实体 IamEntities.IamMenu 的字段注释也写着「MENU（一级）/ ITEM（叶子）」。
-- 注意 MENU 这个词两边都有、意思却相反：注释里它是 L3 叶子，实现里它是分组。
--
-- DOMAIN / MODULE / DEEPLINK 三个值**全仓库再无第二处出现**
-- （只有 db-schema-reference.md 里那一行，而那是从本注释生成的，不算旁证）。
--
-- 【为什么现在才发现】
-- 这一列此前只有 18 行分组数据，全是 MENU —— 恰好落在注释词表里（虽然含义不同）。
-- V67 把 109 行叶子迁进来之后，ITEM 第一次出现，
-- StoredValueInVocabularyTest（拿库里实际存的值跟列注释比）当场报红。
-- **不是 V67 引入的问题，是 V67 让一个一直存在的问题有了症状。**
--
-- 【改注释而不是改数据】
-- 实现是自洽的：分组/叶子两档，运营端菜单树就按这两档渲染。
-- 而那套四档分类没有任何代码、契约或需求文档支撑 —— 改它，不让实现去迁就一句注释。
-- （判据同 V64/V65/V66：以证据多的那一边为准。这次证据全在实现这边。）
--
-- 【没有一并处理的】
-- iam_menu.status 至今没有列注释（仍在 known-undocumented-status-columns.txt）：
-- 全部 127 行都是 ACTIVE，MenuService 只按 ACTIVE 过滤，而**没有任何代码写第二个值**。
-- 词表是什么无从判断，不猜。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE iam_menu
    MODIFY COLUMN type VARCHAR(8) NOT NULL DEFAULT 'MENU' COMMENT 'MENU(分组)/ITEM(叶子)';
