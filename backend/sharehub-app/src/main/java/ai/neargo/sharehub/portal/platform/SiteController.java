package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.common.Checklist;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.SiteDtos.CloseReq;
import ai.neargo.sharehub.loc.dto.SiteDtos.PauseReq;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteReq;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteStatusLogItem;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteSummary;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteSurvey;
import ai.neargo.sharehub.loc.dto.SiteDtos.SurveyReq;
import ai.neargo.sharehub.loc.dto.SiteDtos.WithdrawReq;
import ai.neargo.sharehub.loc.service.SiteService;
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
 * 站点（2026-09-25 定：站点状态与门店生命周期合并为一套状态机；TDD-运营核心流程/03）。从 OpsController 拆出，路径不变。
 *
 * <p>状态只经动作端点改：pause · resume · withdraw · close；PREPARING → ACTIVE 由首台设备上线系统触发，无端点。
 * 建档 / 编辑收 {@link SiteReq}：没有 status（状态只经动作改），没有 agentNo（归属只经划拨改）。
 */
@RestController
@RequestMapping("/api/ops/sites")
public class SiteController {

    private final SiteService sites;

    public SiteController(SiteService sites) {
        this.sites = sites;
    }

    @GetMapping
    @PreAuthorize("@perm.can('location:poi:read')")
    public PageResult<Site> page(@RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) Boolean showArchived,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Boolean missingOwner,
                                 @RequestParam(required = false) Boolean missingOpenHours) {
        return sites.page(new SiteService.Query(page, size, keyword, showArchived, status, missingOwner, missingOpenHours));
    }

    @GetMapping("/summary")
    @PreAuthorize("@perm.can('location:poi:read')")
    public SiteSummary summary() {
        return sites.summary();
    }

    @GetMapping("/{siteNo}")
    @PreAuthorize("@perm.can('location:poi:read')")
    public Site get(@PathVariable String siteNo) {
        return sites.get(siteNo);
    }

    @PostMapping
    @PreAuthorize("@perm.can('location:poi:create')")
    public Site create(@RequestBody SiteReq body) {
        return sites.create(body);
    }

    @PostMapping("/{siteNo}")
    @PreAuthorize("@perm.can('location:poi:create')")
    public Site update(@PathVariable String siteNo, @RequestBody SiteReq body) {
        return sites.update(siteNo, body);
    }

    /** 暂停营业：停借保还 —— 已借出的仍可归还，停业不该把用户的充电宝扣在手里。 */
    @PostMapping("/{siteNo}/pause")
    @PreAuthorize("@perm.can('location:poi:update')")
    public Site pause(@PathVariable String siteNo, @RequestBody(required = false) PauseReq body) {
        return sites.pause(siteNo, body == null ? null : body.reason(), body == null ? null : body.pauseUntil());
    }

    /** 恢复营业：站点须有生效合同。 */
    @PostMapping("/{siteNo}/resume")
    @PreAuthorize("@perm.can('location:poi:update')")
    public Site resume(@PathVariable String siteNo) {
        return sites.resume(siteNo);
    }

    @PostMapping("/{siteNo}/withdraw")
    @PreAuthorize("@perm.can('location:poi:update')")
    public Site withdraw(@PathVariable String siteNo, @RequestBody(required = false) WithdrawReq body) {
        return sites.withdraw(siteNo, body == null ? null : body.reason(), body == null ? null : body.plannedAt());
    }

    /** 关闭：无设备、无未结工单、无在借订单（见 close-gate）。 */
    @PostMapping("/{siteNo}/close")
    @PreAuthorize("@perm.can('location:poi:update')")
    public Site close(@PathVariable String siteNo, @RequestBody(required = false) CloseReq body) {
        return sites.close(siteNo, body == null ? null : body.note());
    }

    @GetMapping("/{siteNo}/opening-checklist")
    @PreAuthorize("@perm.can('location:poi:read')")
    public Checklist openingChecklist(@PathVariable String siteNo) {
        return sites.openingChecklist(siteNo);
    }

    @GetMapping("/{siteNo}/close-gate")
    @PreAuthorize("@perm.can('location:poi:read')")
    public Checklist closeGate(@PathVariable String siteNo) {
        return sites.closeGate(siteNo);
    }

    /** 现场勘测（C1）：信号 / 电源 / 可摆位置 / 照片 / 结论；以最近一次为准，首台设备上线须通过。 */
    @PostMapping("/{siteNo}/surveys")
    @PreAuthorize("@perm.can('location:poi:update')")
    public SiteSurvey recordSurvey(@PathVariable String siteNo, @RequestBody SurveyReq r) {
        return sites.recordSurvey(siteNo, r);
    }

    @GetMapping("/{siteNo}/surveys")
    @PreAuthorize("@perm.can('location:poi:read')")
    public List<SiteSurvey> surveys(@PathVariable String siteNo) {
        return sites.surveys(siteNo);
    }

    @GetMapping("/{siteNo}/status-logs")
    @PreAuthorize("@perm.can('location:poi:read')")
    public List<SiteStatusLogItem> statusLogs(@PathVariable String siteNo) {
        return sites.statusLogs(siteNo);
    }

    /**
     * 归档 / 取消归档（前端契约 Archivable）。<b>只有 CLOSED 的站点可归档</b>。
     * 权限码沿用存量 {@code location:site:update}（真源表缺口已在 known-perm-ssot-gaps 登记，本次不动）。
     */
    @PostMapping("/{siteNo}/archive")
    @PreAuthorize("@perm.can('location:site:update')")
    public Site archive(@PathVariable String siteNo) {
        return sites.archive(siteNo);
    }

    @PostMapping("/{siteNo}/unarchive")
    @PreAuthorize("@perm.can('location:site:update')")
    public Site unarchive(@PathVariable String siteNo) {
        return sites.unarchive(siteNo);
    }
}
