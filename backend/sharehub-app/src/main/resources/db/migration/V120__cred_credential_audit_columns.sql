-- P3b · B2：给 cred_credential 补 created_by / updated_by。
--
-- 为什么会缺：V114 建表时**没有任何代码读写这张表**（B1 只铺地基），
-- 于是「实体继承的 BaseEntity 有这两列、表没有」这件事一次也没发作过。
-- B2 接上第一个调用方的那一刻，登录直接 500：
--   Unknown column 'created_by' in 'SELECT'
-- —— 而它落在**登录**这条路上，等于全站进不去。
--
-- 不改成 @TableField(exist = false)：这张表恰恰最需要「谁建的号、谁重置的口令」，
-- 而这正是审计能回答「凭据是谁发的」的唯一依据。全库其余表都有这两列，
-- 让凭据表成为唯一的例外只会让下一个人再踩一次。
ALTER TABLE cred_credential
  ADD COLUMN created_by VARCHAR(64) NULL COMMENT '建号/重置的操作人' AFTER created_at,
  ADD COLUMN updated_by VARCHAR(64) NULL COMMENT '最后一次改动的操作人' AFTER updated_at;
