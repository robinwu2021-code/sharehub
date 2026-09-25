package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcRecord;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcReq;
import ai.neargo.sharehub.dev.service.QcService;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransferDetail;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.AssetDiff;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.ItemsReq;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.ReceiveReq;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.ReceiveResult;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.ResolveReq;
import ai.neargo.sharehub.inv.service.TransferOpsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备资产线（运营核心流程批次 C）：入库质检 · 调拨逐件明细 / 发货 / 逐件签收 · 资产差异。
 *
 * <p>调拨的建单与草稿编辑仍在 {@code InventoryController}；<b>发货与签收走这里</b> —— 只有这里联动机柜状态
 * （IN_STOCK ⇄ IN_TRANSIT）与所在仓，并把签收差异落资产差异表。
 */
@RestController
@RequestMapping("/api/ops")
public class AssetOpsController {

    private final QcService qc;
    private final TransferOpsService transfers;

    public AssetOpsController(QcService qc, TransferOpsService transfers) {
        this.qc = qc;
        this.transfers = transfers;
    }

    // —— 入库质检 ——

    @PostMapping("/devices/{cabinetNo}/qc")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public QcRecord inspectCabinet(@PathVariable String cabinetNo, @RequestBody QcReq r) {
        return qc.inspectCabinet(cabinetNo, r);
    }

    @PostMapping("/powerbanks/{powerbankNo}/qc")
    @PreAuthorize("@perm.can('device:powerbank:update')")
    public QcRecord inspectPowerbank(@PathVariable String powerbankNo, @RequestBody QcReq r) {
        return qc.inspectPowerbank(powerbankNo, r);
    }

    @GetMapping("/qc-records")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public List<QcRecord> qcRecords(@RequestParam String itemNo) {
        return qc.records(itemNo);
    }

    // —— 调拨作业 ——

    @PostMapping("/inventory-transfers/{transferNo}/items")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public InventoryTransferDetail setItems(@PathVariable String transferNo, @RequestBody ItemsReq r) {
        return transfers.setItems(transferNo, r == null ? null : r.itemNos());
    }

    @PostMapping("/inventory-transfers/{transferNo}/ship")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public InventoryTransferDetail ship(@PathVariable String transferNo) {
        return transfers.ship(transferNo);
    }

    @PostMapping("/inventory-transfers/{transferNo}/receive")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public ReceiveResult receive(@PathVariable String transferNo, @RequestBody ReceiveReq r) {
        return transfers.receive(transferNo, r == null ? null : r.receivedNos(), r == null ? null : r.note());
    }

    // —— 资产差异 ——

    @GetMapping("/asset-diffs")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public PageResult<AssetDiff> diffs(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String status, @RequestParam(required = false) String sourceType,
                                       @RequestParam(required = false) String sourceRef) {
        return transfers.diffs(page, size, status, sourceType, sourceRef);
    }

    @PostMapping("/asset-diffs/{diffNo}/resolve")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public AssetDiff resolve(@PathVariable String diffNo, @RequestBody ResolveReq r) {
        return transfers.resolveDiff(diffNo, r == null ? null : r.note());
    }
}
