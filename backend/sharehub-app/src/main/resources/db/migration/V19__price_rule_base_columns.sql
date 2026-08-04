-- price_rule 补齐 BaseEntity 标准列
--
-- 为什么现在才暴露：`PriceRule` 实体一直继承 BaseEntity（带 version/deleted），
-- 但**从来没有代码查过这张表** —— M2 的取价链是第一个真正 SELECT 它的地方，
-- 于是「实体声明了但表没有」的不一致立刻炸成 Unknown column 'version'。
--
-- 教训：`entity-column-diff.py` 比对的是「实体字段 ⊆ 表列」，
-- 它报 0 缺口是因为**它按 @TableName 扫实体自有字段，未展开父类 BaseEntity 的继承列**。
-- 这是该脚本的一个真实盲区，已在其文档中记录。

SET NAMES utf8mb4;

ALTER TABLE price_rule
  ADD COLUMN IF NOT EXISTS version    BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁',
  ADD COLUMN IF NOT EXISTS deleted    TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除';
