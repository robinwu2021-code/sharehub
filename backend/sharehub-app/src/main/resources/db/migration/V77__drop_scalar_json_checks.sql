-- ============================================================
-- ShareHub · 九个存标量的列被加了 json_valid 约束，其中一个正在让功能 100% 失败
--
-- 【这是实现状态总表 §六 第 6 条「26 张表的 json_valid 约束里若干为标量语义误标
--   （本次未核实）」的核实结果】全库 49 条 json_valid 约束、33 张表，逐列查了数据与写入方：
--     · 真 JSON（对象/数组）—— 保留
--     · 存标量的 —— 本次删约束，见下
--     · iam_audit_log 的 target_no/target_type —— 也是标量，但两侧代码都在迁就它
--       （写侧 json(...) 包一层），要连代码和存量一起改，留到下一次
--
-- 【错误是怎么来的：列注释自己写着】
-- 这几列的注释原文就是「db-design 标 JSON」——
-- 有人把设计文档里标了 JSON 的列**一律**做成 longtext + json_valid，
-- 而没有对一下代码到底往里写什么。于是：
--     notify_template.scene      ← 场景键 OTP / RENT_OK
--     gw_message_log.event_type  ← 事件码 HEARTBEAT
--     gw_command_log.slot_index  ← 仓位序号（整数；数字恰好是合法 JSON，所以它只是语义错，不报错）
--     share_rule.formula         ← 计算式 "amount * rate"
--     ad_placement.slot_no       ← 广告位号 SLOT001
--     ad_campaign.advertiser/creative/targeting ← 广告主名 / 素材路径 / 定向说明（前端都当字符串显示）
--     mkt_push.audience          ← 受众**标签**（audienceLabel() 产出的「全部用户」「会员等级 GOLD」）
--
-- 【mkt_push.audience 不是隐患，是现在就坏的】
-- json_valid('全部用户') = 0。PushServiceImpl 在 beforeCreate/beforeUpdate 里必写这一列，
-- 于是**新建定时推送必然撞约束**：
--     ERROR 4025 (23000): CONSTRAINT `mkt_push.audience` failed
-- 表里 0 行、也没有任何后端用例创建过推送 —— 所以这个功能从落地起就没在真库上跑通过，
-- 而没有人会发现，因为 mock 层没有约束。
--
-- 【为什么删约束而不是让代码写 JSON】
-- 这些列语义上就是标量：受众标签是给人看的，结构化的受众在 audience_type + audience_value
-- 两列里；formula 是表达式；slot_no 是编号。把它们包成 JSON 只是为了迁就一条本不该加的约束，
-- 代价是每个读侧都要记得拆包，而忘了拆的那处会把 "CAB1005" 带引号显示出来
-- （iam_audit_log 就是这么被带偏的）。
--
-- 【不动的】openapi_app.scopes（注释：授权范围(权限码数组)）与 ad_campaign.target
-- （注释：定向 region/site/scene）确实是结构化的，保留约束。
-- 列类型仍是 longtext，本次不动：改类型要评估存量与索引，而约束才是会报错的那一半。
-- ============================================================
SET NAMES utf8mb4;

-- ⚠️ 这些 CHECK **是写在列定义里的**（`audience longtext ... CHECK (json_valid(audience))`），
--    不是表级约束。于是：
--      · `ALTER TABLE ... DROP CONSTRAINT `mkt_push.audience`` —— 名字就不对
--        （约束名是列名，报错信息里的「mkt_push.audience」是 MariaDB 拼给人看的格式）；
--      · `ALTER TABLE ... DROP CONSTRAINT `audience`` —— 名字对了，**执行无错但什么都没删**。
--    两次都是「跑过了、没生效」，而 Flyway 两次都把它记成成功。
--    正确办法是 MODIFY COLUMN 把列定义原样重写一遍、只摘掉 CHECK 那一段。
--    下面每一行的定义都是从 `SHOW CREATE TABLE` 抄的（字符集/排序规则/默认值/注释一字未动）。
--
-- 验证方式不能只看迁移有没有报错，要回头查：
--    SELECT COUNT(*) FROM information_schema.check_constraints
--     WHERE constraint_schema=DATABASE() AND check_clause LIKE '%json_valid%';
ALTER TABLE mkt_push MODIFY COLUMN `audience` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '受众条件(分群 SEG/标签/全量)';
ALTER TABLE notify_template MODIFY COLUMN `scene` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
ALTER TABLE gw_message_log MODIFY COLUMN `event_type` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
ALTER TABLE gw_command_log MODIFY COLUMN `slot_index` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
ALTER TABLE share_rule MODIFY COLUMN `formula` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
ALTER TABLE ad_placement MODIFY COLUMN `slot_no` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
ALTER TABLE ad_campaign MODIFY COLUMN `advertiser` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
ALTER TABLE ad_campaign MODIFY COLUMN `creative` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
ALTER TABLE ad_campaign MODIFY COLUMN `targeting` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'db-design 标 JSON';
