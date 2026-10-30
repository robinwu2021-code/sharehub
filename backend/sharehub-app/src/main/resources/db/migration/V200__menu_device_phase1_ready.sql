-- 设备管理一期收齐（用户 2026-10-29「设备管理全部第一阶段完成」）：库存调拨、资产差异、设备编码、设备日志、固件 OTA
-- 五个叶子改为 L1 且就绪 —— 后端端点均已在位（前端契约对齐 100%），此前只是分期门禁把它们灰掉。
SET NAMES utf8mb4;

UPDATE iam_menu SET phase = 1, ready = 1
 WHERE parent_no = 'M_device'
   AND path IN ('/devices?tab=inventory', '/devices?tab=asset-diffs', '/devices?tab=codes', '/devices?tab=logs', '/devices?tab=ota');
