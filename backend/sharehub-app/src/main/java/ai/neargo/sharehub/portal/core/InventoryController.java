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
    private final ai.neargo.sharehub.inv.service.StockViewService stock;
    private final ai.neargo.sharehub.inv.remote.InventoryMigration migration;
    private final ai.neargo.sharehub.inv.service.WarehouseAdminService warehouseAdmin;
    private final ai.neargo.sharehub.inv.service.SupplierService suppliers;
    private final ai.neargo.sharehub.inv.service.DisposalService disposals;
    private final ai.neargo.sharehub.inv.service.StockThresholdService thresholds;
    private final ai.neargo.sharehub.inv.service.ShipmentService shipmentService;
    private final ai.neargo.sharehub.inv.service.MaterialService materials;
    private final ai.neargo.common.security.rbac.PermChecker perm;

    public InventoryController(InventoryTransferService transferService,
                               CommandLogService commandLogService,
                               ai.neargo.sharehub.inv.WarehouseDirectory warehouses,
                               ai.neargo.sharehub.inv.service.IntakeService intakes,
                               ai.neargo.sharehub.inv.service.StockViewService stock,
                               ai.neargo.sharehub.inv.remote.InventoryMigration migration,
                               ai.neargo.sharehub.inv.service.WarehouseAdminService warehouseAdmin,
                               ai.neargo.sharehub.inv.service.SupplierService suppliers,
                               ai.neargo.sharehub.inv.service.DisposalService disposals,
                               ai.neargo.sharehub.inv.service.StockThresholdService thresholds,
                               ai.neargo.sharehub.inv.service.ShipmentService shipmentService,
                               ai.neargo.sharehub.inv.service.MaterialService materials,
                               ai.neargo.common.security.rbac.PermChecker perm) {
        this.materials = materials;
        this.perm = perm;
        this.shipmentService = shipmentService;
        this.thresholds = thresholds;
        this.disposals = disposals;
        this.warehouseAdmin = warehouseAdmin;
        this.suppliers = suppliers;
        this.transferService = transferService;
        this.commandLogService = commandLogService;
        this.warehouses = warehouses;
        this.intakes = intakes;
        this.stock = stock;
        this.migration = migration;
    }

    // —— 库存流水与盘点（S4；菜单叶：设备管理 › 仓储与出库 › 库存流水 / 盘点）——

    /** 当前库存引擎：盘点只在 SERVICE 下可用，页面据此提示。 */
    @GetMapping("/inventory/engine")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public ai.neargo.sharehub.inv.dto.StockDtos.InventoryEngine engine() {
        return stock.engine();
    }

    /** 统一库存视图（S6）：按生命周期阶段的台数。 */
    @GetMapping("/inventory/stages")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.StockDtos.StockStage> stages() {
        return stock.stages();
    }

    /** 设备库存余额（对接明细 B01）：库存服务按仓 × 设备类型的在手 / 占用 / 不可用 / 可用。 */
    @GetMapping("/inventory/balances")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.StockDtos.DeviceBalance> deviceBalances(@RequestParam(required = false) String warehouseNo,
                                                                                         @RequestParam(required = false) String itemType) {
        return stock.deviceBalances(warehouseNo, itemType);
    }

    /** 可出库的柜（对接明细 C01）：库存服务在库 ∩ 本地可出库。 */
    @GetMapping("/inventory/issue-candidates")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.StockDtos.IssueCandidate> issueCandidates(@RequestParam(required = false) String warehouseNo) {
        return stock.issueCandidates(warehouseNo);
    }

    /** 某商家的待安装柜（对接明细 C05）：库存服务出库池该商家已领取的 ∩ 本地可安装。建安装工单选设备用。 */
    @GetMapping("/inventory/installation-candidates")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public ai.neargo.common.core.PageResult<ai.neargo.sharehub.inv.dto.StockDtos.InstallCandidate> installationCandidates(
            @RequestParam String agentNo, @RequestParam(required = false) String siteNo,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return stock.installationCandidates(agentNo, siteNo, page, size);
    }

    /** 某仓在库的设备序列号（余额下钻）。 */
    @GetMapping("/inventory/serials")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public ai.neargo.common.core.PageResult<ai.neargo.sharehub.inv.dto.StockDtos.DeviceSerial> deviceSerials(@RequestParam String warehouseNo,
                                                                                                         @RequestParam(required = false) String itemType,
                                                                                                         @RequestParam(required = false) Integer page,
                                                                                                         @RequestParam(required = false) Integer size) {
        return stock.deviceSerials(warehouseNo, itemType, page, size);
    }

    /** 阶段下钻（P2 #6）：某阶段 × 类型的设备清单，口径同 stages。 */
    @GetMapping("/inventory/stage-items")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StageItems stageItems(@RequestParam String stage,
                                                                      @RequestParam(required = false) String itemType) {
        return stock.stageItems(stage, itemType);
    }

    /** 期间报表（S6）：期初 / 入 / 出 / 期末，按仓与设备类型。 */
    @GetMapping("/inventory/period-summary")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.StockDtos.StockPeriodRow> periodSummary(
            @RequestParam java.time.LocalDate from, @RequestParam java.time.LocalDate to,
            @RequestParam(required = false) String warehouseNo) {
        return stock.periodSummary(from, to, warehouseNo);
    }

    /** 存量迁移试跑（S7）：要入库的、要出库的、跳过的（附原因）、已完成的。与切换引擎同级 —— 系统参数权限。 */
    @GetMapping("/inventory/migration")
    @PreAuthorize("@perm.can('system:param:read')")
    public ai.neargo.sharehub.inv.remote.InventoryMigration.InventoryMigrationPlan migrationPlan() {
        return migration.plan();
    }

    /** 存量迁移执行一批（S7）：每台独立、可重跑；失败逐台列出。 */
    @PostMapping("/inventory/migration")
    @PreAuthorize("@perm.can('system:param:update')")
    public ai.neargo.sharehub.inv.remote.InventoryMigration.InventoryMigrationResult runMigration(
            @RequestBody(required = false) ai.neargo.sharehub.inv.dto.StockDtos.MigrationRunReq req) {
        int limit = req == null || req.limit() == null ? 200 : Math.max(1, Math.min(req.limit(), 1000));
        return migration.run(limit);
    }

    @GetMapping("/inventory/ledger")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public PageResult<ai.neargo.sharehub.inv.dto.StockDtos.StockLedgerRow> ledger(
            @RequestParam(required = false) String warehouseNo, @RequestParam(required = false) String itemType,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return stock.ledger(warehouseNo, itemType, keyword, page, size);
    }

    @GetMapping("/inventory/counts")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.StockDtos.StockCount> counts(
            @RequestParam(required = false) String warehouseNo, @RequestParam(required = false) String status) {
        return stock.counts(warehouseNo, status);
    }

    @GetMapping("/inventory/counts/{countNo}")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail count(@PathVariable String countNo) {
        return stock.count(countNo);
    }

    @PostMapping("/inventory/counts")
    @PreAuthorize("@perm.can('device:inventory:stocktake')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail openCount(
            @RequestBody ai.neargo.sharehub.inv.dto.StockDtos.OpenCountReq req) {
        return stock.openCount(req == null ? null : req.warehouseNo());
    }

    @PostMapping("/inventory/counts/{countNo}/scans")
    @PreAuthorize("@perm.can('device:inventory:stocktake')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail scanCount(@PathVariable String countNo,
            @RequestBody ai.neargo.sharehub.inv.dto.StockDtos.CountScanReq req) {
        return stock.scan(countNo, req == null ? null : req.serialNos());
    }

    @PostMapping("/inventory/counts/{countNo}/submit")
    @PreAuthorize("@perm.can('device:inventory:stocktake')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail submitCount(@PathVariable String countNo) {
        return stock.submit(countNo);
    }

    @PostMapping("/inventory/counts/{countNo}/approve")
    @PreAuthorize("@perm.can('device:inventory:stocktake')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail approveCount(@PathVariable String countNo,
            @RequestBody(required = false) ai.neargo.sharehub.inv.dto.StockDtos.CountNoteReq req) {
        return stock.approve(countNo, req == null ? null : req.note());
    }

    @PostMapping("/inventory/counts/{countNo}/reject")
    @PreAuthorize("@perm.can('device:inventory:stocktake')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail rejectCount(@PathVariable String countNo,
            @RequestBody(required = false) ai.neargo.sharehub.inv.dto.StockDtos.CountNoteReq req) {
        return stock.reject(countNo, req == null ? null : req.note());
    }

    @PostMapping("/inventory/counts/{countNo}/post")
    @PreAuthorize("@perm.can('device:inventory:stocktake')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail postCount(@PathVariable String countNo) {
        return stock.post(countNo);
    }

    @PostMapping("/inventory/counts/{countNo}/cancel")
    @PreAuthorize("@perm.can('device:inventory:stocktake')")
    public ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail cancelCount(@PathVariable String countNo) {
        return stock.cancel(countNo);
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

    // —— 仓库（V201 起可维护；默认回仓仓经系统参数 inv.return_warehouse 设置）——

    @GetMapping("/inventory/warehouses")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem> warehouses() {
        return warehouses.list();
    }

    @PostMapping("/inventory/warehouses")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem createWarehouse(@RequestBody ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseReq req) {
        return warehouseAdmin.create(req);
    }

    @PostMapping("/inventory/warehouses/{warehouseNo}")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem updateWarehouse(@PathVariable String warehouseNo,
                                @RequestBody ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseReq req) {
        return warehouseAdmin.update(warehouseNo, req);
    }

    @PostMapping("/inventory/warehouses/{warehouseNo}/archive")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem archiveWarehouse(@PathVariable String warehouseNo) {
        return warehouseAdmin.archive(warehouseNo);
    }

    @PostMapping("/inventory/warehouses/{warehouseNo}/unarchive")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem unarchiveWarehouse(@PathVariable String warehouseNo) {
        return warehouseAdmin.unarchive(warehouseNo);
    }

    // —— 处置出库（V203；菜单叶：设备管理 › 仓储与出库 › 处置出库）——

    @GetMapping("/inventory/disposals")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public PageResult<ai.neargo.sharehub.inv.dto.DisposalDtos.Disposal> disposals(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String kind,
                                              @RequestParam(required = false) String warehouseNo,
                                              @RequestParam(required = false) String supplierNo) {
        return disposals.page(page, size, keyword, kind, warehouseNo, supplierNo);
    }

    @GetMapping("/inventory/disposals/{disposalNo}")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public ai.neargo.sharehub.inv.dto.DisposalDtos.DisposalDetail disposal(@PathVariable String disposalNo) {
        return disposals.get(disposalNo);
    }

    /** 建单即过账：报废须原因码，退供应商须供应商。与发货同属出库，判 device:inventory:transfer（功能矩阵）。 */
    @PostMapping("/inventory/disposals")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public ai.neargo.sharehub.inv.dto.DisposalDtos.DisposalDetail createDisposal(@RequestBody ai.neargo.sharehub.inv.dto.DisposalDtos.DisposalReq req) {
        return disposals.create(req);
    }

    // —— 物料（V210；菜单：库存管理 › 物料档案 / 物料单据 / 物料库存）——

    @GetMapping("/inventory/materials")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public PageResult<ai.neargo.sharehub.inv.dto.MaterialDtos.Material> materials(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String keyword, @RequestParam(required = false) String category,
                                               @RequestParam(required = false) Boolean showArchived) {
        return materials.page(page, size, keyword, category, showArchived);
    }

    @PostMapping("/inventory/materials")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.MaterialDtos.Material createMaterial(@RequestBody ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialReq req) {
        return materials.create(req);
    }

    @PostMapping("/inventory/materials/{materialNo}")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.MaterialDtos.Material updateMaterial(@PathVariable String materialNo, @RequestBody ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialReq req) {
        return materials.update(materialNo, req);
    }

    @PostMapping("/inventory/materials/{materialNo}/archive")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.MaterialDtos.Material archiveMaterial(@PathVariable String materialNo) {
        return materials.archive(materialNo);
    }

    @PostMapping("/inventory/materials/{materialNo}/unarchive")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.MaterialDtos.Material unarchiveMaterial(@PathVariable String materialNo) {
        return materials.unarchive(materialNo);
    }

    @GetMapping("/inventory/material-docs")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public PageResult<ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDoc> materialDocs(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String keyword, @RequestParam(required = false) String kind,
                                                     @RequestParam(required = false) String purpose, @RequestParam(required = false) String warehouseNo,
                                                     @RequestParam(required = false) String materialNo) {
        return materials.docs(page, size, keyword, kind, purpose, warehouseNo, materialNo);
    }

    @GetMapping("/inventory/material-docs/{docNo}")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDoc materialDoc(@PathVariable String docNo) {
        return materials.doc(docNo);
    }

    /** 入库类用途判 :inbound，出库与调拨判 :transfer（与设备单据同口径）。 */
    @PostMapping("/inventory/material-docs")
    @PreAuthorize("@perm.can('device:inventory:inbound') or @perm.can('device:inventory:transfer')")
    public ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDoc createMaterialDoc(@RequestBody ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDocReq req) {
        boolean inbound = req != null && req.purpose() != null && java.util.Set.of("PURCHASE", "OPENING", "RETURN").contains(req.purpose());
        if (!perm.can(inbound ? "device:inventory:inbound" : "device:inventory:transfer"))
            throw new org.springframework.security.access.AccessDeniedException(inbound ? "device:inventory:inbound" : "device:inventory:transfer");
        return materials.createDoc(req);
    }

    @GetMapping("/inventory/material-balances")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialBalance> materialBalances(@RequestParam(required = false) String warehouseNo,
                                                                 @RequestParam(required = false) String materialNo,
                                                                 @RequestParam(required = false) String category,
                                                                 @RequestParam(required = false) Boolean lowOnly) {
        return materials.balances(warehouseNo, materialNo, category, lowOnly);
    }

    @PostMapping("/inventory/material-thresholds")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialBalance saveMaterialMinQty(@RequestBody ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialMinQtyReq req) {
        return materials.saveMinQty(req);
    }

    // —— 发货物流（V205）：按单据（docType + docRef）或设备号查 ——

    @GetMapping("/inventory/shipments")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.ShipmentDtos.Shipment> shipments(@RequestParam(required = false) String docType,
                                                                                  @RequestParam(required = false) String docRef,
                                                                                  @RequestParam(required = false) String itemNo) {
        return shipmentService.list(docType, docRef, itemNo);
    }

    // —— 安全库存（V204；仓库页「安全库存」）——

    @GetMapping("/inventory/stock-thresholds")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.ThresholdDtos.StockThreshold> stockThresholds(@RequestParam(required = false) String warehouseNo) {
        return thresholds.list(warehouseNo);
    }

    @PostMapping("/inventory/stock-thresholds")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.ThresholdDtos.StockThreshold saveStockThreshold(@RequestBody ai.neargo.sharehub.inv.dto.ThresholdDtos.StockThresholdReq req) {
        return thresholds.save(req);
    }

    // —— 供应商（V201）——

    @GetMapping("/inventory/suppliers")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public PageResult<ai.neargo.sharehub.inv.dto.SupplierDtos.Supplier> suppliers(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) Boolean showArchived) {
        return suppliers.page(page, size, keyword, showArchived);
    }

    /** 供应商绩效（V207）：到货 / 入库 / 不合格 / 退货台数与合格率。 */
    @GetMapping("/inventory/suppliers/stats")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public java.util.List<ai.neargo.sharehub.inv.dto.SupplierDtos.SupplierStats> supplierStats() {
        return suppliers.stats();
    }

    @PostMapping("/inventory/suppliers")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.SupplierDtos.Supplier createSupplier(@RequestBody ai.neargo.sharehub.inv.dto.SupplierDtos.SupplierReq req) {
        return suppliers.create(req);
    }

    @PostMapping("/inventory/suppliers/{supplierNo}")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.SupplierDtos.Supplier updateSupplier(@PathVariable String supplierNo,
                              @RequestBody ai.neargo.sharehub.inv.dto.SupplierDtos.SupplierReq req) {
        return suppliers.update(supplierNo, req);
    }

    @PostMapping("/inventory/suppliers/{supplierNo}/archive")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.SupplierDtos.Supplier archiveSupplier(@PathVariable String supplierNo) {
        return suppliers.archive(supplierNo);
    }

    @PostMapping("/inventory/suppliers/{supplierNo}/unarchive")
    @PreAuthorize("@perm.can('device:inventory:warehouse')")
    public ai.neargo.sharehub.inv.dto.SupplierDtos.Supplier unarchiveSupplier(@PathVariable String supplierNo) {
        return suppliers.unarchive(supplierNo);
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
