SET NAMES utf8mb4;
-- ============================================================
-- 机柜状态：存量空串回填 + 词表 CHECK 约束
--
-- 【起因】共享测试库 test_sharehub 的 CAB1001 status = ''（列是 NOT NULL，空串照样进得来）。
--   前端 StatusBadge 对未知值降级显示原值，所以不白屏，但这台柜子在台账里没有状态、
--   任何按状态走的门禁 / 统计都把它漏掉 —— 而这不报错。
--   写入来源是旧的编辑接口：CabinetServiceImpl 曾 `setStatus(str(in.get("status")))`，
--   请求体带空串就原样落库。2026-09-25 起状态只经 CabinetLifecycleService 的动作改，入口已关。
--
-- 【为什么还要约束】入口关了只挡住已知的那一条路；导入、批量脚本、手工 SQL 都还能写进非法值。
--   NOT NULL 拦不住空串，词表 CHECK 才拦得住。词表与 CabinetStatus 枚举逐项一致，
--   以后枚举加值必须同时改这里（新开迁移），否则写入会被库拒绝 —— 这是有意的：宁可当场报错，不要静默脏数据。
--
-- 【回填口径】有点位的视为已布放（DEPLOYED），没有点位的视为在库（IN_STOCK）。
--   只处理空串 / 纯空白；词表外的其它值不猜，若存在，下面加约束会失败，需人工处理后再跑。
-- ============================================================

UPDATE dev_cabinet
   SET status = CASE WHEN location_no IS NOT NULL AND location_no <> '' THEN 'DEPLOYED' ELSE 'IN_STOCK' END
 WHERE TRIM(status) = '';

ALTER TABLE dev_cabinet DROP CONSTRAINT IF EXISTS chk_dev_cabinet_status;
ALTER TABLE dev_cabinet
  ADD CONSTRAINT chk_dev_cabinet_status
  CHECK (status IN ('IN_STOCK', 'IN_TRANSIT', 'DEPLOYED', 'FAULT', 'RETIRED'));
