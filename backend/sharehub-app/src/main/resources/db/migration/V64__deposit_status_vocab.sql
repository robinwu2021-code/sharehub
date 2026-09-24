-- ============================================================
-- ShareHub · 押金买断状态取值归一（BUYOUT → BOUGHT_OUT）
--
-- 【同一个值在两套词里】
-- 除了落库那一行，**其余所有地方都是 BOUGHT_OUT**：
--   · ord_deposit.status 的 DDL 列注释：HELD/RELEASED/BOUGHT_OUT/ARREARS
--   · OrdDeposit 实体 javadoc、DepositService 接口注释
--   · 运营端 lib/types/order.ts 的 DepositStatus 联合类型
--   · 运营端的状态机：buyout: { from: ["HELD"], to: "BOUGHT_OUT" }
-- 只有 DepositServiceImpl.buyout() 写的是 "BUYOUT"。
--
-- 【后果不是「少个下划线」】
-- 买断之后那一行的状态**不在运营端的取值集里** —— 按「买断」筛一条都查不到，
-- 状态徽标也映射不上。而两边都不报错：运营会以为没有买断的单子。
--
-- 资金上没出事，只是因为 release() 用的是正向白名单（if (!HELD) throw），
-- 与别的状态怎么拼无关 —— 换成「排除 BOUGHT_OUT」那种写法，这个拼写差
-- 就会变成「已计收入的押金还能被解冻」。这是运气，不是设计。
--
-- 【为什么改代码而不是改词表】
-- BOUGHT_OUT 是 DDL 与两端契约的既定值，只有一处实现跟它不一致 ——
-- 改那一处，而不是让四处去迁就它。
-- ============================================================
SET NAMES utf8mb4;

UPDATE ord_deposit SET status = 'BOUGHT_OUT' WHERE status = 'BUYOUT';
