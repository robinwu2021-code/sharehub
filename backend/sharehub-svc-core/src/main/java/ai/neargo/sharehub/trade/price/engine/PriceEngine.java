package ai.neargo.sharehub.trade.price.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 计价引擎：**逐费用项算完求和**（ADR-018 · 设计v3-数据库变更 §三）。
 *
 * <p>取代原先写死在 {@code RentOrderServiceImpl} 里的
 * {@code Math.min(30, ceil(min/30) * 3)} —— 那版连 {@code price_plan} 都没读，
 * 改价要发版，更装不下充电桩的 kWh 阶梯电价。
 *
 * <h3>算法（每个费用项独立走一遍）</h3>
 * <ol>
 *   <li><b>扣免费额度</b>：{@code chargeQty = max(0, qty - freeQty)}；</li>
 *   <li><b>换算步数</b>：{@code units = round(chargeQty / unitQty)}，
 *       舍入方式由 {@code rounding} <b>显式</b>指定；</li>
 *   <li><b>套阶梯</b>：按步数落在哪几段，<b>分段累加</b>而非整体套单一档位；</li>
 *   <li><b>封顶</b>：{@code min(amount, capDaily)}。</li>
 * </ol>
 *
 * <p><b>阶梯是分段累加，不是「落在哪档就整体按哪档」。</b>
 * 两种口径算出的钱差很多：0–100 度 0.5 元、100 度以上 0.8 元，充 150 度时
 * 分段累加 = 100×0.5 + 50×0.8 = 90，整体套档 = 150×0.8 = 120。
 * 电价、阶梯水电、运营商流量包用的都是分段累加，本引擎与之一致。
 *
 * <p><b>全程 BigDecimal</b>：金额不用浮点。原实现用 {@code double} 算钱，
 * 在封顶与阶梯边界上会出现 {@code 29.999999999999996} 这类值。
 *
 * <p><b>不在这里做的事</b>：券抵扣与免单减免。它们在封顶之后、按订单整体处理
 * （[TDD-核心业务逻辑] 四层取价链：快照 plan → 逐项计价 → 券在封顶后抵扣 → 免费单先算后减免），
 * 放进引擎会让「单项封顶」和「整单减免」的先后顺序变得含糊。
 */
@Component
public class PriceEngine {

    /** 金额精度：2 位小数（[db-design §1.5] {@code DECIMAL(18,2)}）。 */
    private static final int MONEY_SCALE = 2;

    /**
     * 计价。
     *
     * @param items    费用项规格，来自方案展开或订单快照
     * @param quantities 各计量维度的实际用量，如 {@code {MINUTE: 90}} 或 {@code {KWH: 23.5, MINUTE: 10}}
     * @param currency 币种
     */
    public PriceResult price(List<PriceItemSpec> items, Map<String, BigDecimal> quantities,
                             String currency) {
        List<PriceResult.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (PriceItemSpec item : items) {
            BigDecimal qty = quantities.getOrDefault(item.metering(), BigDecimal.ZERO);

            // ① 扣免费额度
            BigDecimal chargeQty = qty.subtract(nz(item.freeQty())).max(BigDecimal.ZERO);
            if (chargeQty.signum() == 0) {
                lines.add(new PriceResult.Line(item.itemType(), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, null));
                continue;
            }

            // ② 换算步数（显式舍入）
            BigDecimal unitQty = nz(item.unitQty());
            if (unitQty.signum() <= 0) unitQty = BigDecimal.ONE;
            BigDecimal units = chargeQty.divide(unitQty, 6, RoundingMode.HALF_UP)
                    .setScale(0, roundingOf(item.rounding()));

            // ③ 套阶梯（分段累加）
            BigDecimal amount = applyLadders(units, item.ladders());

            // ④ 封顶
            BigDecimal cappedBy = null;
            if (item.capDaily() != null && amount.compareTo(item.capDaily()) > 0) {
                cappedBy = item.capDaily();
                amount = item.capDaily();
            }

            amount = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            lines.add(new PriceResult.Line(item.itemType(), chargeQty, units, amount, cappedBy));
            total = total.add(amount);
        }

        return new PriceResult(lines, total.setScale(MONEY_SCALE, RoundingMode.HALF_UP), currency);
    }

    /**
     * 分段累加。区间语义：{@code [fromQty, toQty)}，{@code toQty=null} 为无上限。
     *
     * <p>阶梯之间的缝隙与重叠由**写入时**校验（整组 PUT 覆盖，见 api 设计 §二）；
     * 这里按 seq 顺序推进，不重复做区间合法性检查 —— 引擎每单都跑，
     * 把校验放在这里等于为每一笔订单付一次配置校验的代价。
     */
    private BigDecimal applyLadders(BigDecimal units, List<PriceItemSpec.PriceLadderSpec> ladders) {
        if (ladders == null || ladders.isEmpty()) return BigDecimal.ZERO;

        List<PriceItemSpec.PriceLadderSpec> sorted = new ArrayList<>(ladders);
        sorted.sort(Comparator.comparing(l -> nz(l.fromQty())));

        BigDecimal amount = BigDecimal.ZERO;
        for (PriceItemSpec.PriceLadderSpec l : sorted) {
            BigDecimal from = nz(l.fromQty());
            if (units.compareTo(from) <= 0) break;

            BigDecimal upper = l.toQty() == null ? units : l.toQty().min(units);
            BigDecimal seg = upper.subtract(from);
            if (seg.signum() > 0) {
                amount = amount.add(seg.multiply(nz(l.unitPrice())));
            }
        }
        return amount;
    }

    private static RoundingMode roundingOf(String rounding) {
        if (rounding == null) return RoundingMode.CEILING;
        return switch (rounding) {
            case "FLOOR" -> RoundingMode.FLOOR;
            case "HALF_UP" -> RoundingMode.HALF_UP;
            case "CEIL", "CEILING" -> RoundingMode.CEILING;
            // 不认识的口径**必须炸**而不是默默取默认：静默按错误口径收钱，
            // 差异会一直累积到对账时才暴露，届时已无法逐单追溯。
            default -> throw new IllegalArgumentException("未知的舍入口径: " + rounding);
        };
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
