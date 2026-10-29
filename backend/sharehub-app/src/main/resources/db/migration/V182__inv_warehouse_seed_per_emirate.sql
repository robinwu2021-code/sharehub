-- 过渡期种仓（独立进销存上线前；docs/technical/独立进销存/D §2.10 · S0）。
--
-- 生产一个仓都没有：出库池「退回仓库」不填仓时设备会落到「在库、不在任何仓、也不在出库池」，
-- 撤机回仓则静默不生成回仓单（S10 P0-2）。用户 2026-10-29 定：每个酋长国各建一个仓，默认回仓为迪拜。
-- 地址留空待运营补；仓库管理（建 / 改 / 停用）将来在独立库存服务里做，这里不提供写入口。
SET NAMES utf8mb4;

INSERT INTO inv_warehouse (warehouse_no, tenant_id, region_id, name)
SELECT v.no, 'MAIN', v.region, v.name
  FROM (SELECT 'WH001' AS no, 'AE-DU' AS region, '迪拜仓' AS name
        UNION ALL SELECT 'WH002', 'AE-AZ', '阿布扎比仓'
        UNION ALL SELECT 'WH003', 'AE-SH', '沙迦仓'
        UNION ALL SELECT 'WH004', 'AE-AJ', '阿治曼仓') v
 WHERE NOT EXISTS (SELECT 1 FROM inv_warehouse w WHERE w.warehouse_no = v.no);

-- 默认回仓：只在没人配置过时设为迪拜仓，不覆盖已有配置
UPDATE sys_param SET value = 'WH001'
 WHERE tenant_id = 'MAIN' AND param_key = 'inv.return_warehouse' AND (value IS NULL OR value = '');
