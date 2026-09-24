-- ============================================================
-- ShareHub · 给六列状态补上词表注释（台账 15 → 9）
--
-- 【为什么补注释算干活】
-- 列注释在本仓库是词表的**裁定依据**，也是 StoredValueInVocabularyTest
-- （拿库里实际存的值跟注释比）唯一的判据 ——
-- **台账每短一条，那条卡口的覆盖面就大一列**。
-- 它已经这样抓到过两处：share_rule.mode='RATE'、iam_menu.type='ITEM'。
--
-- 【每一条的证据（不是猜的）】
--   price_plan.status   ACTIVE/DISABLED  —— 后端 PricePlanStatus 枚举两值 +
--                                           运营端 lib/types/pricing.ts 同款联合
--   agt_account.status  ACTIVE/DISABLED  —— 运营端 AgentAccount.status；
--                                           表有 username/cred_ref/login_phone，确是登录账号那张
--   loc_location.status ACTIVE/PAUSED    —— 库里两个值都有（27/4），
--                                           且同域的 loc_site.status 已登记同一对
--   coupon_tpl.status   ACTIVE/PAUSED    —— 运营端 Coupon.status（PAUSED = 已下线，不可发放）
--   ad_campaign.status  DRAFT/RUNNING/ENDED —— 运营端 AdCampaign.status，建表默认 DRAFT
--   mbr_benefit.status  ENABLED/DISABLED —— 运营端 MemberBenefit.status；
--                                           两边列名逐个对得上（level/rent_discount/…）
--
-- 【剩下九列为什么不补】
-- ad_placement · dict_item · gw_vendor_device_type · iam_menu · md_device_type ·
-- price_plan_item · usr_membership · usr_user · wo_inspection_plan ——
-- 它们要么运营端压根没有对应类型（功能没做），要么后端只写过一个值。
-- 只凭「现在见过一个值」去定一套封闭词表就是**发明**，不是记录。
-- 典型：wo_inspection_plan 在运营端是 `active: boolean`，根本不是状态串；
-- iam_menu.status 全表 127 行都是 ACTIVE，没有任何代码写第二个值。
-- 宁可留在台账里，也不要写一条看起来权威的错注释 —— 后者会被当成裁定依据。
--
-- 【写法】每行的列定义都从 `SHOW CREATE TABLE` 原样抄，只追加 COMMENT。
-- 理由见 V69：手写类型/可空/默认值会静默改掉它们。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE price_plan MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED';
ALTER TABLE agt_account MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED';
ALTER TABLE loc_location MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED';
ALTER TABLE coupon_tpl MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED';
ALTER TABLE ad_campaign MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/ENDED';
ALTER TABLE mbr_benefit MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED';
