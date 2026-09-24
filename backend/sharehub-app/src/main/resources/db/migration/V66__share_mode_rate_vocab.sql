-- ============================================================
-- ShareHub · 分润方式里混进了一个谁都不认识的 RATE
--
-- 【事实】test 库里 share_rule 有 9 行、share_record 有 8 行 mode='RATE'，
-- 而这一列的词表是 CHANNEL_SPLIT / LEDGER —— 三处都这么写：
--   · share_rule.mode / agt_commission.mode 的 DDL 列注释：CHANNEL_SPLIT/LEDGER
--   · 后端 ShareMode 枚举（两值），生成分润的 ShareGeneratorImpl 写的是 LEDGER
--   · 运营端 lib/types/finance.ts：export type ShareMode = "CHANNEL_SPLIT" | "LEDGER"
-- 只有 DemoOpsFinanceSeeder 写 "RATE"（本次一并改掉）。
--
-- 【为什么会写成 RATE：两个概念被当成一个】
-- mode 是**结算路径**（钱怎么走）：LEDGER = 平台先全额收款再记账分账，
-- CHANNEL_SPLIT = 支付通道在收款瞬间直接分给各方，钱不经平台账户 ——
-- 两者的资金流与合规口径不同，不能互换。
-- 而「按比例还是按固定额」是 rate 那一列的事。写 RATE 等于把计费方式填进了资金路径。
--
-- 【后果】那些行的 mode 不在运营端的取值集里：分润规则页上映射不出来。
-- 更远一点的雷是 ShareMode.of()：它对非法值抛异常（全局映射 400）。
-- 今天主代码里**没有任何地方调它**，所以还没炸；哪天读侧开始解析这一列，
-- 炸的是这 17 行历史数据，而不是当时写的那段代码 —— 排障会从错的地方开始查。
--
-- 【顺带补上 share_record.mode 的列注释】
-- 它此前没有注释，而 share_rule/agt_commission 的同名列都有。
-- 没有注释就没有裁定依据（见 known-undocumented-status-columns.txt），
-- 这一列正好是那 17 行里的一半。
-- ============================================================
SET NAMES utf8mb4;

UPDATE share_rule   SET mode = 'LEDGER' WHERE mode = 'RATE';
UPDATE share_record SET mode = 'LEDGER' WHERE mode = 'RATE';

ALTER TABLE share_record
    MODIFY COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'LEDGER' COMMENT 'CHANNEL_SPLIT/LEDGER';
