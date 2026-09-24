-- ============================================================
-- ShareHub · stl_payout_account.payee_type 的词表注释写的是一个被判过不用的值
--
-- 【三处说 OPERATOR，而实现与现网数据都是 AGENT】
--   · 本列的 DDL 注释（V54 起）：'OPERATOR 运营主体 / VENUE 场地方'
--   · 实体 StlPayoutAccount.payeeType 的 javadoc：同上
--   · 服务的报错文案：「受益方类型只能是 OPERATOR 或 VENUE」
-- 而 PayoutAccountServiceImpl.PAYEE_TYPES = ["AGENT","VENUE"]，
-- 并且那段 javadoc 已经把理由写清楚了：share_record 与 stl_withdrawal 现网存的都是
-- AGENT，一张表用新词、另两张用旧词就 join 不上，也查不出「这笔打给了谁」。
-- 改名归改名的窗口是 ADR-029 §5.1 的 B 步，**那时三张表一起改**。
--
-- 【最刺眼的一处】那句报错**教人用一个会被这一行拒掉的值** ——
-- 调用方照着提示把 payeeType 改成 OPERATOR，再试一次还是 400。（本次一并改掉。）
--
-- 【存量】库里 875 行 AGENT、18 行 OPERATOR。那 18 行的 payee_no 形如 AGT165941，
-- 在 agt_agent 里**查无此人** —— 是早期用例留下的残留（当时服务还收 OPERATOR）。
-- 一并归一：留着它们会让刚立的 StoredValueInVocabularyTest 在词表注释改对之后报红，
-- 而那时红的原因会指向「注释改错了」，不是「数据是脏的」。
--
-- 【⚠️ 这条注释是我自己刚写错的】V69 修双重编码时，把这段文本**原样**还原了回去 ——
-- 乱码修对了，而里面那个值一直是错的。**修「显示」的时候顺手确认一下「内容」**。
--
-- 【编号】本文件最初写成 V75，与另一个会话同时新建的 V75__mkt_push_schedule.sql 撞号，
-- Flyway 直接拒绝启动（Found more than one migration with version 75）。
-- 并行开发下**迁移号要在落盘那一刻再确认一次**，而且写完就提交，别攒着。
-- ============================================================
SET NAMES utf8mb4;

UPDATE stl_payout_account SET payee_type = 'AGENT' WHERE payee_type = 'OPERATOR';

ALTER TABLE stl_payout_account
    MODIFY COLUMN `payee_type` varchar(16) NOT NULL COMMENT 'AGENT 代理商 / VENUE 场地方（与 share_record / stl_withdrawal 同一套；OPERATOR 是 ADR-029 改名后的词，三张表同时改时再换）';
