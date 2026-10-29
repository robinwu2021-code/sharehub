package ai.neargo.sharehub.inv.service;

import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseReq;

/**
 * 仓库管理（V201）：新建 / 编辑 / 归档 / 取消归档。默认回仓仓仍是系统参数 inv.return_warehouse（页面经系统参数接口改）。
 * 归档前须：不是默认回仓仓、仓里没有在库设备、没有未结的到仓待检单 —— 否则这些设备在台账上就「落在一个停用的仓里」。
 */
public interface WarehouseAdminService {

    WarehouseItem create(WarehouseReq req);

    WarehouseItem update(String warehouseNo, WarehouseReq req);

    WarehouseItem archive(String warehouseNo);

    WarehouseItem unarchive(String warehouseNo);
}
