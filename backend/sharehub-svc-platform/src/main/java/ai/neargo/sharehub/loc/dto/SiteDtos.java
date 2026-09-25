package ai.neargo.sharehub.loc.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 站点状态机（TDD-运营核心流程/03）的请求与响应。 */
public final class SiteDtos {

    private SiteDtos() {
    }

    /**
     * 建档 / 编辑。<b>没有 status</b>（状态只经动作接口改），<b>没有 agentNo</b>（归属只经划拨改）。
     * 营业时间必填：它决定离线类告警是否计时，缺了会把闭店断电当成故障。
     */
    public record SiteReq(String name, String nameAr, String venueNo, String brandNo, String regionId,
                          String address, BigDecimal lng, BigDecimal lat, String sceneType, String openHours,
                          String opsEmployeeNo) {
    }

    public record PauseReq(String reason, LocalDate pauseUntil) {
    }

    public record WithdrawReq(String reason, LocalDate plannedAt) {
    }

    public record CloseReq(String note) {
    }

    /** 站点运营信息（追加到 Site VO 的末尾）。 */
    public record SiteOps(String opsEmployeeNo, String operateAgentNo, LocalDateTime firstLiveAt, String pauseReason,
                          LocalDate pauseUntil, String withdrawReason, LocalDate withdrawPlannedAt,
                          LocalDateTime closedAt, String activeContractNo) {
    }

    public record SiteStatusLogItem(String event, String fromStatus, String toStatus, String operator, String reason,
                                    LocalDateTime at) {
    }

    public record SiteSummary(long preparing, long active, long paused, long withdrawing, long closed,
                              long missingOwner, long missingOpenHours) {
    }

    /** 门店生命周期只读漏斗的一行：签约前是商机，签约后是站点。 */
    public record LifecycleRow(String kind, String no, String name, String phase, LocalDateTime phaseSince,
                               Long daysInPhase, String owner) {
    }

    public record FunnelStage(String kind, String phase, long count, Double avgDaysInPhase) {
    }

    /** 现场勘测（C1）。不通过时 note 必填；信号 NONE 或不能接电不能判通过。 */
    public record SurveyReq(String signalLevel, Boolean powerOk, String placementNote, java.util.List<String> fileNos,
                            String result, String note) {
    }

    public record SiteSurvey(String surveyNo, String siteNo, String signalLevel, boolean powerOk, String placementNote,
                             java.util.List<String> fileNos, String result, String note, String surveyedBy,
                             LocalDateTime surveyedAt) {
    }
}
