package ai.neargo.sharehub.report.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 报表域出参 VO。**字段名逐字镜像 {@code ops-web/lib/types/report.ts}**，前端不需要任何适配层。
 *
 * <p><b>约定</b>（[骨架规约 §4]）：域内 dto 文件，不往顶层 {@code dto/Dto.java} 追加（已冻结）。
 *
 * <p><b>本域全部是读模型，不建物理表</b>（[db-design §12.3] 同「分润统计」）：
 * 由 {@code ord_order ⋈ loc_site ⋈ dev_cabinet ⋈ loc_contract ⋈ share_record} 聚合而来。
 *
 * <p><b>⚠️ 已知的口径空洞（本域最重要的注释，删之前先看 {@code ReportServiceImpl} 类注释）</b>：
 * 下列字段在当前库里**无处可取**，一律返回 0 / 缺省，<b>不得为了「表格好看」填近似值</b> ——
 * 财务报表里一个貌似合理的假聚合，比一个显眼的空洞危险得多（运营会拿它拍撤站/调价决策）：
 * <ul>
 *   <li>{@code ReportLocation.payback}（回本天数）—— 缺「单柜采购成本」；{@code loc_contract.entry_fee}
 *       只是进场费，拿它单独摊会系统性低估回本期，故不用。</li>
 *   <li>{@code ReportFinance.settle}（已结算）—— {@code share_record}/{@code stl_settlement} 现无数据
 *       （无 seeder、结算批处理未跑），聚合结果真实为 0。</li>
 *   <li>{@code ReportDevice.onlineRate}/{@code faultRate} —— {@code dev_heartbeat} 空、无按日快照表，
 *       只能给**当前快照**，所以它不随周期变化。</li>
 *   <li>运维成本（opex）—— 全站无表，故 {@code cost} 仅含场地分润一项。</li>
 * </ul>
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    // ————————————————————————————————————————————————————————————
    // 三张周期报表
    // ————————————————————————————————————————————————————————————

    /**
     * 设备运营报表一行 = 一个**站点名**。
     *
     * <p><b>为什么按名字而不是按 siteNo 聚合</b>：主数据里 20 个站点只用了 7 个名字，
     * 逐 siteNo 出行会出现两行同名不同数，前端 rowKey 也无处可取
     * （与 ops-web mock 文件头 ② 同一决策）。字段名 {@code locationName} 沿用前端契约。
     *
     * @param onlineRate 0..1，**当前快照**（缺按日在线快照表，见类注释）
     * @param turnover   翻台率 = 周期订单 / 天数 / 柜数（次/柜/日）
     * @param faultRate  0..1，**当前快照**
     */
    public record ReportDevice(String locationName, Double onlineRate, BigDecimal turnover,
                               Double faultRate, Integer cabinetCount, Long orders) {
    }

    /**
     * 点位坪效报表一行 = 一个站点名。
     *
     * @param cost    成本。**仅含场地分润**（{@code revenue × loc_contract.share_rate}），无 opex 表
     * @param payback 回本天数。恒为 0 = 不可得（缺单柜采购成本），前端渲染成「—」
     * @param roi     (revenue − cost) / cost；cost 为 0（该站点无生效合约）时返回 0 表示不可得
     */
    public record ReportLocation(String siteName, BigDecimal revenue, BigDecimal cost,
                                 Integer payback, BigDecimal roi, Long orders, String currency) {
    }

    /**
     * 财务报表一行 = 图表一个点（周期桶），所以表与图**不可能**对不上。
     *
     * @param share  应付场地分润（合约条款派生，是「应付」不是「已付」）
     * @param settle 已结算金额（{@code share_record.status='DONE'} 求和，当前真实为 0）
     * @param net    净收入 = gmv − share（无 opex 表，故 cost 只有 share 一项）
     */
    public record ReportFinance(String period, BigDecimal gmv, BigDecimal share,
                                BigDecimal settle, BigDecimal net, String currency) {
    }

    /** 大屏 KPI 一项。{@code trend} = 0 表示存量口径/无同期可比，前端渲染成「—」而不是假的 0.0%。 */
    public record ReportScreen(String metric, BigDecimal value, String unit, Double trend) {
    }

    /** 自定义报表长表一格（dim × metric × value）。指标是自选的，列数不定，宽表由前端透视。 */
    public record ReportCustom(String dim, String metric, BigDecimal value) {
    }

    // ————————————————————————————————————————————————————————————
    // 趋势 / 汇总
    // ————————————————————————————————————————————————————————————

    /**
     * 一个周期桶的汇总。字段是三张报表的并集，取用方按 kind 各取所需 ——
     * 拆成三个类型会导致三份桶切分逻辑，桶边界一旦不一致，图表与表格就对不上。
     */
    public record ReportTrendPoint(String bucket, Long orders, BigDecimal revenue, BigDecimal cost,
                                   BigDecimal share, BigDecimal net, Double onlineRate,
                                   Double faultRate, BigDecimal turnover) {
    }

    /** 汇总条一项。{@code format} 决定前端怎么渲染（MONEY/RATE/NUMBER），不在页面里按 label 猜。 */
    public record ReportSummaryItem(String label, BigDecimal value, String format) {
    }

    /** kind ∈ DEVICE|LOCATION|FINANCE；非法值落回 FINANCE（与 mock 同）。 */
    public record ReportTrend(String kind, String period, List<ReportTrendPoint> points,
                              List<ReportSummaryItem> summary, String currency) {
    }

    // ————————————————————————————————————————————————————————————
    // 实时大屏
    // ————————————————————————————————————————————————————————————

    public record ScreenBoardPoint(String hour, BigDecimal gmv, Long orders) {
    }

    public record ScreenRankRow(Integer rank, String siteNo, String siteName, BigDecimal gmv, Long orders) {
    }

    public record ScreenStatusSlice(String label, Long value) {
    }

    /**
     * 大屏一次取全。拆成四个请求会让看板分批到达、画面跳。
     *
     * <p><b>三条不变量（{@code ReportScreenBoardTest} 兜住）</b>：
     * ① {@code Σtoday.gmv} = KPI 今日 GMV、{@code Σtoday.orders} = KPI 今日订单；
     * ② {@code Σranking.gmv} = 今日 GMV；
     * ③ {@code ΣcabinetStatus.value} = 机柜总数（故三片必须互斥，见 impl 注释）。
     */
    public record ScreenBoard(String updatedAt, String currency, List<ReportScreen> kpis,
                              List<ScreenBoardPoint> today, List<ScreenRankRow> ranking,
                              List<ScreenStatusSlice> cabinetStatus) {
    }

    // ————————————————————————————————————————————————————————————
    // 自定义报表指标目录
    // ————————————————————————————————————————————————————————————

    /** 指标定义。与前端 {@code REPORT_METRICS} 同源同序 —— 少同步一处就会「勾了指标但表里没这列」。 */
    public record ReportMetricDef(String key, String label, String format) {
    }

    // ————————————————————————————————————————————————————————————
    // 消费者洞察
    // ————————————————————————————————————————————————————————————

    /**
     * 人群分层一行。
     *
     * <p><b>分层维度取「最近借出距今天数」而非「累计单量」</b>：按单量分层会让
     * {@code repeatRate}（段内复借用户占比）退化成 0/1/1，一眼假；按活跃度分层它才有信息量。
     */
    public record ConsumerSegment(String segmentNo, String segment, Long userCount,
                                  Double repeatRate, BigDecimal avgOrderValue, String currency) {
    }

    /**
     * 漏斗一环。
     *
     * @param rate     相对首环节的整体转化率 0..1
     * @param dropRate 相对上一环节的流失率 0..1；首环节为 0
     */
    public record ConsumerFunnelStage(String stage, Long users, Double rate, Double dropRate) {
    }

    /** 画像切片。{@code dim} 是分组键，同一 dim 内 {@code Σvalue = totalUsers}（按用户去重计数保证）。 */
    public record ConsumerProfileSlice(String dim, String dimLabel, String label, Long value, Double share) {
    }

    /** {@code totalUsers} = 分层表用户数合计 = 漏斗「成功借出」环节人数（同一批人，同一 tab 不能两个数）。 */
    public record ConsumerInsight(List<ConsumerFunnelStage> funnel,
                                  List<ConsumerProfileSlice> profiles, Long totalUsers) {
    }

    // ————————————————————————————————————————————————————————————
    // 站点卷积（域内共享事实，供 loc 域坪效读模型复用）
    // ————————————————————————————————————————————————————————————

    /**
     * 一个站点名在一个周期内的全部可得事实。**报表域与 loc 域坪效端点共用它** ——
     * 两处各算一套的话，「站点坪效」页与「点位报表」页会给出两个不同的营收。
     *
     * @param days      周期天数（算翻台率/日均用，避免下游再猜）
     * @param shareRate 生效合约分润率 0..1（无生效合约 = 0）
     */
    public record SiteRollup(String siteName, String siteNo, String sceneType,
                             Long orders, BigDecimal revenue, BigDecimal share,
                             Integer cabinetCount, Integer onlineCount, Integer faultCount,
                             Integer days, BigDecimal shareRate, String currency) {
    }

    // ── 站点坪效（自 loc.ext 迁入，2026-07-30）──
    // 它是**读模型**而非 loc 的领域概念：实现本就委托报表域算，
    // 放在 loc 里等于让业务域同步依赖报表域（ADR-017 §5.2：报表应订阅事件落宽表）。
    // 迁到这里后 loc → report 的依赖消失。

    /**
     * 站点坪效行，镜像前端 {@code SiteAnalysis}。
     *
     * <p>**不建物理表**：由 {@code loc_site ⋈ ord_order} 聚合而来（[db-design §3.4]）。
     */
    public record SiteAnalysis(String siteNo, String siteName, BigDecimal revenue, Integer orders,
                               BigDecimal turnover, Integer paybackDays, Integer cabinetCount,
                               String currency) {
    }

    // ── 工作台（自 OpsController 的 SeedData 骨架迁入，2026-08-04）──
    // 镜像前端 lib/types/dashboard.ts：统计卡 + 7 日趋势 + 待办中心 + 告警提醒条 + 站点排行。

    /** 趋势一日（前端 {@code trend[]} 元素）。 */
    public record DashboardTrendDay(String day, BigDecimal gmv, Long orders) {
    }

    /** 待办中心三数：待派工单 / 待审退款 / 待处理提现。 */
    public record DashboardTodos(Long pendingWorkOrders, Long pendingRefunds, Long pendingWithdrawals) {
    }

    /** 告警提醒条一行。{@code type} 取前端枚举 {@code OFFLINE | EXCEPTION | TIMEOUT}。 */
    public record DashboardAlert(String id, String type, String cabinetNo, String message, String href) {
    }

    /** 站点排行一行（按 7 日 GMV 降序）。 */
    public record DashboardRankItem(Integer rank, String siteName, BigDecimal gmv,
                                    Long orderCount, String currency) {
    }

    /** 工作台聚合，镜像前端 {@code DashboardStats}。{@code onlineRate} 是 0..1 小数。 */
    public record DashboardStats(BigDecimal gmvToday, Long ordersToday, Integer activeCabinets,
                                 Double onlineRate, Long openWorkOrders, String currency,
                                 List<DashboardTrendDay> trend, DashboardTodos todos,
                                 List<DashboardAlert> alerts, List<DashboardRankItem> rankings) {
    }
}
