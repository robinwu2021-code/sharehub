-- 客服手工建单允许不带用户号。
--
-- cs_ticket.c_user_no 是 NOT NULL 无默认值，而同一张表的 order_no / cabinet_no /
-- problem_no 都可空 —— 它是唯一的例外。DTO 注释早就写明了意图：
-- 「来电的人未必报得出账号，而『登记不下来』比『记录里缺个账号』糟得多」，
-- 运营端那一格也没标必填。列从来没跟上，于是不带账号建单直接 500。
--
-- NULL 的语义就是注释里那句：来电者没报账号。不是「未知」也不是「待补」，
-- 所以不给默认空串 —— 空串会让「没报」和「报了个空」在报表里混成一类。
ALTER TABLE cs_ticket MODIFY COLUMN c_user_no VARCHAR(36) NULL COMMENT 'C 端用户号；NULL = 来电者未报账号（手工登记）';
