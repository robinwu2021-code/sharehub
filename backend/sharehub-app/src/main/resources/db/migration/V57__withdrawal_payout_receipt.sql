-- ============================================================
-- ShareHub · 提现打款回执（必要功能清单 ⑮）
--
-- 【解决什么】
-- 状态机写着 PAYING --PAY--> PAID / --FAIL--> FAILED，但此前**没有任何入口调用它**：
-- 审批把单子推到 PAYING（出款在途）之后，再没有一条路通向 PAID。
-- 结果是钱算得清、批得了，**批完不会动，单子永远不会变成已付**。
-- 本迁移补上「钱真的出去了/没出去」这一步要落的证据列。
--
-- 【为什么不复用 reject_reason】
-- 那是「审批驳回」的理由（钱从没打算出去）；打款失败是「钱出去了但退回来」——
-- 两者在对账和客诉里要分开查。同一列装两种含义，事后没人分得清。
--
-- 【为什么另起 pay_channel + pay_ref，而不用 V3 的 nearpay_payout_no】
-- 那列名字里焊死了 nearpay。实际第一批打款多半是**人工转账后回填**（nearpay 未接，
-- 见 MVP 清单硬阻塞 2），叫 nearpay_payout_no 存一笔手工转账的银行流水号，名实不符会长期误导。
-- 该列 V3 建表起就**没有实体映射、全仓零引用**（实为死列），这里不删（删列不可逆，
-- 且线上可能有历史值），改由 pay_channel='NEARPAY' + pay_ref 表达，列注释里标明取代关系。
--
-- 【幂等键为什么敢让它可空】
-- uk 里带 NULL 在 MySQL/MariaDB 下**不约束**——这正是 share_record 幂等缺陷的成因。
-- 但这里的语义相反且正确：未打款的单子 pay_ref 本来就该是 NULL、**本来就不该互相排斥**；
-- 只有「已登记回执」的行才需要防重复登记，而那些行 pay_ref 非空、受约束。
-- 判据：NULL 表示「此行不参与去重」时可用；NULL 表示「缺了个本该有的值」时不可用。
-- ============================================================

ALTER TABLE stl_withdrawal
  ADD COLUMN IF NOT EXISTS pay_channel VARCHAR(16)  NULL COMMENT '打款渠道 NEARPAY/MANUAL；取代 V3 的 nearpay_payout_no（死列，保留不删）',
  ADD COLUMN IF NOT EXISTS pay_ref     VARCHAR(64)  NULL COMMENT '渠道流水号（nearpay 打款单号 / 银行回单号）；回执幂等键',
  ADD COLUMN IF NOT EXISTS payer_no    VARCHAR(36)  NULL COMMENT '登记回执的人；与 auditor_no 分开，便于查「审批人是否自己给自己放款」',
  ADD COLUMN IF NOT EXISTS payer_name  VARCHAR(64)  NULL COMMENT '快照：登记人姓名',
  ADD COLUMN IF NOT EXISTS fail_reason VARCHAR(255) NULL COMMENT '打款失败原因；与 reject_reason（审批驳回）分列，勿合并';

-- 同一笔渠道流水只能登记一次；未登记的行 pay_ref 为 NULL，不参与约束（见上「幂等键」注）
ALTER TABLE stl_withdrawal
  ADD UNIQUE KEY IF NOT EXISTS uk_stl_withdrawal_payref (pay_channel, pay_ref);
