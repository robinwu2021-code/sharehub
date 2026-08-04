package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransfer;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransferDetail;
import ai.neargo.sharehub.inv.entity.InvTransfer;

/**
 * 库存调拨业务（[api/README §3.2]）。
 *
 * <p>**不继承 {@code CrudService}**：调拨单有状态流转（DRAFT/IN_TRANSIT/DONE）与终态保护，
 * 不是字典（[SKELETON_BRIEF §3] 的聚合根一类）。
 */
public interface InventoryTransferService {

    /** 调拨单分页。 */
    PageResult<InventoryTransfer> page(Integer page, Integer size, String keyword,
                                       String status, String itemType);

    /** 单据详情（含明细）。不存在返回 {@code null}。 */
    InventoryTransferDetail get(String transferNo);

    /**
     * 建单 / 更新（发出、收货）。
     *
     * <p>{@code transferNo} 为空 → 建单（取号 TR*，落 {@code DRAFT}）；
     * 非空 → 按状态机流转 + 更新可改字段。终态 {@code DONE} 拒绝再改。
     */
    InventoryTransfer save(String transferNo, InvTransfer body);
}
