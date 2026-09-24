-- ============================================================
-- ShareHub · 两列的「默认值」不在自己的词表里
--
-- 【症状：库自己造出一个谁都不认识的状态】
--   · notify_template.status  DEFAULT 'ACTIVE'  —— 而这一列的词表是 ENABLED / DISABLED
--     （NotifyTemplateServiceImpl 建模板时写 ENABLED，
--       运营端 lib/types/system.ts 的 NotifyTemplate.status 也只有这两个）
--   · ad_slot.status          DEFAULT 'ACTIVE'  —— 而这一列的词表是 IDLE / OCCUPIED
--     （AdSlotServiceImpl 建广告位时写 IDLE，
--       运营端 lib/types/marketing.ts 的 AdSlot.status 也只有这两个）
--
-- 走服务建的行没问题（服务显式写了值）。问题在**所有不走服务的插入路径**：
-- 种子 SQL、数据导入、DBA 手工补行、将来任何 INSERT 时漏了这一列 ——
-- 落进去的是 ACTIVE，而 ACTIVE **不在运营端的取值集里**：
-- 徽标映射不上、按状态筛一条都查不到，而两边都不报错。
-- 与 V64（BUYOUT/BOUGHT_OUT）是同一类缺陷，区别只是这次造出错值的是**库本身**。
--
-- 【为什么是默认值错而不是词表错】
-- 两列的取值在**服务实现 + 运营端契约**上是一致的，只有 DDL 的默认值是第三个值，
-- 而它从来没有被任何代码或文档声明过。改那一处，不让另外两处去迁就它。
--
-- 【为什么同时补列注释】
-- 这两列此前**没有注释**。本仓库把 DDL 列注释当作词表的裁定依据
-- （见 StatusVocabularyAcrossEndsTest：「以 DDL 列注释为准定哪边是对的」）——
-- 没有注释的列就没有裁定依据，于是当初那个 ACTIVE 写进来时，
-- 没有任何一处能说它不对。全库这样的列还有 17 个，见
-- known-undocumented-status-columns.txt。
--
-- 【存量】两张表当前都是空的（test 与线上均未启用该功能），UPDATE 是为了幂等与稳妥：
-- 本迁移在任何已有 ACTIVE 行的环境上也能把它们归一，不依赖「表一定是空的」这个前提。
-- ============================================================
SET NAMES utf8mb4;

UPDATE notify_template SET status = 'ENABLED' WHERE status = 'ACTIVE';
ALTER TABLE notify_template
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED';

UPDATE ad_slot SET status = 'IDLE' WHERE status = 'ACTIVE';
ALTER TABLE ad_slot
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/OCCUPIED(被投放占用)';
