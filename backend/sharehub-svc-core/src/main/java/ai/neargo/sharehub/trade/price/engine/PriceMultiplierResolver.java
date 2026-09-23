package ai.neargo.sharehub.trade.price.engine;

import ai.neargo.sharehub.trade.price.entity.PriceSchedule;
import ai.neargo.sharehub.trade.price.mapper.PriceScheduleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/**
 * 时段 / 活动倍率（{@code price_schedule}）—— 修「配置了不生效」。
 *
 * <p>2026-09-23 之前：{@code ChargeChain.charge(gross, multiplier, …)} 早就接受倍率参数，
 * 但**没有任何调用方算出倍率传进来**（下单处写死 {@code null}）。
 * 于是「活动/时段价」菜单能存能改，订单一分钱都不多收 —— 又一个「有能力没消费方」。
 *
 * <h3>按订单开始时刻取值，不按分钟分段（ADR-028 §四）</h3>
 * 跨时段的长单只按开始时刻定一个倍率，并随快照定格。
 * 分段计价的争议成本远高于它带来的精度 —— 用户看不懂「为什么这一单有两个费率」。
 *
 * <h3>多条命中取最大，不相乘</h3>
 * 相乘会让「高峰 1.5 × 节假日 1.5 = 2.25」这种叠加悄悄出现，运营配的时候不会意识到。
 * 取最大是可预期的，且任何一条都能单独解释给用户听。
 *
 * <h3>只读结构化列</h3>
 * {@code period} 是展示串（见 {@link PriceSchedule} 的字段注释），本类一概不读它。
 * 存量行没有结构化值 → 不命中 → 不加倍。**宁可不加倍，也不猜错倍率去多收钱。**
 */
@Component
public class PriceMultiplierResolver {

    private final PriceScheduleMapper schedules;

    public PriceMultiplierResolver(PriceScheduleMapper schedules) {
        this.schedules = schedules;
    }

    /**
     * @param regionId 站点所在区域；{@code null} 时只匹配「全域」的行
     * @param at       订单开始时刻
     * @return 倍率；无命中返回 {@code null}（调用方据此不加倍，而不是乘以 1）
     */
    public BigDecimal resolve(String regionId, LocalDateTime at) {
        if (at == null) return null;
        List<PriceSchedule> rows = schedules.selectList(
                new LambdaQueryWrapper<PriceSchedule>().eq(PriceSchedule::getActive, 1));
        BigDecimal best = null;
        for (PriceSchedule r : rows) {
            if (!regionMatches(r.getRegionId(), regionId)) continue;
            if (!hits(r, at)) continue;
            BigDecimal m = r.getMultiplier();
            if (m == null || m.signum() <= 0) continue;
            if (best == null || m.compareTo(best) > 0) best = m;
        }
        return best;
    }

    /** 行上 region 为空 = 全域。 */
    private static boolean regionMatches(String rowRegion, String queryRegion) {
        if (rowRegion == null || rowRegion.isBlank()) return true;
        return rowRegion.equals(queryRegion);
    }

    /**
     * 星期与时刻是否命中。
     *
     * <p>{@code expr} 型（节假日）**一律不命中**：判它需要节假日日历，本期没有。
     * 直接返回 false 而不是「当成全天命中」—— 后者会让所有节假日规则对全年生效。
     */
    static boolean hits(PriceSchedule r, LocalDateTime at) {
        boolean hasExpr = r.getExpr() != null && !r.getExpr().isBlank();
        boolean hasRange = notBlank(r.getDays()) || notBlank(r.getTimeFrom()) || notBlank(r.getTimeTo());
        if (hasExpr && !hasRange) return false;
        if (!hasRange) return false;   // 结构化列全空（存量行）→ 不命中，见类注释

        if (notBlank(r.getDays()) && !dayHits(r.getDays(), at)) return false;
        return timeHits(r.getTimeFrom(), r.getTimeTo(), at.toLocalTime());
    }

    /** {@code 1}=周一 … {@code 7}=周日，与 {@link java.time.DayOfWeek#getValue()} 同序。 */
    private static boolean dayHits(String csv, LocalDateTime at) {
        int today = at.getDayOfWeek().getValue();
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .anyMatch(s -> {
                    try {
                        return Integer.parseInt(s) == today;
                    } catch (NumberFormatException e) {
                        return false;      // 脏值只让这一条不命中，不炸整个取价
                    }
                });
    }

    /**
     * 时刻区间；两端同时为空 = 全天。
     *
     * <p><b>跨零点是合法的</b>（{@code 22:00-06:00}）：此时判「在 from 之后 或 在 to 之前」。
     * 只填一端的行视为不命中 —— 那是没配完的配置，按全天生效会悄悄多收一整天的钱。
     */
    private static boolean timeHits(String from, String to, LocalTime now) {
        boolean hasFrom = notBlank(from), hasTo = notBlank(to);
        if (!hasFrom && !hasTo) return true;
        if (hasFrom != hasTo) return false;
        LocalTime f = parse(from), t = parse(to);
        if (f == null || t == null) return false;
        if (f.equals(t)) return true;                       // 首尾相同 = 全天
        return f.isBefore(t) ? !now.isBefore(f) && now.isBefore(t)
                : !now.isBefore(f) || now.isBefore(t);      // 跨零点
    }

    private static LocalTime parse(String hhmm) {
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
