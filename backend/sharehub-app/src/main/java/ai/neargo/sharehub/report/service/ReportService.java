package ai.neargo.sharehub.report.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.report.dto.ReportDtos.ConsumerInsight;
import ai.neargo.sharehub.report.dto.ReportDtos.ConsumerSegment;
import ai.neargo.sharehub.report.dto.ReportDtos.DashboardStats;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportCustom;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportDevice;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportFinance;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportLocation;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportMetricDef;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportScreen;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportTrend;
import ai.neargo.sharehub.report.dto.ReportDtos.ScreenBoard;
import ai.neargo.sharehub.report.dto.ReportDtos.SiteRollup;

import java.time.LocalDate;
import java.util.List;

/**
 * 报表域读模型（[db-design §12.3]，本域**零物理表**）。
 *
 * <p><b>周期是第一入参，不是可选筛选项</b>：不写清「统计的是哪段时间」的报表等于没有口径。
 * 所有 {@code period} 参数经 {@code ReportPeriods.normalize} 归一化，非法值落回 {@code LAST_30D}。
 *
 * <p><b>两条时间口径必须同时记住，否则同一屏出现两个「今日」</b>：
 * <ul>
 *   <li>周期报表（device/location/finance/custom/trend）统计到 <b>昨日</b>（T+1 跑批）；</li>
 *   <li>大屏（screen/screen-board）统计 <b>今日 00:00 到当前小时</b>。</li>
 * </ul>
 */
public interface ReportService {

    /** 工作台聚合（今日统计卡 + 7 日趋势 + 待办中心 + 告警提醒条 + 站点排行）。今日口径同大屏。 */
    DashboardStats dashboard();

    /** 设备运营周期报表（按站点名一行）。 */
    PageResult<ReportDevice> device(Integer page, Integer size, String keyword, String period);

    /** 点位坪效周期报表（按站点名一行）。 */
    PageResult<ReportLocation> location(Integer page, Integer size, String keyword, String period);

    /** 财务周期报表（按周期桶一行 = 趋势图一个点）。 */
    PageResult<ReportFinance> finance(Integer page, Integer size, String keyword, String period);

    /** 大屏 KPI 列表形态（老页面用；新看板走 {@link #screenBoard()}，两者 KPI 同源同值）。 */
    PageResult<ReportScreen> screen(Integer page, Integer size, String keyword);

    /**
     * 自定义报表（长表 dim × metric × value）。
     *
     * @param dim     SITE|SCENE|MONTH，非法/空 = SITE
     * @param metrics csv（如 {@code GMV,ORDERS}），空 = 默认三项；目录外的 key 静默丢弃
     */
    PageResult<ReportCustom> custom(Integer page, Integer size, String keyword,
                                    String period, String dim, String metrics);

    /** 趋势 + 汇总条。{@code kind} ∈ DEVICE|LOCATION|FINANCE，决定汇总口径；非法值落回 FINANCE。 */
    ReportTrend trend(String kind, String period);

    /** 实时大屏聚合视图（一次返回 KPI/分时/排名/柜机构成）。 */
    ScreenBoard screenBoard();

    /** 自定义报表指标目录（静态，与前端 {@code REPORT_METRICS} 同源）。 */
    List<ReportMetricDef> metrics();

    /** 消费者洞察：转化漏斗 + 画像分布（存量口径，不切周期）。 */
    ConsumerInsight consumerInsight();

    /** 人群分层表（存量口径）。历史上挂在 user 域端点下，实现落本域，两处同一份数据。 */
    PageResult<ConsumerSegment> consumerSegments(Integer page, Integer size, String keyword);

    /**
     * 站点卷积事实 —— **给 loc 域坪效端点（{@code /api/ops/site-analysis}）复用**。
     *
     * <p>暴露在接口上而不是藏在 impl 里，是为了让「站点坪效」页与「点位报表」页
     * 物理上不可能给出两个不同的营收；两处各查一次 SQL 也无所谓，口径是同一段代码。
     *
     * @param from 统计起始业务日（含）
     * @param to   统计截止业务日（含）
     */
    List<SiteRollup> siteRollups(LocalDate from, LocalDate to);
}
