package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.gw.dto.GwDtos.CommandRecord;
import ai.neargo.sharehub.gw.service.CommandLogService;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransfer;
import ai.neargo.sharehub.inv.dto.InvDtos.InventoryTransferDetail;
import ai.neargo.sharehub.inv.dto.InvDtos.InvTransferReq;
import ai.neargo.sharehub.inv.service.InventoryTransferService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 设备管理 · 库存调拨 + 远程控制·指令记录（[api/README §3.2]，菜单叶：库存调拨 / 指令记录）。
 *
 * <p>两个看似不相干的叶子放在一个控制器里，是因为它们都属「设备管理」权限域
 * （{@code device:inventory:*} / {@code device:command:send}），且都只是读写各自单表；
 * 拆两个类只会多一份样板。业务上不共享任何状态。
 *
 * <p>路径 {@code /api/ops/inventory-transfers} 与 {@code /api/ops/command-records}，
 * 均不与 {@link OpsController}、{@link AlarmController} 已占用的子路径重叠。
 */
@RestController
@RequestMapping("/api/ops")
public class InventoryController {

    private final InventoryTransferService transferService;
    private final CommandLogService commandLogService;
    private final ai.neargo.sharehub.inv.WarehouseDirectory warehouses;
    private final ai.neargo.sharehub.inv.service.IntakeService intakes;

    public InventoryController(InventoryTransferService transferService,
                               CommandLogService commandLogService,
                               ai.neargo.sharehub.inv.WarehouseDirectory warehouses,
                               ai.neargo.sharehub.inv.service.IntakeService intakes) {
        this.transferService = transferService;
        this.commandLogService = commandLogService;
        this.warehouses = warehouses;
        this.intakes = intakes;
    }

    // —— 到仓待检（V192；菜单叶：设备管理 › 入库与质检 › 到仓待检）——

    @GetMapping("/inventory/intakes")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public PageResult<ai.neargo.sharehub.inv.dto.IntakeDtos.Intake> intakes(@RequestParam(required = false) Integer page,
                                                                         @RequestParam(required = false) Integer size,
                                                                         @RequestParam(required = false) String keyword,
                                                                         @RequestParam(required = false) String status,
                                                                         @RequestParam(required = false) String warehouseNo) {
        return intakes.page(page, size, keyword, status, warehouseNo);
    }

    @GetMapping("/inventory/intakes/{intakeNo}")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public ai.neargo.sharehub.inv.dto.IntakeDtos.IntakeDetail intake(@PathVariable String intakeNo) {
        return intakes.get(intakeNo);
    }

    @PostMapping("/inventory/intakes")
    @PreAuthorize("@perm.can('device:inventory:inbound')")
    public ai.neargo.sharehub.inv.dto.IntakeDtos.IntakeDetail createIntake(@RequestBody ai.neargo.sharehub.inv.dto.IntakeDtos.CreateReq req) {
        return intakes.create(req);
    }

    @PostMapping("/inventory/intakes/{intakeNo}/items/{itemType}/{itemNo}/qc")
    @PreAuthorize("@perm.can('device:qc:inspect')")
    public ai.neargo.sharehub.inv.dto.IntakeDtos.IntakeDetail inspectIntakeItem(@PathVariable String intakeNo, @PathVariable String itemType,
                                                                          @PathVariable String itemNo,
                                                                          @RequestBody ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcReq req) {
        return intakes.inspect(intakeNo, itemType, itemNo, req);
    }

    @PostMapping("/inventory/intakes/{intakeNo}/inbound")
    @PreAuthorize("@perm.can('device:inventory:inbound')")
    public ai.neargo.sharehub.inv.dto.IntakeDtos.IntakeDetail inboundIntake(@PathVariable String intakeNo,
                                                                      @RequestBody ai.neargo.sharehub.inv.dto.IntakeDtos.ItemsReq req) {
        return intakes.inbound(intakeNo, req);
    }

    @PostMapping("/inventory/intakes/{intakeNo}/dispose")
    @PreAuthorize("@perm.can('device:inventory:inbound')")
    public ai.neargo.sharehub.inv.dto.IntakeDtos.IntakeDetail disposeIntake(@PathVariable String intakeNo,
                                                                      @RequestBody ai.neargo.sharehub.inv.dto.IntakeDtos.DisposeReq req) {
        return intakes.dispose(intakeNo, req);
    }

    // —— 仓库（只读，过渡期：仓库由迁移种入，管理将来在独立库存服务里做）——

    @GetMapping("/inventory/warehouses")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem> warehouses() {
        return warehouses.list();
    }

    // —— 库存调拨（菜单叶：设备管理 › 库存调拨）——

    @GetMapping("/inventory-transfers")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public PageResult<InventoryTransfer> transfers(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String itemType) {
        return transferService.page(page, size, keyword, status, itemType);
    }

    /** 单据详情（含逐件明细，收货核对用）。 */
    @GetMapping("/inventory-transfers/{transferNo}")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public InventoryTransferDetail transfer(@PathVariable String transferNo) {
        return transferService.get(transferNo);
    }

    /** 建调拨单（落 DRAFT）。 */
    @PostMapping("/inventory-transfers")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public InventoryTransfer createTransfer(@RequestBody InvTransferReq body) {
        return transferService.save(null, body);
    }

    /** 更新调拨单：发出（→IN_TRANSIT）/ 收货（→DONE）/ 草稿期改单头。状态流转经状态机校验。 */
    @PostMapping("/inventory-transfers/{transferNo}")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public InventoryTransfer updateTransfer(@PathVariable String transferNo,
                                            @RequestBody InvTransferReq body) {
        return transferService.save(transferNo, body); // 单号只认路径：写入面里没有 transferNo
    }

    // —— 远程控制 · 指令记录（菜单叶：设备管理 › 远程控制·指令记录）——
    // 只读：发指令走已有的 POST /api/ops/cabinets/{no}/commands（OpsController）。

    @GetMapping("/command-records")
    @PreAuthorize("@perm.can('device:command:send')")
    public PageResult<CommandRecord> commandRecords(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String cabinetNo,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String status) {
        return commandLogService.page(page, size, keyword, cabinetNo, type, status);
    }
}
