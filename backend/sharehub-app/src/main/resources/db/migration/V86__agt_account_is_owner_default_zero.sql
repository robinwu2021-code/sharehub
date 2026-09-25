-- agt_account.is_owner 的默认值是 1 —— 于是运营端新建的每一个代理账号
-- **默认就是主体属主**，而属主按 [ADR-030 §5.1] 是「全站点全权限、不进授权表」。
--
-- V47 建列时写的是 `NOT NULL DEFAULT 1`。当时只有入驻派生这一条路，
-- 每个主体恰好一个账号，默认 1 看起来没错。
-- 但入驻那条路 **本来就显式 setIsOwner(1)**（ApplyServiceImpl:287），
-- 并不依赖默认值 —— 所以 DEFAULT 1 唯一的实际作用，是让**此外的每个账号也成为属主**。
--
-- 症状为什么看不见：出参 VO `AgentAccount` 不回传这一位，
-- 界面上看不出某个账号是不是属主；而属主又「不进授权表」，
-- 于是也不会出现在任何一张权限报表里。
--
-- 2026-09-25 实测：POST /api/agent/accounts 建出来的账号 is_owner = 1。
--
-- 本迁移只改默认值。**不做批量回填** —— 见下。
ALTER TABLE agt_account
  MODIFY COLUMN is_owner TINYINT(1) NOT NULL DEFAULT 0
  COMMENT '主体属主：全站点全权限、不进授权表（ADR-030 §5.1）。默认 0；属主由入驻审核事务显式置 1';

-- ⚠️ 存量未回填，需要 IAM 线 / 产品裁决后另做一条迁移。
--
-- 为什么不在这里顺手做：把一个真属主误降成普通账号，等于把人锁在自己的生意外面，
-- 而「哪个才是真属主」这张表自己答不出来 —— 全是 1 的时候无从区分。
--
-- 检出有疑问的主体（同一 agent_no 下多于一个属主）：
--   SELECT agent_no, COUNT(*) accounts, SUM(is_owner) owners
--     FROM agt_account GROUP BY agent_no HAVING SUM(is_owner) > 1;
--
-- 一种候选口径（**待确认，勿直接跑**）：每个主体只保留最早创建的那个为属主 ——
--   UPDATE agt_account a JOIN (
--     SELECT agent_no, MIN(created_at) first_at FROM agt_account GROUP BY agent_no
--   ) f ON a.agent_no = f.agent_no
--   SET a.is_owner = 0 WHERE a.created_at > f.first_at;
-- 它假定「入驻派生的那个账号最早」，在有历史数据迁入的库上不一定成立。
