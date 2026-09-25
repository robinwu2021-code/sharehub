package ai.neargo.sharehub.trade.price.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 取价链的后两层：**分时倍率 → 券抵扣 → 免单减免**（[TDD-核心业务逻辑 §1.1/§1.2]）。
 *
 * <p>{@link PriceEngine} 负责第 ①② 层（方案展开 + 逐项计价 + 单项封顶），
 * 本类负责其后的三步。分开是因为它们的**输入时机不同**：
 * 计价规格在<b>下单时快照</b>，而倍率/券/免单要按<b>归还时刻</b>重算
 * （时段价本就依赖使用时段，会员与券在结算时才确定）。
 *
 * <h3>两条顺序约束（写错就是算错钱，且不会报错）</h3>
 * <ol>
 *   <li><b>券在封顶之后抵扣，不参与封顶计算</b> —— 反过来的话券会被封顶「吃掉」：
 *       应收 40 封顶 30、券 10，正确是 30-10=20；若先抵扣再封顶则是 min(40-10,30)=30，用户白拿券。</li>
 *   <li><b>免费单要先算出金额再减免</b> —— {@code fee=0} 且 {@code waivedAmount=本应收}。
 *       直接置 0 会让「本月减免总额」这个统计口径永远拿不到数。</li>
 * </ol>
 *
 * <p><b>纯函数，不碰 DB</b>（TDD 明确要求）：券和免单的读取在调用方做，
 * 这样整条链可以被单测穷举，不需要起 Spring 上下文。
 */
@Component
public class ChargeChain {

    private static final int MONEY_SCALE = 2;

    /**
     * @param gross      {@link PriceEngine} 算出的应收（已含单项封顶）
     * @param multiplier 分时/节假日倍率；null 或 ≤0 视为 1
     * @param coupon     券的抵扣规格；null 表示无券。**传规格而不是算好的金额** ——
     *                   门槛与折扣率都要按「封顶后的应收」算，而那个数只有本方法知道
     * @param capTotal   总封顶；null 表示不封顶。**在倍率之后、券之前应用**
     * @param freeReason 免单原因；非空即免单
     */
    public Charged charge(BigDecimal gross, BigDecimal multiplier, Coupon coupon,
                          BigDecimal capTotal, String freeReason) {
        BigDecimal amount = nz(gross);

        // ③ 分时倍率
        if (multiplier != null && multiplier.signum() > 0) {
            amount = amount.multiply(multiplier);
        }

        // 总封顶 —— 必须在券之前，见类注释约束 1
        boolean buyout = false;
        if (capTotal != null && amount.compareTo(capTotal) >= 0) {
            amount = capTotal;
            // fee 触顶 = 买断（[db-design §9A.1]）：充电宝转 SOLD，订单走买断流程
            buyout = true;
        }
        amount = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal beforeDiscount = amount;

        // ④ 券抵扣（封顶之后）。抵扣不产生负数应收 —— 券面额大于应收时按应收抵。
        BigDecimal couponUsed = BigDecimal.ZERO;
        if (coupon != null) {
            BigDecimal off = coupon.offAgainst(amount);
            if (off.signum() > 0) {
                couponUsed = off.min(amount);
                amount = amount.subtract(couponUsed);
            }
        }

        // 免单：先算后减免
        BigDecimal waived = BigDecimal.ZERO;
        if (freeReason != null && !freeReason.isBlank()) {
            waived = amount;              // 记录「本应收多少」，供减免统计
            amount = BigDecimal.ZERO;
        }

        return new Charged(beforeDiscount, couponUsed, waived,
                amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP), buyout);
    }

    /**
     * @param beforeDiscount 封顶后、抵扣前的应收（券与免单的计算基数）
     * @param couponUsed     实际抵扣的券金额（可能小于券面额）
     * @param waivedAmount   免单减免额；非 0 时 {@code payable} 必为 0
     * @param payable        最终应付
     * @param buyout         是否触发买断（应收触及总封顶）
     */
    public record Charged(BigDecimal beforeDiscount, BigDecimal couponUsed,
                          BigDecimal waivedAmount, BigDecimal payable, boolean buyout) {
    }

    /**
     * 券的抵扣规格（纯数据，不含券号与属主 —— 那些是调用方校验完才轮到这里）。
     *
     * @param type      CUT（满减）/ DISCOUNT（折扣）；其它值一律按不抵扣处理
     * @param value     CUT=减免金额；DISCOUNT=折扣率（0..1）
     * @param threshold 使用门槛；null/0 表示无门槛
     */
    public record Coupon(String type, BigDecimal value, BigDecimal threshold) {

        /**
         * 按基数算抵扣额。{@code base} 是**封顶后、抵扣前**的应收。
         *
         * <p>门槛用同一个基数判：用倍率前的原价判门槛，会让高峰期一张「满 10 减 3」
         * 在应收 12 时用不了；用抵扣后的余额判，则门槛永远差那一点。两种都不报错。
         *
         * <p>{@code DISCOUNT} 的 {@code value} 是**折扣率**（0.8 = 八折 = 付 80%），
         * 所以抵扣额是 {@code base × (1 - value)}，不是 {@code base × value} ——
         * 写反了是「八折变两折」，金额照样算得出来，只是少收了 60%。
         */
        BigDecimal offAgainst(BigDecimal base) {
            if (value == null || value.signum() <= 0) return BigDecimal.ZERO;
            if (threshold != null && base.compareTo(threshold) < 0) return BigDecimal.ZERO;
            if ("CUT".equals(type)) return value;
            if ("DISCOUNT".equals(type)) {
                if (value.compareTo(BigDecimal.ONE) >= 0) return BigDecimal.ZERO;   // 一折不打，不是倒贴
                return base.multiply(BigDecimal.ONE.subtract(value))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
