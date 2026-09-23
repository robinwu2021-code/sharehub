package ai.neargo.sharehub.operation.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 运营管理域的**读模型**出参，逐字段镜像前端 `lib/types/operation.ts`。
 *
 * <p>它们不是实体：站点概览与单站统计都是跨域聚合的结果（站点在 platform、机柜与订单在 core、
 * 分成在 finance），没有也不该有对应的表。
 *
 * <p><b>为什么用 record 而不是 Map</b>：这几个形状是前后端的契约，字段拼错在 Map 里
 * 要等到界面上少了一块才发现；record 编译期就拦住。与 {@code ReportMappers} 返回 Map
 * 的取舍不同 —— 那里 Map 是**聚合 SQL 的中间结果**，这里是**对外契约**。
 */
public final class OperationDtos {

    private OperationDtos() {
    }

    /** 规模指标（存量，不随时间筛选变化）。 */
    public record OverviewScale(int siteTotal, int siteActive, int sitePaused,
                                int pointTotal, int cabinetTotal, int cabinetOnline,
                                double onlineRate,
                                int powerbankTotal, int powerbankInCabinet,
                                int powerbankRented, int powerbankFault) {
    }

    /** 经营指标（随时间筛选变化）。 */
    public record OverviewBusiness(int orders, BigDecimal gmv, String currency,
                                   BigDecimal avgOrderValue, double ordersPerCabinetPerDay) {
    }

    public record TrendPoint(String day, int orders, BigDecimal gmv) {
    }

    public record SiteRankRow(String siteNo, String siteName, String venueName, int cabinetCount,
                              BigDecimal gmv, int orders, double perCabinet, double onlineRate) {
    }

    /**
     * 待关注站点。
     *
     * @param kind     ALL_OFFLINE / NO_CABINET / CONTRACT_EXPIRED / CONTRACT_SOON /
     *                 NO_PRICE_PLAN / NO_SHARING / NO_ORDER
     * @param severity high / medium / low
     * @param detail   **必须带具体数字**（「3 台机柜全部离线 5 小时」）——
     *                 只说「设备异常」的提示，运营看了还是不知道该先处理哪个
     */
    public record AttentionItem(String siteNo, String siteName, String kind,
                                String severity, String detail) {
    }

    public record SceneShare(String sceneType, int siteCount, BigDecimal gmv) {
    }

    /** @param geoReady 有经纬度的站点数 / 总数 —— 地图能不能开就看它 */
    public record OperationOverview(OverviewScale scale, OverviewBusiness business,
                                    List<TrendPoint> trend, List<SiteRankRow> ranking,
                                    List<AttentionItem> attention, List<SceneShare> scenes,
                                    GeoReady geoReady) {
    }

    public record GeoReady(int withGeo, int total) {
    }

    public record PointStat(String locationNo, String locationName, int cabinetCount,
                            int orders, BigDecimal gmv, double perCabinet) {
    }

    public record SiteStats(String siteNo, String siteName, String from, String to,
                            int orders, BigDecimal gmv, String currency, BigDecimal avgOrderValue,
                            int avgDurationMin, double ordersPerCabinetPerDay, double onlineRate,
                            int cabinetCount, List<TrendPoint> trend, List<PointStat> byPoint) {
    }

    // ——— 分成两视角（清单 OM-S5 / OM-S6）———

    /** @param source 这条比例从哪来：CONTRACT（进场合同）/ RULE（分润规则） */
    public record SitePayee(String payeeType, String payeeNo, String payeeName,
                            BigDecimal rate, String mode, String source, String sourceNo) {
    }

    /**
     * @param platformRate 平台留存 = 1 - 各方合计。**可能为负** —— 那正是 INVALID 要暴露的情况
     * @param state        OK / MISSING / INVALID
     */
    public record SiteSharingRow(String siteNo, String siteName, String venueName,
                                 List<SitePayee> payees, BigDecimal totalRate, BigDecimal platformRate,
                                 String contractEndAt, String state, String stateDetail) {
    }

    public record SharingStats(int total, int ok, int missing, int invalid) {
    }

    public record PayeeSiteRef(String siteNo, String siteName, BigDecimal rate) {
    }

    public record PayeeSharingRow(String payeeType, String payeeName, int siteCount,
                                  BigDecimal minRate, BigDecimal maxRate, BigDecimal amount30d,
                                  String currency, List<PayeeSiteRef> sites) {
    }
}
