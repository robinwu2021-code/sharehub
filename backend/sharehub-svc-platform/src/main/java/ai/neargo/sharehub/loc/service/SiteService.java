package ai.neargo.sharehub.loc.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.common.Checklist;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.SiteDtos.FunnelStage;
import ai.neargo.sharehub.loc.dto.SiteDtos.LifecycleRow;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteReq;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteStatusLogItem;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteSummary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 站点状态机（TDD-运营核心流程/03）。站点的写从 {@code LocService} 迁到这里；
 * {@code LocService.pageSites} 仍保留给 C 端「附近」等只读调用。
 *
 * <p>状态只经动作改：建档即 PREPARING，首台设备上线由系统转 ACTIVE。编辑接口不收 status 与 agentNo。
 */
public interface SiteService {

    record Query(Integer page, Integer size, String keyword, Boolean showArchived, String status,
                 Boolean missingOwner, Boolean missingOpenHours,
                 /* V162：只看某商家的站点 */ String agentNo) {

        public Query(Integer page, Integer size, String keyword, Boolean showArchived, String status,
                     Boolean missingOwner, Boolean missingOpenHours) {
            this(page, size, keyword, showArchived, status, missingOwner, missingOpenHours, null);
        }
    }

    /** 运维责任人对账 / 暂停到期扫描的结果。 */
    record SiteTickResult(int wentLive, int pauseOverdue, int autoClosed) {
    }

    PageResult<Site> page(Query q);

    Site get(String siteNo);

    SiteSummary summary();

    Site create(SiteReq r);

    /** 为商家建站（V162）：场地方、区域、营业时间可空，位置必填；同事务建默认点位。归属与责任由调用方写。 */
    Site createForMerchant(SiteReq r);

    /**
     * 商家默认站点（V157，优化方案 §2.4）：建或更新一个只带名称、地址、坐标的站点。
     *
     * <p>与 {@link #create} 分开，因为常规建站要求场地方、区域、营业时间 —— 一期场地方只是记录（需求方案 D10），
     * 商家补齐位置时这些都不存在。归属不在这里写：调用方随后走划拨，留流水并级联。
     *
     * @param siteNo 已有的默认站点编号；空 = 新建
     */
    Site upsertDefaultSite(String siteNo, String name, String address,
                           java.math.BigDecimal lng, java.math.BigDecimal lat);

    Site update(String siteNo, SiteReq r);

    Site pause(String siteNo, String reason, LocalDate pauseUntil);

    Site resume(String siteNo);

    Site withdraw(String siteNo, String reason, LocalDate plannedAt);

    Site close(String siteNo, String note);

    /** 归档：追加前置 —— 只有 CLOSED 的站点可归档。 */
    Site archive(String siteNo);

    Site unarchive(String siteNo);

    Checklist openingChecklist(String siteNo);

    Checklist closeGate(String siteNo);

    /** 现场勘测（C1）：追加一条，以最近一次为准。首台设备上线须最近一次为通过。 */
    ai.neargo.sharehub.loc.dto.SiteDtos.SiteSurvey recordSurvey(String siteNo, ai.neargo.sharehub.loc.dto.SiteDtos.SurveyReq req);

    List<ai.neargo.sharehub.loc.dto.SiteDtos.SiteSurvey> surveys(String siteNo);

    List<SiteStatusLogItem> statusLogs(String siteNo);

    /** 系统：设备上线事件与对账共用。非 PREPARING 什么都不做（幂等）。 */
    boolean goLiveIfPreparing(String siteNo, LocalDateTime at, String cause);

    SiteTickResult tick();

    /** 门店生命周期只读视图：签约前是商机，签约后是站点。 */
    PageResult<LifecycleRow> lifecycle(Integer page, Integer size, String keyword, String phase);

    List<FunnelStage> funnel();
}
