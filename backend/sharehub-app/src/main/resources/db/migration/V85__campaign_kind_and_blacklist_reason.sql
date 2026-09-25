-- 词表归一收尾：V82（告警等级）· V84（对账差异 / outbox 事件）之后剩下的两处。
--
-- 这两处**生产都是空表**，脏值只在累积测试库里 —— 但写它们的是测试代码，
-- 说明写入侧的词表认知也是错的，所以两边一起改（测试里的字面量已同步）。
-- 迁移写在这里而不是"清一下测试库就完了"：测试库是共享的累积资产，
-- 谁也不会记得手工清过什么；迁移是唯一能让每个人的库都一致的东西。
--
-- ── 一、mkt_campaign.kind = 'DISCOUNT' ────────────────────────────────
-- 活动 kind 的词表是 NEW_USER/RECHARGE_GIFT/COUPON_PUSH/REFERRAL/FESTIVAL，
-- 而 DISCOUNT 是**优惠券模板** coupon_tpl.type 的词表（CUT/DISCOUNT）。
-- 又一次「邻域的词表串过来了」—— 与 V82（工单优先级串进告警等级）、
-- V84（对账差异自造名字）同形。这三处都发生在**没有任何写入校验**的列上。
UPDATE mkt_campaign
   SET kind = 'COUPON_PUSH'
 WHERE kind = 'DISCOUNT';

-- ── 二、notify_blacklist.reason = 'COMPLAINT' ─────────────────────────
-- 词表是 USER_OPT_OUT/HARD_BOUNCE/ABUSE/MANUAL。投诉/举报导致的拉黑
-- 在这四档里对应 ABUSE。
UPDATE notify_blacklist
   SET reason = 'ABUSE'
 WHERE reason = 'COMPLAINT';

-- ── 这一轮真正的收尾在卡口上 ───────────────────────────────────────
-- StoredValueInVocabularyTest 的范围判据从「写死的列名清单」换成
-- 「该列取值的形状像不像枚举」。实测覆盖 92 列 → 151 列，误报只剩 2 个。
-- 上面这两处正是原判据扫不到的列（kind / reason 都不在那五个名字里）。
--
-- ⚠️ 仍然欠着的：mkt_campaign.kind 与 notify_blacklist.reason 的词表
-- **只存在于实体 javadoc，写入侧零校验** —— 真实调用方照样能写进词表外的值。
-- 卡口只能在事后发现，补 XxxKind.of() 这类入参校验是另一件事。
