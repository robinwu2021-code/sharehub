-- ============================================================
-- ShareHub · 资金表的数据范围锚点
--
-- 【为什么现在才做 —— 也为什么必须现在做】
-- 在代理端实名登录打通之前（V47/V48 + AgentIdentityPort），**没有任何代理能登录**，
-- 所以「资金表没有数据范围」是个够不着的缺口。门一开，它就是可触发的：
-- 实测一个名下零条资金记录的新代理（AG075）能读到
--   · 分润明细 108 行  —— 全平台每个人的分成流水
--   · 收款账户 150 行  —— 全平台的户名与银行账号掩码
-- 后者尤其是自找的：本轮给 AGENT 发了 finance:payout_account:read（为了代理门户
-- 那条「你还不能收款」提示），**码给了，范围没跟上**。
--
-- 【DataScopeRegistration 原本卡在哪】
-- 那里的注释写着 share_record / stl_settlement「需 handler 支持『带条件的锚点』，
-- 直接注册会误伤 VENUE 维度的行」—— 因为 registry 只能把「维度 → 列」一对一登记，
-- 表达不了 `payee_type='AGENT' AND payee_no IN (...)` 这个合取。
--
-- 【解法：把条件挪进列定义，而不是改共享框架】
-- 加一个生成列 agent_no = CASE WHEN payee_type='AGENT' THEN payee_no END：
--   · AGENT 行 → 拿到代理号，IN (...) 正常命中
--   · VENUE 行 → NULL，而 **IN (...) 天然不匹配 NULL**，代理一行也看不到
-- 与 DataScopeRegistration 类注释里「agent_no IS NULL 表示平台直营，代理看不到」
-- 是同一套语义，不是新发明的规则。
--
-- 生成列而非普通列 + 触发器/应用层同步：**同步逻辑一旦漏一处就是静默泄露**，
-- 而生成列没有「忘了写」这种可能。代价是不能手工改它 —— 这正是我们要的。
--
-- ⚠️ MariaDB 对生成列里的 CASE 挑剔（V48 撞过 ERROR 1901：分支类型不一致）。
-- 这里只有一个 VARCHAR 分支 + 隐式 NULL，已在 12.3 实测建表、插值、索引均正常。
-- ============================================================

ALTER TABLE share_record
  ADD COLUMN IF NOT EXISTS agent_no VARCHAR(36)
    GENERATED ALWAYS AS (CASE WHEN payee_type = 'AGENT' THEN payee_no END) VIRTUAL
    COMMENT '数据范围锚点：仅 payee_type=AGENT 时有值，VENUE 行为 NULL（IN 不匹配 NULL）';
ALTER TABLE share_record ADD KEY IF NOT EXISTS idx_share_record_agent (agent_no);

ALTER TABLE stl_payout_account
  ADD COLUMN IF NOT EXISTS agent_no VARCHAR(36)
    GENERATED ALWAYS AS (CASE WHEN payee_type = 'AGENT' THEN payee_no END) VIRTUAL
    COMMENT '数据范围锚点：同上';
ALTER TABLE stl_payout_account ADD KEY IF NOT EXISTS idx_payout_account_agent (agent_no);

-- 下面两张代理当前还没有读取权限码，但**先补上锚点**：
-- 漏注册的后果是「哪天给了码就立刻泄露」，而补注册的成本只是这两行。
-- fail-closed 的方向也对：注册了却漏维度是「看不到」，不是「看得到别人的」。
ALTER TABLE stl_withdrawal
  ADD COLUMN IF NOT EXISTS agent_no VARCHAR(36)
    GENERATED ALWAYS AS (CASE WHEN payee_type = 'AGENT' THEN payee_no END) VIRTUAL
    COMMENT '数据范围锚点：同上';
ALTER TABLE stl_withdrawal ADD KEY IF NOT EXISTS idx_withdrawal_agent (agent_no);

ALTER TABLE stl_settlement
  ADD COLUMN IF NOT EXISTS agent_no VARCHAR(36)
    GENERATED ALWAYS AS (CASE WHEN payee_type = 'AGENT' THEN payee_no END) VIRTUAL
    COMMENT '数据范围锚点：同上';
ALTER TABLE stl_settlement ADD KEY IF NOT EXISTS idx_settlement_agent (agent_no);
