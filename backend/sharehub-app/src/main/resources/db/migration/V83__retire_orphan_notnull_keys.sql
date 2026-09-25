-- 10 个「NOT NULL 无默认、实体又不映射」的遗留列 —— 它们让 8 个建单端点必然 500。
--
-- 症状：`Field 'campaign_no' doesn't have a default value`。
-- 实测复现：POST /api/user/ad-campaigns 建广告活动 500（2026-09-25 探针）。
--
-- 成因是一批改名做了一半：V13 按「约定:业务键」给这些表补了新键列
-- （ad_no / slot_no / transfer_no…），实体改用新列，而**旧列没退役**，
-- 仍是 NOT NULL 且没有默认值。于是 MyBatis-Plus 按实体拼 INSERT 时不带旧列，
-- 数据库直接拒。
--
-- 为什么一直没人发现：这些都是「新建」路径，而列表/详情读的是种子数据，
-- 读侧一切正常。本轮 10 张表在测试库里**全都是 0 行** —— 没有一条是建出来的。
--
-- 改成可空而不是 DROP：本仓库零 DELETE，且真实环境里这些列可能有历史值，
-- 置空是能让 INSERT 通过的最小改动，不丢任何数据。
-- 列注释标 retired，下一轮清理时按注释找得到。

ALTER TABLE ad_campaign   MODIFY COLUMN campaign_no  VARCHAR(36) NULL COMMENT 'retired: 旧业务键，实体已改用 ad_no（V13）';
ALTER TABLE ad_creative   MODIFY COLUMN campaign_no  VARCHAR(36) NULL COMMENT 'retired: 旧业务键，实体已改用 ad_no（V13）';
ALTER TABLE ad_placement  MODIFY COLUMN campaign_no  VARCHAR(36) NULL COMMENT 'retired: 旧业务键，实体已改用 ad_no（V13）';
ALTER TABLE ad_placement  MODIFY COLUMN ad_slot_no   VARCHAR(36) NULL COMMENT 'retired: 旧业务键，实体已改用 slot_no（V13）';
ALTER TABLE ad_impression MODIFY COLUMN placement_no VARCHAR(36) NULL COMMENT 'retired: 实体不映射（曝光按 ad_no + slot_no + stat_date 聚合）';
ALTER TABLE ad_slot       MODIFY COLUMN ad_slot_no   VARCHAR(36) NULL COMMENT 'retired: 旧业务键，实体已改用 slot_no';
ALTER TABLE inv_transfer  MODIFY COLUMN from_location VARCHAR(64) NULL COMMENT 'retired: 实体已拆成 from_type/from_ref/from_name';
ALTER TABLE inv_transfer  MODIFY COLUMN to_location   VARCHAR(64) NULL COMMENT 'retired: 实体已拆成 to_type/to_ref/to_name';
ALTER TABLE notify_template MODIFY COLUMN code        VARCHAR(64) NULL COMMENT 'retired: 实体已改用 template_no';
ALTER TABLE dev_ota_release MODIFY COLUMN fw_version  VARCHAR(32) NULL COMMENT 'retired: 实体映射的是 version（fw_type 区分固件类型）';
