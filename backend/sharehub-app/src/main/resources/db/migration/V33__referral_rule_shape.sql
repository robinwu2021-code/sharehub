-- V33：mkt_referral_rule 贴合前端 ReferralRule 形状（V31 先建表后见契约的补齐）。
-- reward_to：奖励对象 INVITER/INVITEE/BOTH（BOTH=双方各得 inviter_reward，不是均分）；
-- trigger_event：触发事件（FIRST_ORDER 首单完成 / REGISTER 注册 等，前端 ReferralTrigger）。
ALTER TABLE mkt_referral_rule
  ADD COLUMN IF NOT EXISTS reward_to     VARCHAR(8)  NOT NULL DEFAULT 'BOTH'        COMMENT '奖励对象：INVITER/INVITEE/BOTH',
  ADD COLUMN IF NOT EXISTS trigger_event VARCHAR(16) NOT NULL DEFAULT 'FIRST_ORDER' COMMENT '触发事件（前端 ReferralTrigger）';
