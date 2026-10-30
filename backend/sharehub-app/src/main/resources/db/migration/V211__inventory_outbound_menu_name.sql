-- 当前页面按设备展示交付与安装状态，菜单使用出库管理；出库单保留给库存业务凭证。
UPDATE iam_menu SET name = '出库管理', name_en = 'Outbound Management', name_ar = 'إدارة الصرف'
 WHERE parent_no = 'M_inventory' AND path = '/inventory?tab=issued';
