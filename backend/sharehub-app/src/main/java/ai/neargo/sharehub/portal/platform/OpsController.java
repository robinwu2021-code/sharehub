package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import org.springframework.web.bind.annotation.RequestBody;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.CabinetDetail;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.CommandResult;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.LocDtos.Location;
import ai.neargo.sharehub.loc.dto.LocDtos.Venue;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.loc.entity.LocVenue;
import ai.neargo.sharehub.loc.LocService;
import ai.neargo.sharehub.report.dto.ReportDtos.DashboardStats;
import ai.neargo.sharehub.report.service.ReportService;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * ops 域运营端端点（对齐 docs/api §三 与 ops-web {@code http.ts}）：
 * 工作台 / 设备（柜机·仓位·远程指令）/ 工单 / 场所（站点·点位·场地方·合同）。
 *
 * <p>全部端点走 Service 落库/实算：场所域经 {@link LocService}、设备经 {@link CabinetService}、
 * 工单经 {@link WorkOrderService}、工作台聚合经报表域 {@link ReportService}。内存种子骨架已退役。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final LocService loc;
    private final CabinetService cabinetService;
    private final WoOpsService woOpsService;
    private final ReportService reportService;
    private final ai.neargo.sharehub.portal.ops.WorkOrderAssembler workOrders;

    public OpsController(LocService loc, CabinetService cabinetService,
                         WoOpsService woOpsService, ReportService reportService,
                         ai.neargo.sharehub.portal.ops.WorkOrderAssembler workOrders) {
        this.workOrders = workOrders;
        this.loc = loc;
        this.cabinetService = cabinetService;
        this.woOpsService = woOpsService;
        this.reportService = reportService;
    }

    /** 工作台聚合：真表实算（报表域读模型），内存种子骨架已退役。 */
    @GetMapping("/dashboard")
    @PreAuthorize("@perm.can('dashboard:overview:read')")
    public DashboardStats dashboard() {
        return reportService.dashboard();
    }

    // —— 设备（MariaDB 持久化，经 CabinetService）——
    @GetMapping("/cabinets")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public PageResult<Cabinet> cabinets(@RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String onlineStatus,
                                      @RequestParam(required = false) String status) {
        return cabinetService.page(page, size, keyword, onlineStatus, status);
    }

    @GetMapping("/cabinets/{cabinetNo}")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public CabinetDetail cabinet(@PathVariable String cabinetNo) {
        return cabinetService.detail(cabinetNo);
    }

    /**
     * 机柜建档 / 编辑。归属随点位级联（siteNo/agentNo 由后端反查，不接受前端指定）。
     *
     * <p>两条路径共用：不带 {@code cabinetNo} = 新建，带 = 编辑该台 —— 与 sites/locations 同构。
     */
    @PostMapping({"/cabinets", "/cabinets/{cabinetNo}"})
    @PreAuthorize("@perm.can('device:cabinet:create')")
    public Object saveCabinet(@PathVariable(required = false) String cabinetNo,
                              @RequestBody java.util.Map<String, Object> body) {
        return cabinetService.save(cabinetNo, body);
    }

    @PostMapping("/cabinets/{cabinetNo}/commands")
    @PreAuthorize("@perm.can('device:command:send')")
    public CommandResult sendCommand(@PathVariable String cabinetNo, @RequestBody(required = false) Map<String, Object> body) {
        String type = body == null ? null : String.valueOf(body.get("type"));
        return cabinetService.sendCommand(cabinetNo, type, body);
    }

    // —— 工单（MariaDB 持久化，经 WorkOrderService）——
    @GetMapping("/work-orders")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public PageResult<WorkOrder> workOrders(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String type,
                                          // 2026-09-25 承接业务告警：运营维度筛选；出参追加 ops（SLA 剩余、关联告警数、复核结果…）
                                          @RequestParam(required = false) String priority,
                                          @RequestParam(required = false) String source,
                                          @RequestParam(required = false) String siteNo,
                                          @RequestParam(required = false) String assigneeNo,
                                          @RequestParam(required = false) String slaState,
                                          @RequestParam(required = false) String reviewStatus) {
        return workOrders.page(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WoQuery(page, size, keyword, status, type,
                priority, source, siteNo, assigneeNo, slaState, reviewStatus));
    }

    @PostMapping("/work-orders/{woNo}/dispatch")
    @PreAuthorize("@perm.can('workorder:wo:dispatch')")
    public WorkOrder dispatch(@PathVariable String woNo, @RequestBody Map<String, Object> body) {
        String assignee = body == null ? null : String.valueOf(body.get("assignee"));
        return woOpsService.dispatch(woNo, assignee);   // 迁移 + wo_dispatch 时间轴留痕（ext 编排）
    }

    // —— 场所：点位 / 场地方（站点已迁入 SiteController：状态机 + 门禁；合同在 ContractController）——
    @GetMapping("/locations")
    @PreAuthorize("@perm.can('location:poi:read')")
    public PageResult<Location> locations(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Boolean showArchived) {
        return loc.pageLocations(page, size, keyword, showArchived);
    }

    @PostMapping({"/locations", "/locations/{locationNo}"})
    @PreAuthorize("@perm.can('location:poi:create')")
    public Location savePoint(@PathVariable(required = false) String locationNo, @RequestBody Location in) {
        return loc.savePoint(locationNo, in);
    }

    @GetMapping("/venues")
    @PreAuthorize("@perm.can('location:venue:read')")
    public PageResult<Venue> venues(@RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Boolean showArchived) {
        return loc.pageVenues(page, size, keyword, showArchived);
    }

    /**
     * 新建 / 修改场地方。
     *
     * <p>此前只有 GET 与归档 —— 前端的「新增/编辑场地方」在 {@code USE_MOCK=0} 下必 404，
     * 整条「场地方 → 合同 → 站点 → 责任 → 分账」在真后端下从第一环就断了。
     */
    @PostMapping("/venues")
    @PreAuthorize("@perm.can('location:venue:create')")
    public Venue createVenue(@RequestBody LocVenue body) {
        body.setVenueNo(null);   // 新建一律服务端取号，忽略 body 里的键
        return loc.saveVenue(body);
    }

    @PostMapping("/venues/{venueNo}")
    @PreAuthorize("@perm.can('location:venue:update')")
    public Venue updateVenue(@PathVariable String venueNo, @RequestBody LocVenue body) {
        body.setVenueNo(venueNo); // 路径为准，防越权改别家档案
        return loc.saveVenue(body);
    }

    // 合同的读写已迁入 ContractController（2026-09-25：合同走审批，状态只经动作接口改）。

    /**
     * 归档Cabinet。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/cabinets/{no}/archive")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Object archiveCabinet(@PathVariable String no) {
        return cabinetService.archive(no);
    }

    /** 取消归档Cabinet：清空时间戳，回到默认列表。 */
    @PostMapping("/cabinets/{no}/unarchive")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Object unarchiveCabinet(@PathVariable String no) {
        return cabinetService.unarchive(no);
    }

    // ───────────── 场所域归档（前端契约 Archivable）─────────────
    // 站点/点位/场地方共用 LocService 的归档实现：archivedAt 时间戳，null=在用。
    // **不是删除** —— 勾「显示已归档」可见，可恢复。

    @PostMapping("/locations/{no}/archive")
    @PreAuthorize("@perm.can('location:poi:update')")
    public Object archiveLocation(@PathVariable String no) {
        return loc.archiveLocation(no);
    }

    @PostMapping("/locations/{no}/unarchive")
    @PreAuthorize("@perm.can('location:poi:update')")
    public Object unarchiveLocation(@PathVariable String no) {
        return loc.unarchiveLocation(no);
    }

    @PostMapping("/venues/{no}/archive")
    @PreAuthorize("@perm.can('location:venue:update')")
    public Object archiveVenue(@PathVariable String no) {
        return loc.archiveVenue(no);
    }

    @PostMapping("/venues/{no}/unarchive")
    @PreAuthorize("@perm.can('location:venue:update')")
    public Object unarchiveVenue(@PathVariable String no) {
        return loc.unarchiveVenue(no);
    }

    /**
     * 批量导入机柜。**逐行校验、整批回滚** ——
     * 部分成功会让运营不知道该重传全部还是补传剩余，重传已成功的行还会撞唯一键。
     */
    @PostMapping("/cabinets/import")
    @PreAuthorize("@perm.can('device:cabinet:create')")
    @SuppressWarnings("unchecked")
    public Object importCabinets(@RequestBody java.util.Map<String, Object> body) {
        Object rows = body == null ? null : body.get("rows");
        if (!(rows instanceof java.util.List<?> list)) {
            throw new IllegalArgumentException("导入数据 rows 必须是数组");
        }
        return java.util.Map.of("imported",
                cabinetService.importRows((java.util.List<java.util.Map<String, Object>>) list));
    }
}
