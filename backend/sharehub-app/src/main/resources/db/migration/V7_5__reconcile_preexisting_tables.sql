-- 补齐「预先存在的表」缺失的列。
--
-- 为什么需要：V1..V7 用 `CREATE TABLE IF NOT EXISTS`（让迁移对已有库幂等、不丢数据的前提），
-- 代价是**表已存在时整条 CREATE 被跳过** —— 早期手工建的表停留在旧结构，缺了 ddl/ 后来补的列。
-- 实测后果：V8 的 ALTER 报 Unknown column 'price_plan_no'。
--
-- 本文件由「迁移建表定义 ⊖ 库中实际列」比对生成（不是人工清单）。
-- 全部 ADD COLUMN IF NOT EXISTS；新列一律可空（存量行已有数据，NOT NULL 无默认值会失败）。
-- 全新部署时为空操作 —— 只为修复存量库而存在。

SET NAMES utf8mb4;

