package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.inv.dto.SupplierDtos.Supplier;
import ai.neargo.sharehub.inv.dto.SupplierDtos.SupplierReq;

/** 供应商（V201）：列表 / 新建 / 编辑 / 归档 / 取消归档（契约禁止 delete）。 */
public interface SupplierService {

    PageResult<Supplier> page(Integer page, Integer size, String keyword, Boolean showArchived);

    Supplier create(SupplierReq req);

    Supplier update(String supplierNo, SupplierReq req);

    Supplier archive(String supplierNo);

    Supplier unarchive(String supplierNo);

    /** 启用中的供应商名称；不存在或已归档返回 null（待检单登记时校验用）。 */
    String activeName(String supplierNo);
}
