-- ============================================================
-- ShareHub · 审计表按月分区（TDD T3-5）
--
-- 【为什么是现在做，而不是"等它大了再说"】
-- 分区要改主键（见下），而改主键的代价**随表增长**：现在 7 千行是秒级，
-- 等到几百万行就是一次长锁。这件事只会越拖越贵，且拖到那时往往正是
-- "审计表太大了得赶紧处理"的时候 —— 最不该做长锁操作的时候。
--
-- 【为什么要动主键】
-- MariaDB 要求**每个唯一键都包含分区列**。本表主键是自增 id，分区列是 created_at，
-- 所以主键必须变成 (id, created_at)。id 仍是第一列，AUTO_INCREMENT 照常。
-- 本表没有别的唯一键，所以只此一处。
--
-- 【为什么有 pmax 兜底分区】
-- RANGE 分区最常见的事故是：**没人记得加下个月的分区，于是某天零点开始所有插入报错**。
-- 对审计表来说，那意味着从那一刻起所有操作都不留痕，而业务照常跑
-- （审计写失败是降级不是 500，见 AuditTrailInterceptor）——
-- 也就是说这个事故**不会有任何人察觉**，直到需要查审计时发现断了几个月。
--
-- pmax 让插入永远不会失败。代价是它不能被 DROP，要新增月份得用
-- REORGANIZE 把它拆开（见 deploy/tencent/cron/powerbank-audit-partitions）。
-- 这个取舍很清楚：宁可分区维护麻烦一点，不能让审计静默断流。
--
-- 【归档为什么不自动删】
-- 维护脚本只**自动加**分区，**从不自动删**。审计是争议时的证据，
-- 删它必须是有人明确决定的动作，不该由一个 cron 在半夜替人做。
-- 归档（导出后再 DROP PARTITION）的命令写在那个脚本的注释里，手动执行。
-- ============================================================
SET NAMES utf8mb4;

-- 一条语句里同时删旧主键、建新主键：中间没有"id 不在任何索引里"的瞬间，
-- 否则 MariaDB 会拒绝（自增列必须始终有索引）。
ALTER TABLE iam_audit_log
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (id, created_at);

-- 按自然月切。RANGE COLUMNS 直接比较 DATETIME，不必像 RANGE 那样套
-- TO_DAYS()/YEAR() —— 少一层转换，边界也更容易读：`LESS THAN ('2026-11-01')`
-- 就是字面意思，不用心算函数值。
ALTER TABLE iam_audit_log
  PARTITION BY RANGE COLUMNS (created_at) (
    -- 第一个分区兜住**所有更早的**历史行，不只是 2026-08 那个月 —— 名字照实写，
    -- 叫 p2026_08 会让人以为更早的数据在别处。
    PARTITION p_until_2026_09 VALUES LESS THAN ('2026-09-01'),
    PARTITION p2026_09 VALUES LESS THAN ('2026-10-01'),
    PARTITION p2026_10 VALUES LESS THAN ('2026-11-01'),
    PARTITION p2026_11 VALUES LESS THAN ('2026-12-01'),
    PARTITION p2026_12 VALUES LESS THAN ('2027-01-01'),
    PARTITION p2027_01 VALUES LESS THAN ('2027-02-01'),
    PARTITION p2027_02 VALUES LESS THAN ('2027-03-01'),
    -- 兜底：没人加分区时插入落这里，而不是报错。理由见上。
    PARTITION pmax VALUES LESS THAN (MAXVALUE)
  );
