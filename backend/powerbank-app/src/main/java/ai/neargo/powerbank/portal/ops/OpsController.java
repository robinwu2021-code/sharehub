package ai.neargo.powerbank.portal.ops;

import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dev.service.CabinetService;
import ai.neargo.powerbank.dto.Dto.*;
import ai.neargo.powerbank.loc.LocService;
import ai.neargo.powerbank.seed.SeedData;
import ai.neargo.powerbank.wo.service.WorkOrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ops 域运营端端点（对齐 docs/api §三 与 ops-web {@code http.ts}）：
 * 工作台 / 设备（柜机·仓位·远程指令）/ 工单 / 场所（站点·点位·场地方·合同）。
 *
 * <p>场所域（站点/点位/场地方/合同）已切 MariaDB 持久化（{@link LocService}，P4）；
 * 其余域仍走内存 {@link SeedData}（渐进替换）。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final SeedData db;
    private final LocService loc;
    private final CabinetService cabinetService;
    private final WorkOrderService workOrderService;

    public OpsController(SeedData db, LocService loc, CabinetService cabinetService, WorkOrderService workOrderService) {
        this.db = db;
        this.loc = loc;
        this.cabinetService = cabinetService;
        this.workOrderService = workOrderService;
    }

    @GetMapping("/dashboard")
    public DashboardStats dashboard() {
        return db.dashboard();
    }

    // —— 设备（MariaDB 持久化，经 CabinetService）——
    @GetMapping("/cabinets")
    public PageResult<Cabinet> cabinets(@RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String onlineStatus,
                                      @RequestParam(required = false) String status) {
        return cabinetService.page(page, size, keyword, onlineStatus, status);
    }

    @GetMapping("/cabinets/{cabinetNo}")
    public CabinetDetail cabinet(@PathVariable String cabinetNo) {
        return cabinetService.detail(cabinetNo);
    }

    @PostMapping("/cabinets/{cabinetNo}/commands")
    @PreAuthorize("@perm.can('device:command:send')")
    public CommandResult sendCommand(@PathVariable String cabinetNo, @RequestBody(required = false) Map<String, Object> body) {
        String type = body == null ? null : String.valueOf(body.get("type"));
        return cabinetService.sendCommand(cabinetNo, type, body);
    }

    // —— 工单（MariaDB 持久化，经 WorkOrderService）——
    @GetMapping("/work-orders")
    public PageResult<WorkOrder> workOrders(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String type) {
        return workOrderService.page(page, size, keyword, status, type);
    }

    @PostMapping("/work-orders/{woNo}/dispatch")
    @PreAuthorize("@perm.can('workorder:wo:dispatch')")
    public OkResult dispatch(@PathVariable String woNo, @RequestBody Map<String, Object> body) {
        String assignee = body == null ? null : String.valueOf(body.get("assignee"));
        return workOrderService.dispatch(woNo, assignee);
    }

    // —— 场所：站点 / 点位 / 场地方 / 合同（MariaDB 持久化，经 LocService，P4）——
    @GetMapping("/sites")
    public PageResult<Site> sites(@RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size,
                                @RequestParam(required = false) String keyword) {
        return loc.pageSites(page, size, keyword);
    }

    @PostMapping({"/sites", "/sites/{siteNo}"})
    @PreAuthorize("@perm.can('location:poi:create')")
    public Site saveSite(@PathVariable(required = false) String siteNo, @RequestBody Site in) {
        return loc.saveSite(siteNo, in);
    }

    @GetMapping("/locations")
    public PageResult<Location> locations(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword) {
        return loc.pageLocations(page, size, keyword);
    }

    @PostMapping({"/locations", "/locations/{locationNo}"})
    @PreAuthorize("@perm.can('location:poi:create')")
    public Location savePoint(@PathVariable(required = false) String locationNo, @RequestBody Location in) {
        return loc.savePoint(locationNo, in);
    }

    @GetMapping("/venues")
    public PageResult<Venue> venues(@RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String keyword) {
        return loc.pageVenues(page, size, keyword);
    }

    @GetMapping("/contracts")
    public PageResult<Contract> contracts(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword) {
        return loc.pageContracts(page, size, keyword);
    }
}
