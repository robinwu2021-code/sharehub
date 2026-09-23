-- ============================================================
-- ShareHub · 商机归属能记到伙伴头上（ADR-027 §五 / TDD-A2 第 3 批）
--
-- 【解决什么】
-- `loc_lead.owner` 今天只接受 employee_no —— 商机只能归属自己人。
-- 而「场地是某个代理商谈下来的」是开站常态，拓展佣金的依据就是这件事。
-- 记不下来的话，佣金只能靠人记：谁谈的、谈成没有，全在聊天记录里。
--
-- 【为什么不把 owner 改名成 owner_no（ADR 原文的写法）】
-- 改名要同步动 keywordColumns / filterFields / VO / mock / 前端筛选，
-- 而这些改动**没有一处改变行为**。`owner` 本来就是「归属方的业务号」，
-- 加一列 owner_type 说清它是哪个命名空间的号就够了 ——
-- 一列存号、一列存类型，不存在 ADR 想避免的那种二义。
--
-- 【site_no：拓展归因必须落到站点上才能变成钱】
-- ADR 说「商机 SIGNED 且 owner 是代理商时，自动写一行 loc_site_agent(role=DEVELOP)」。
-- 但**商机上没有站点** —— 它谈的是场地，站点是之后才建的。
-- 没有这一列，SIGNED 那一刻根本不知道该把 DEVELOP 写到哪个站点上。
-- 所以给商机加「最终落成哪个站点」，签下并指定站点后才写责任行；
-- 先签后建站也支持：站点补填上去的那一刻补写。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE loc_lead
  ADD COLUMN IF NOT EXISTS owner_type VARCHAR(16) NOT NULL DEFAULT 'STAFF'
  COMMENT '归属方类型：STAFF=员工(owner 存 employee_no) / AGENT=伙伴(owner 存 agent_no)';

-- 存量商机一律是员工归属（这一列出现之前只可能是员工），显式回填而不是只靠 DEFAULT：
-- DEFAULT 只作用于将来插入的行，已存在的行若为 NULL，判断处会走进「既不是 STAFF 也不是 AGENT」的缝里。
UPDATE loc_lead SET owner_type = 'STAFF' WHERE owner_type IS NULL OR owner_type = '';

ALTER TABLE loc_lead
  ADD COLUMN IF NOT EXISTS site_no VARCHAR(36) NULL
  COMMENT '这条商机最终落成的站点；拓展佣金的责任行写在它身上';

-- 按归属方查「我谈成了哪些」——伙伴门户和佣金核对都要用
ALTER TABLE loc_lead
  ADD KEY IF NOT EXISTS idx_lead_owner_type (owner_type, owner);
