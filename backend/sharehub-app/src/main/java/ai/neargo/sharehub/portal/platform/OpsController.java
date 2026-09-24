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

    public OpsController(LocService loc, CabinetService cabinetService,
                         WoOpsService woOpsService, ReportService reportService) {
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
                                          @RequestParam(required = false) String type) {
        return woOpsService.pageRich(page, size, keyword, status, type);
    }

    @PostMapping("/work-orders/{woNo}/dispatch")
    @PreAuthorize("@perm.can('workorder:wo:dispatch')")
    public WorkOrder dispatch(@PathVariable String woNo, @RequestBody Map<String, Object> body) {
        String assignee = body == null ? null : String.valueOf(body.get("assignee"));
        return woOpsService.dispatch(woNo, assignee);   // 迁移 + wo_dispatch 时间轴留痕（ext 编排）
    }

    // —— 场所：站点 / 点位 / 场地方 / 合同（MariaDB 持久化，经 LocService，P4）——
    @GetMapping("/sites")
    @PreAuthorize("@perm.can('location:poi:read')")
    public PageResult<Site> sites(@RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size,
                                @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) Boolean showArchived) {
        return loc.pageSites(page, size, keyword, showArchived);
    }

    @PostMapping({"/sites", "/sites/{siteNo}"})
    @PreAuthorize("@perm.can('location:poi:create')")
    public Site saveSite(@PathVariable(required = false) String siteNo, @RequestBody Site in) {
        return loc.saveSite(siteNo, in);
    }

    /**
     * 暂停 / 恢复营业（运营管理清单 OM-S3）。
     *
     * <p>与「归档」分开：归档是「这个站点不在经营范围里了」，停业是「暂时不做生意」。
     * 两者混成一个开关的话，运营想临时停业就只能归档，而归档会把它从所有列表里拿掉。
     */
    @PostMapping("/sites/{siteNo}/pause")
    @PreAuthorize("@perm.can('location:poi:update')")
    public Site pauseSite(@PathVariable String siteNo, @RequestBody(required = false) java.util.Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return loc.pauseSite(siteNo, reason == null ? null : String.valueOf(reason));
    }

    @PostMapping("/sites/{siteNo}/resume")
    @PreAuthorize("@perm.can('location:poi:update')")
    public Site resumeSite(@PathVariable String siteNo) {
        return loc.resumeSite(siteNo);
    }

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

    @GetMapping("/contracts")
    @PreAuthorize("@perm.can('location:contract:read')")
    public PageResult<Contract> contracts(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword) {
        return loc.pageContracts(page, size, keyword);
    }

    /**
     * 新建 / 修改进场合同。
     *
     * <p><b>此前这个端点根本不存在</b>：合同只有种子在写，前端的「新增/编辑」按钮
     * 在 {@code USE_MOCK=0} 下必 404。而合同是场地方分成的唯一依据 ——
     * 建不了合同，场地方费率就只能靠改库。
     */
    @PostMapping("/contracts")
    @PreAuthorize("@perm.can('location:contract:create')")
    public Contract createContract(@RequestBody LocContract body) {
        body.setContractNo(null);   // 新建一律服务端取号，忽略 body 里的键
        return loc.saveContract(body);
    }

    @PostMapping("/contracts/{contractNo}")
    @PreAuthorize("@perm.can('location:contract:update')")
    public Contract updateContract(@PathVariable String contractNo, @RequestBody LocContract body) {
        body.setContractNo(contractNo); // 路径为准，防越权改别人的合同
        return loc.saveContract(body);
    }

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

    @PostMapping("/sites/{no}/archive")
    @PreAuthorize("@perm.can('location:site:update')")
    public Object archiveSite(@PathVariable String no) {
        return loc.archiveSite(no);
    }

    @PostMapping("/sites/{no}/unarchive")
    @PreAuthorize("@perm.can('location:site:update')")
    public Object unarchiveSite(@PathVariable String no) {
        return loc.unarchiveSite(no);
    }

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
