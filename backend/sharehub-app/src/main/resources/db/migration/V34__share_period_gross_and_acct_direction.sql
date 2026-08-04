-- V34：三个记账语义落地（未完成清单 B1，TDD-后端API对齐实现 §B4 的 ⚖️ 项）。
--
-- 34.1 share_record.period —— 分润归属哪个结算周期
--
-- 现状：出账（SettlementServiceImpl.generate）按 `DATE_FORMAT(created_at,'%Y-%m')` 现推。
-- 两个问题：① 语义错位 —— 记录**创建**时刻不等于业务**归属**周期（跨月补记的分润会记到补记月，
-- 而它该归属原订单月）；② 函数包列，账期过滤走不了索引，出账批处理全表扫。
--
-- 定案：加列显式定格，写入时由生成方给定。存量回填沿用旧推导 —— 与今天的行为**逐行等价**，
-- 所以这次迁移不改变任何已出账的数字，只是把口径从「每次查询现推」变成「写入时定格」。
ALTER TABLE share_record
  ADD COLUMN IF NOT EXISTS period VARCHAR(7) NULL COMMENT '归属结算周期 YYYY-MM（写入定格，不再由 created_at 现推）';
UPDATE share_record SET period = DATE_FORMAT(created_at, '%Y-%m') WHERE period IS NULL;
CREATE INDEX IF NOT EXISTS idx_share_record_period ON share_record (period, payee_no);

-- 34.2 share_record.gross_amount —— 分润基数（GMV）
--
-- 现状：分润统计的 gmv 由 `SUM(amount / NULLIF(rate,0))` **反推**。
-- 反推在两种情况下直接出错：固定额分润（mode=FIXED，rate 为 0/NULL → 该行 GMV 被算成 NULL 而消失）、
-- 阶梯分润（有效费率≠单一 rate）。统计口径不该依赖「金额是费率乘出来的」这个并不总成立的假设。
--
-- 定案：把分润基数**快照**下来（与订单落价格快照同理）。不 join ord_order 取真值，
-- 是因为 finance 不得跨域 join trade（arch-guard G1）；也因为费率变更后历史分润必须保持可复算。
-- 存量回填仍用反推值 —— 除此之外无从得知历史基数，但新数据从此是真值。
ALTER TABLE share_record
  ADD COLUMN IF NOT EXISTS gross_amount DECIMAL(18,2) NULL COMMENT '分润基数(GMV快照)；写入定格，不再由 amount/rate 反推';
UPDATE share_record
   SET gross_amount = ROUND(amount / rate, 2)
 WHERE gross_amount IS NULL AND rate IS NOT NULL AND rate <> 0;

-- 34.3 acct_account.balance —— 负债类科目方向
--
-- 复式记账里余额方向随科目性质走：资产/费用类 = Σ借 − Σ贷；负债/权益/收入类 = Σ贷 − Σ借。
-- 现状 balance 是不带方向语义的单列，且**当前无任何生产者**（记分录不更新账户余额），
-- 所以这里不动存量数字 —— 改数字才是危险的。本次只把「方向由 acct_type 决定」这件事
-- 落成表上的显式契约（列注释 + 取值域），余额算子见 LedgerService#balanceOf。
ALTER TABLE acct_account
  MODIFY COLUMN acct_type VARCHAR(24) NOT NULL
    COMMENT '科目性质：ASSET/EXPENSE(借增，余额=Σ借−Σ贷) · LIABILITY/EQUITY/REVENUE(贷增，余额=Σ贷−Σ借)。旧值 CASH→ASSET、PAYABLE→LIABILITY',
  MODIFY COLUMN balance DECIMAL(18,2) NOT NULL DEFAULT 0.00
    COMMENT '账户余额（方向已按 acct_type 归一：任何科目的正数都表示「该科目方向上的余额」，不需要调用方再判方向）';
UPDATE acct_account SET acct_type = 'ASSET'     WHERE acct_type = 'CASH';
UPDATE acct_account SET acct_type = 'LIABILITY' WHERE acct_type = 'PAYABLE';
