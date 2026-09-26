-- dict_item 的三个 v1 旧列退役 —— 它们让「新建字典项」必然 500。
--
-- 症状：`Field 'dict_type' doesn't have a default value`。
-- 成因与 V83 那 10 列同一批：V13 按「约定:业务键」给 dict_item 补了新列
-- （dict_no / group_code / code / label / label_en / label_ar / enabled），
-- 实体 DictItem 改用新列，而 v1 的 dict_type / dict_key / dict_value **没退役**，
-- 仍是 NOT NULL 无默认。实体不映射它们 → MyBatis-Plus 拼的 INSERT 不带这三列 → 库拒。
--
-- 为什么 V83 那轮没带上它：`OrphanNotNullColumnTest` 的 @TableName 正则只认
-- `@TableName("x")` 一种写法，而 DictItem 写的是 `@TableName(value = "x", excludeProperty = ...)`
-- —— 整个实体被跳过，这张表被当成「没有实体的表」豁免，卡口对它从未生效。
-- 正则已在同一提交里补齐（三种写法都认，含只写 excludeProperty 时按类名推）。
--
-- 改成可空而不是 DROP：同 V83 的理由 —— 真实环境里可能有历史值，置空是能让 INSERT
-- 通过的最小改动，不丢数据。列注释标 retired，下一轮清理按注释找得到。
--
-- 遗留的 `uk_dict (dict_type, dict_key)` 不动：三列退役后没有写入方，
-- 而 MariaDB 的 UNIQUE 允许多个 NULL，这个索引不会再拦任何插入。
ALTER TABLE dict_item MODIFY COLUMN dict_type  VARCHAR(32)  NULL COMMENT 'retired: v1 旧列，实体已改用 group_code（V13）';
ALTER TABLE dict_item MODIFY COLUMN dict_key   VARCHAR(64)  NULL COMMENT 'retired: v1 旧列，实体已改用 code（V13）';
ALTER TABLE dict_item MODIFY COLUMN dict_value VARCHAR(128) NULL COMMENT 'retired: v1 旧列，实体已改用 label（V13）';
