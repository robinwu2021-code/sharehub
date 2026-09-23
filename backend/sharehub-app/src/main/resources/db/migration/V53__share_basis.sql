-- ============================================================
-- ShareHub · 分账加「依据」（ADR-027 §四 / TDD-A2 第 2 批）
--
-- 【解决什么】
-- A2-1 建了 loc_site_agent，运营已能记下「谁出资、谁拓展、谁运维、谁牵线」。
-- 但分账那边还是一个代理一条记录、一个比例 —— 责任配得再细，钱还是按老口径分。
-- 本批给 share_rule / share_record 加 basis（凭什么分这笔钱），
-- 让一个代理在一个站点上可以有多条各有依据的分成。
--
-- 【幂等键是本批最危险的一处】
-- V37 的 uk_srec_order_payee (order_no, dimension, payee_no) 是「outbox 重投不重复记账」
-- 的唯一保证。一单里同一个代理要能有 INVEST + OPERATE 两条，这个键就必须放宽。
-- 顺序：先加列 → 回填 → 建新唯一键 → 再删旧键。**中间任何一步失败，
-- 表上都仍有一个唯一键在**（新键建成前旧键还在，新键建成后才删旧）。
-- 顺序反过来的话，两键之间那一瞬是裸奔的，而正好在那一瞬重投就会多记一笔钱。
--
-- 【新键为什么保留 dimension（与 TDD §2.3 写的三列不同）】
-- TDD 原议 (order_no, payee_no, basis)。但那不是旧键的严格细化：
-- VENUE 行的 payee_no 是场地方号、AGENT 行的是代理号，两个命名空间理论上能撞同一个值，
-- 撞上就是**迁移当场失败**或**日后少记一笔**。保留 dimension 后新键是旧键的严格细化，
-- 存量数据不可能冲突，而「同一代理多依据」照样表达得了。多一列的代价只有索引宽度。
--
-- 【basis 为什么 NOT NULL DEFAULT '' 而不是可空】
-- MariaDB 的唯一索引把 NULL 之间视为互不相同 —— basis 可空的话，
-- 两条 basis 均为 NULL 的记录能同时存在，幂等键**形同虚设且不报错**。
-- 同一个坑 V49 的 uk_scope_target 已经踩过一次（当时实测两行都落库了）。
-- VENUE 维度没有责任细分，其 basis 就留空串 = 「维度本身即依据」。
--
-- 【share_rule 为什么不加唯一键（与 TDD §2.3 也不同）】
-- TDD 原议 (dimension, payee_no, basis) 唯一。但 share_rule 本来就允许同一分成方配多条、
-- 由 priority 取最小者命中（[db-design §5.4]，ShareGeneratorImpl.ruleOf 正依赖它）。
-- 加唯一键等于**悄悄废掉一个在用的能力**，且存量库若已有多条会让迁移直接失败。
-- 这里只把查询索引扩到含 basis，唯一性仍由 rule_no 保证。
-- ============================================================
SET NAMES utf8mb4;

-- ── share_rule：这条规则是「凭什么」的分成 ──
ALTER TABLE share_rule
  ADD COLUMN IF NOT EXISTS basis VARCHAR(16) NOT NULL DEFAULT ''
  COMMENT '分成依据：INVEST/DEVELOP/OPERATE/REFER；VENUE 维度留空';

-- 存量 AGENT 规则回填成 OPERATE：今天那一条代理分成的**实际含义**就是运维分成
-- （站点归谁、谁就在维护）。留空的话取价时匹配不上任何责任行，等于静默少付。
UPDATE share_rule SET basis = 'OPERATE' WHERE dimension = 'AGENT' AND basis = '';

ALTER TABLE share_rule
  ADD KEY IF NOT EXISTS idx_srule_payee_basis (dimension, payee_no, basis);

-- ── share_record：这笔钱是「凭什么」分出去的 ──
ALTER TABLE share_record
  ADD COLUMN IF NOT EXISTS basis VARCHAR(16) NOT NULL DEFAULT ''
  COMMENT '分成依据：INVEST/DEVELOP/OPERATE/REFER；VENUE 维度留空';

-- 存量行同理回填。**必须在建新唯一键之前**：否则同一单同一代理的历史行
-- 与将来按责任生成的行会被判成不同键，同一笔运维分成记两次。
UPDATE share_record SET basis = 'OPERATE' WHERE dimension = 'AGENT' AND basis = '';

-- 先建新键（此刻旧键仍在，约束不间断）
ALTER TABLE share_record
  ADD UNIQUE KEY IF NOT EXISTS uk_srec_order_payee_basis (order_no, dimension, payee_no, basis);

-- 新键就位后才删旧键
ALTER TABLE share_record
  DROP INDEX IF EXISTS uk_srec_order_payee;
