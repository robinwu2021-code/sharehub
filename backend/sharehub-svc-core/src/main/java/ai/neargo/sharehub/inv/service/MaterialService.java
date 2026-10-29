package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.inv.dto.MaterialDtos.Material;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialBalance;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDoc;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDocReq;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialMinQtyReq;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialReq;

import java.util.List;

/**
 * 库存管理 · 物料（V210；规范 docs/technical/设计规范-库存管理菜单.md §四）。
 *
 * <p>物料档案是宿主主数据，任何引擎下都可维护；<b>账在 ai-shop 库存服务</b>（用户 2026-10-29），
 * 所以物料单据与物料库存只在独立进销存引擎（inv.engine = SERVICE）下可用，LOCAL 下明确拒绝而不是记一本本地账。
 * 不变式 M1–M5 与前端 mock（ops-web lib/mock/db/material.ts）同口径；数量够不够、序列号在不在，以库存服务为准。
 */
public interface MaterialService {

    PageResult<Material> page(Integer page, Integer size, String keyword, String category, Boolean showArchived);

    Material create(MaterialReq req);

    /** 有物料单据后跟踪方式与单位不可改；内置物料只读。 */
    Material update(String materialNo, MaterialReq req);

    /** 任何仓有库存不可归档；内置物料不可归档。 */
    Material archive(String materialNo);

    Material unarchive(String materialNo);

    PageResult<MaterialDoc> docs(Integer page, Integer size, String keyword, String kind, String purpose, String warehouseNo,
                                 String materialNo);

    MaterialDoc doc(String docNo);

    /** 建单即过账：入库 → 库存服务入库单；出库 → 出库单（报废带原因码、退供应商带 SUPPLIER 去向）；调拨 → 一出一入。 */
    MaterialDoc createDoc(MaterialDocReq req);

    List<MaterialBalance> balances(String warehouseNo, String materialNo, String category, Boolean lowOnly);

    MaterialBalance saveMinQty(MaterialMinQtyReq req);

    /** 低于安全库存的物料（告警 LOW_STOCK 用；LOCAL 引擎或库存服务不可用时返回空）。 */
    List<MaterialBalance> lowRows();
}
