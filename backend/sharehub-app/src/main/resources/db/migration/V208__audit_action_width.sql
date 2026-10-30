-- 审计 action 列放宽：action 存「METHOD + URI」，VARCHAR(64) 装不下深一点的路径，
-- 例如 POST /api/ops/inventory/intakes/{待检单}/items/CABINET/{柜号}/qc 就有 80 多个字符。
-- 写入抛 Data too long，拦截器为了不让业务 500 只记一条 ERROR —— 这类操作此前**全部没有留痕**（2026-10-29 跑测试时发现）。
-- 255 留足余量；拦截器侧另行截断兜底。
SET NAMES utf8mb4;

ALTER TABLE iam_audit_log MODIFY COLUMN action VARCHAR(255) NOT NULL COMMENT '权限码/动作';
