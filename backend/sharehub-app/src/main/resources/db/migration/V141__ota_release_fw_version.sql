-- ============================================================
-- ShareHub · OTA 发布单的固件版本改回落在 fw_version（实体加 @TableField）
--
-- 【症状】建一条固件版本是 1.0.0 的发布单 → **500**：
--   Data truncation: Incorrect integer value: '1.0.0' for column 'version'
-- 也就是说 OTA 发布**从来没有成功过一次**（能存进去的只有纯数字版本号）。
-- 库里 dev_ota_release 为空，与这个结论一致。
--
-- 【怎么来的】V2 建表时把两件事分开并写在注释里：
--   fw_version VARCHAR(32) '固件版本号(如 1.4.2)。不能叫 version —— 那是乐观锁列名'
--   version    BIGINT      '乐观锁'
-- 后来实体写成了 `private String version`（映射到 BIGINT 那一列），
-- V42 把乐观锁让到 version_col，V83 又把 fw_version 标成 "retired: 实体映射的是 version"。
-- **每一步都顺着上一步的错走**，而建表注释里那句警告一直在那儿。
--
-- 【这次怎么改】不改列类型、不搬数据（表是空的），只把实体的映射指回 fw_version：
--   @TableField("fw_version") private String version;
-- 出参字段名仍叫 version（契约不变），dev_ota_rollout 那边本来就叫 fw_version，两张表这才对齐。
-- 本迁移只负责把**列注释**改回真相 —— 本仓库多个卡口拿列注释当真源，
-- 留着 "retired" 会让下一个人再走一遍同样的路。
--
-- `version` BIGINT 就此成为无人映射的死列，**保留不删**：删列要停机改表，
-- 而它 NOT NULL DEFAULT 0，留着不影响写入。
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE dev_ota_release
  MODIFY COLUMN fw_version VARCHAR(32) NULL COMMENT '固件版本号(如 1.4.2)；实体 DevOtaRelease.version 映射到这里';

ALTER TABLE dev_ota_release
  MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '死列：原为乐观锁，乐观锁已移至 version_col(V42)，固件版本已移回 fw_version(V141)。无人映射，保留不删';
