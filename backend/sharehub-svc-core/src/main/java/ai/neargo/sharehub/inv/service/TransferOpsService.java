package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransferDetail;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.AssetDiff;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.ReceiveResult;

import java.util.List;

/**
 * 调拨作业（对齐清单 C4 / C8）：逐件明细、发货（机柜 → 运输中）、逐件签收（差异落资产差异表，机柜回在库并回写所在仓）、
 * 撤机回仓单、撤机清点差异。
 *
 * <p>单头的建单 / 草稿编辑仍在 {@link InventoryTransferService}；发货与签收应当走这里 ——
 * 只有这里会联动机柜状态与所在仓。
 */
public interface TransferOpsService {

    InventoryTransferDetail setItems(String transferNo, List<String> itemNos);

    InventoryTransferDetail ship(String transferNo);

    ReceiveResult receive(String transferNo, List<String> receivedNos, String note);

    PageResult<AssetDiff> diffs(Integer page, Integer size, String status, String sourceType, String sourceRef);

    AssetDiff resolveDiff(String diffNo, String note);

    /** 撤机回仓（C8）：从站点调回仓库的草稿单，一张撤机工单只生成一张（幂等）。没有可用仓库返回 null。 */
    String openReturn(String siteNo, String siteName, String cabinetNo, String woNo);

    /** 撤机（C8）/ 巡检（D5）清点与系统在柜数不符 → 一条数量差异。sourceType = REMOVAL / INSPECTION，sourceRef = 工单号。 */
    AssetDiff recordCountMismatch(String sourceType, String woNo, String siteNo, String cabinetNo, int expected, int actual);
}
