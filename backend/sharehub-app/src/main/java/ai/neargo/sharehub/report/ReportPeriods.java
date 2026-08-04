package ai.neargo.sharehub.report;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 报表周期与桶切分 —— **与 ops-web {@code lib/types/report.ts REPORT_PERIODS} 逐字对齐**。
 *
 * <p><b>为什么周期定义必须两端一份且完全相同</b>：周期是报表的第一入参（不是可选筛选项）。
 * 桶数一旦不一致，同一屏里表格 12 行、折线 13 点，用户看到的就是「数据错了」——
 * 而实际错的只是桶边界。故此处的 days/bucketDays/标签格式都是前端常量的镜像，
 * 改这里必须同步改 {@code REPORT_PERIODS}（反之亦然）。
 *
 * <p><b>刻意的口径 ①：周期报表统计到「昨日」</b>（T+1 跑批口径），大屏才是「今日到现在」。
 * 否则同一页面上「今日」会同时是「整日」与「到点分时」两个数，必然被当成 bug
 * （见 {@code ops-web/lib/mock/db/report.ts} 文件头 ①）。
 *
 * <p><b>刻意的口径 ②：天数取桶宽整数倍</b>（91=13×7、360=12×30）。否则最后一个桶是残桶，
 * 图表上莫名矮一截，看着像掉量。
 *
 * <p><b>时区</b>：{@code ord_order.rent_start_at} 落库是 UTC ISO-8601 字符串（{@code ...Z}），
 * 因此「业务日」= UTC 日。全域统一，不做本地时区换算 —— 混用会让跨零点的订单在两张表里落到不同天。
 */
public final class ReportPeriods {

    private ReportPeriods() {
    }

    /** 周期枚举。{@code days} = 覆盖天数（含截止日），{@code bucketDays} = 一个桶的天数。 */
    public enum Period {
        LAST_7D(7, 1),
        LAST_30D(30, 1),
        LAST_13W(91, 7),
        LAST_12M(360, 30);

        private final int days;
        private final int bucketDays;

        Period(int days, int bucketDays) {
            this.days = days;
            this.bucketDays = bucketDays;
        }

        public int days() {
            return days;
        }

        public int bucketDays() {
            return bucketDays;
        }
    }

    /** 缺省周期。前端 {@code REPORT_PERIOD_DEFAULT} 同值。 */
    public static final Period DEFAULT = Period.LAST_30D;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 归一化周期：非法/缺省一律落回 {@link #DEFAULT}。
     *
     * <p>不抛 400 是刻意的 —— period 从 URL 查询参数来，用户手改地址栏或旧书签带着过期枚举时，
     * 报表页应该正常显示缺省周期，而不是整页报错。
     */
    public static Period normalize(String period) {
        if (period == null || period.isBlank()) {
            return DEFAULT;
        }
        for (Period p : Period.values()) {
            if (p.name().equalsIgnoreCase(period.trim())) {
                return p;
            }
        }
        return DEFAULT;
    }

    /** 一个周期桶：标签 + 它覆盖的具体日期（升序，含端点）。 */
    public record Bucket(String label, List<LocalDate> days) {
    }

    /**
     * 切桶。结束于 {@code today - 1}（昨日），起点 = 结束 - days + 1。
     *
     * @param today 基准「今日」，由调用方注入（测试可固定，生产传 {@code LocalDate.now(UTC)}）
     */
    public static List<Bucket> buckets(Period p, LocalDate today) {
        LocalDate end = today.minusDays(1);
        LocalDate start = end.minusDays(p.days() - 1L);
        List<Bucket> out = new ArrayList<>();
        for (LocalDate s = start; !s.isAfter(end); s = s.plusDays(p.bucketDays())) {
            List<LocalDate> days = new ArrayList<>();
            for (LocalDate d = s; d.isBefore(s.plusDays(p.bucketDays())) && !d.isAfter(end); d = d.plusDays(1)) {
                days.add(d);
            }
            out.add(new Bucket(label(p, days), days));
        }
        return out;
    }

    /** 桶标签：日→{@code 2026-07-10}，周→{@code 07-04~07-10}，月→{@code 2026-07}（与前端 mock 逐字一致）。 */
    private static String label(Period p, List<LocalDate> days) {
        LocalDate first = days.get(0);
        LocalDate last = days.get(days.size() - 1);
        if (p.bucketDays() == 1) {
            return first.format(DAY);
        }
        if (p.bucketDays() >= 30) {
            return first.format(DAY).substring(0, 7);
        }
        return first.format(DAY).substring(5) + "~" + last.format(DAY).substring(5);
    }

    /** 周期首日（含）。取桶序列的第一天，保证与切桶口径同源，不另算一遍。 */
    public static LocalDate from(Period p, LocalDate today) {
        return today.minusDays(p.days());
    }

    /** 周期末日（含）= 昨日。 */
    public static LocalDate to(LocalDate today) {
        return today.minusDays(1);
    }

    public static String fmt(LocalDate d) {
        return d.format(DAY);
    }
}
