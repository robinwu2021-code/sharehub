package ai.neargo.sharehub.portal.shared;

import ai.neargo.common.core.PageResult;
import ai.neargo.common.security.rbac.PermChecker;
import ai.neargo.sharehub.report.dto.ReportDtos.ConsumerInsight;
import ai.neargo.sharehub.report.dto.ReportDtos.ConsumerSegment;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportCustom;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportDevice;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportFinance;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportLocation;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportMetricDef;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportScreen;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportTrend;
import ai.neargo.sharehub.report.dto.ReportDtos.ScreenBoard;
import ai.neargo.sharehub.report.service.ReportService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 报表域端点（{@code /api/ops/reports/**}）：三张周期报表 + 趋势 + 实时大屏 + 自定义报表 + 消费者洞察。
 *
 * <p><b>全部只读</b>。路径与入参逐条对齐 {@code ops-web/lib/api/https/report.ts}，前端零改动。
 * 新增控制器前须核对已占用路径（{@code OpsController}/{@code LocExtController} 等同为
 * {@code /api/ops} 前缀），重复映射会让 Spring 启动直接失败。
 *
 * <p><b>权限码分配（与 {@code RolePerms} / ops-web {@code lib/permissions.ts} 对齐）</b>：
 * OPS 只持有 {@code report:device:read} 与 {@code report:location:read}；
 * {@code report:*} 由 FINANCE / BD / VIEWER / ADMIN 持有。因此 OPS 能看设备与点位报表，
 * 看不到财务/大屏/自定义/消费者 —— 这是设计意图（营收数据不进运维视角），不是漏配。
 *
 * <p><b>⚠️ 趋势端点是唯一需要动态权限码的叶</b>：{@code /trend} 一个端点服务三张报表，
 * kind 决定口径。若统一用一个码，OPS 打开设备报表页就会因为 trend 403 而只看到表格没有曲线；
 * 若放宽成「任一 report 码即可」，OPS 又能通过 {@code kind=FINANCE} 拿到 GMV。
 * 故这里**按 kind 逐个校验**（{@code report:<kind>:read}），@PreAuthorize 只做粗筛。
 */
@RestController
@RequestMapping("/api/ops/reports")
public class ReportController {

    private final ReportService reportService;
    private final PermChecker perm;

    public ReportController(ReportService reportService, PermChecker perm) {
        this.reportService = reportService;
        this.perm = perm;
    }

    // —— 三张周期报表（统计到昨日，T+1）——

    @GetMapping("/device")
    @PreAuthorize("@perm.can('report:device:read')")
    public PageResult<ReportDevice> device(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String period) {
        return reportService.device(page, size, keyword, period);
    }

    @GetMapping("/location")
    @PreAuthorize("@perm.can('report:location:read')")
    public PageResult<ReportLocation> location(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String period) {
        return reportService.location(page, size, keyword, period);
    }

    @GetMapping("/finance")
    @PreAuthorize("@perm.can('report:finance:read')")
    public PageResult<ReportFinance> finance(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String period) {
        return reportService.finance(page, size, keyword, period);
    }

    // —— 趋势（一个端点服务三张报表，权限按 kind 逐个校验，见类注释）——

    @GetMapping("/trend")
    @PreAuthorize("@perm.can('report:device:read') or @perm.can('report:location:read') "
            + "or @perm.can('report:finance:read')")
    public ReportTrend trend(@RequestParam(required = false) String kind,
                             @RequestParam(required = false) String period) {
        String k = kind == null || kind.isBlank() ? "FINANCE" : kind.trim().toUpperCase();
        String code = switch (k) {
            case "DEVICE" -> "report:device:read";
            case "LOCATION" -> "report:location:read";
            default -> "report:finance:read";
        };
        if (!perm.can(code)) {
            // 走 AccessDeniedException 而不是自定义 403：GlobalExceptionHandler 已把它映射成
            // {status:403, code:403}，与 @PreAuthorize 拒绝的响应体完全一致（前端只有一条分支）。
            throw new AccessDeniedException(code);
        }
        return reportService.trend(k, period);
    }

    // —— 实时大屏（统计今日 00:00 到当前小时，与周期报表口径刻意不同）——

    @GetMapping("/screen")
    @PreAuthorize("@perm.can('report:screen:read')")
    public PageResult<ReportScreen> screen(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword) {
        return reportService.screen(page, size, keyword);
    }

    @GetMapping("/screen-board")
    @PreAuthorize("@perm.can('report:screen:read')")
    public ScreenBoard screenBoard() {
        return reportService.screenBoard();
    }

    // —— 自定义报表 ——

    @GetMapping("/metrics")
    @PreAuthorize("@perm.can('report:custom:read')")
    public List<ReportMetricDef> metrics() {
        return reportService.metrics();
    }

    @GetMapping("/custom")
    @PreAuthorize("@perm.can('report:custom:read')")
    public PageResult<ReportCustom> custom(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String period,
                                           @RequestParam(required = false) String dim,
                                           @RequestParam(required = false) String metrics) {
        return reportService.custom(page, size, keyword, period, dim, metrics);
    }

    // —— 消费者洞察 ——

    @GetMapping("/consumer-insight")
    @PreAuthorize("@perm.can('report:consumer:read')")
    public ConsumerInsight consumerInsight() {
        return reportService.consumerInsight();
    }

    /**
     * 人群分层表。**前端从 user 域契约调它**（{@code lib/api/https/user.ts#listConsumerSegments}），
     * 但端点本就在 {@code /api/ops/reports/} 下，故实现落在本控制器 —— 迁契约会牵动 user 域与页面，
     * 本批不动。权限码与消费者洞察同码：两者是同一批人的表与图，分权会出现「有表无图」。
     */
    @GetMapping("/consumer-segments")
    @PreAuthorize("@perm.can('report:consumer:read')")
    public PageResult<ConsumerSegment> consumerSegments(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String keyword) {
        return reportService.consumerSegments(page, size, keyword);
    }
}
