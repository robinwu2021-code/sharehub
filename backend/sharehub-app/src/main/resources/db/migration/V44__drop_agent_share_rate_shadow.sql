-- ============================================================
-- powerbank · 删掉 agt_agent.share_rate 影子列
--
-- 2026-09-23 上午做「实体有字段、迁移没建列」的全量清零时，看到实体有 `shareRate`
-- 而库里没有 `share_rate`，就在 V42 里补了一列。**补错了**：
-- 这张表 V1 起就有 `default_share_rate`，实体那个字段说的是同一件事，
-- 只是字段名按驼峰推导不出列名。
--
-- 后果正是 `entity-column-diff.py` 的注释里警告过的那种：
-- 新值写进影子列、真列 `default_share_rate` 恒为 0 —— 界面显示正常，
-- 而任何读真列的地方拿到的都是 0，且没有任何报错。
--
-- 修法：实体用 `@TableField("default_share_rate")` 指回真列；先把影子列里的值搬回去，
-- 再删列。搬回去是必须的 —— 演示数据的代理分润比例现在只存在影子列里。
-- ============================================================
SET NAMES utf8mb4;

UPDATE agt_agent
   SET default_share_rate = share_rate
 WHERE share_rate IS NOT NULL
   AND (default_share_rate IS NULL OR default_share_rate = 0);

ALTER TABLE agt_agent DROP COLUMN IF EXISTS share_rate;
