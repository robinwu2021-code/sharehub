-- 营销活动状态列的词表注释漏了 PAUSED。
--
-- 原注释：'DRAFT/RUNNING/ENDED'，而 CampaignStateMachine 的 pause 边
-- （RUNNING → PAUSED）确实会把 PAUSED 写进这一列。
--
-- 为什么此前没被任何卡口发现：
--   · StoredValueInVocabularyTest 比的是「库里存着的值 vs 列注释」——
--     库里一天没有暂停过的活动，它就一天是绿的；
--   · DdlStatusVocabularyCommentTest 只要求这一栏**非空**，不判断内容对不对；
--   · StatusVocabularyAcrossEndsTest 比的是两端**同名**词表，而后端此前
--     没有 CampaignStatus 枚举（状态机里是裸串），于是这一对从未被比对过。
--
-- 三条卡口各自都"正常"，缺陷从它们之间的缝里过去了。真正点下「暂停」的那天，
-- 那一行的状态就不在这一列自己声明的词表里 —— 而列注释正是本仓库判定
-- 「两端对不上时以哪边为准」的依据。依据本身错了，就没有裁判。
--
-- 只改注释，不动数据：PAUSED 本来就是合法值，缺的是声明。
ALTER TABLE mkt_campaign
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
  COMMENT 'DRAFT/RUNNING/PAUSED/ENDED';
