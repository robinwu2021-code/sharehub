-- 两处词表，一处值错、一处声明不全。都是 V82（告警等级）那一轮扫出来的同类。
--
-- 背景：StoredValueInVocabularyTest 的扫描范围是写死的列名清单
-- （'status','state','type','mode','level'），而「注释里声明了词表」的列
-- 全库有 130 个。把范围外的 130 列按「该列取值整体像枚举」过滤后逐列比对，
-- 得到 4 处真违例，本次修其中 2 处（另 2 处由写入方所在的会话处理，见文末）。
--
-- ── 一、recon_diff.diff_type 存了一个词表里没有的名字 ─────────────────
-- 词表：MISSING_LOCAL/MISSING_CHANNEL/AMOUNT_MISMATCH/STATUS_MISMATCH/DUPLICATE
-- 实存：MISSING_IN_LEDGER —— DemoOpsFinanceSeeder 自己造的名字，语义即
-- 「本地账里缺」＝ MISSING_LOCAL，只是写的时候没照词表。
-- **生产有 1 行**，对账差异页按类型筛这一行会漏掉，而两边都不报错。
-- 同一个 seeder 上一轮刚写错过告警等级（V82）—— 它走裸写入，不经过任何校验。
UPDATE recon_diff
   SET diff_type = 'MISSING_LOCAL'
 WHERE diff_type = 'MISSING_IN_LEDGER';

-- ── 二、sys_outbox.event_type 的注释是「举例」，不是词表 ────────────────
-- 原注释：'事件类型，如 ASSET_ASSIGNED / ORDER_SETTLED'——「如」说明它从没打算
-- 闭合，于是第三个事件 CONTRACT_SIGNED 上线后，这一列声明的取值与实际长期不符
-- （库里三种都有）。
--
-- 补全而不是给卡口开一个「如=开放词表」的豁免口子：事件类型是生产者与消费者
-- 之间的契约，本就该显式声明；只有三个，补全的成本远小于「有个口子可钻」的代价
-- （本仓库另一条卡口的注释里写过：有豁免清单的卡口，下一个人会先想能不能加进去）。
-- 代价说清楚：以后新增事件要连这条注释一起改，那正是「声明你的词表」的意思。
ALTER TABLE sys_outbox
  MODIFY COLUMN event_type VARCHAR(64) NOT NULL
  COMMENT 'ASSET_ASSIGNED/CONTRACT_SIGNED/ORDER_SETTLED';

-- ── 未在本次修的两处（写入方文件被并行会话占用，不便在本次一并改）───────
--   · mkt_campaign.kind = 'DISCOUNT'      —— 那是**优惠券模板**的词表(CUT/DISCOUNT)，
--     活动 kind 的词表是 NEW_USER/RECHARGE_GIFT/COUPON_PUSH/REFERRAL/FESTIVAL；
--   · notify_blacklist.reason = 'COMPLAINT' —— 词表是
--     USER_OPT_OUT/HARD_BOUNCE/ABUSE/MANUAL。
-- 两者**生产都是空表**，脏值只在累积测试库里，写入方都是测试代码。
-- 另：这两列的词表只存在于实体 javadoc 里，**写入侧没有任何校验** ——
-- 也就是说真实调用方同样能写进词表外的值。补校验是另一件事。
