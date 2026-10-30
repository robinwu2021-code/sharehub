package ai.neargo.sharehub.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 分成基数口径（{@code share_record.base_mode}，V155；需求方案 D9「暂定税前，将来可调整」）。
 *
 * <p>口径由系统参数 {@code finance.share.base_mode} 决定，**每笔明细快照当时的口径**：
 * 改参数只影响之后的订单，历史分成不重算。
 */
public enum ShareBaseMode {

    /** 实收折回税前：实收 ÷ (1 + 税率)。价外税时实收本身就是税前，不折算。 */
    PRE_TAX,
    /** 含税实收直接作基数（V155 之前的口径）。 */
    GROSS;

    /** 参数值写坏时回落到税前 —— 与需求方案的默认口径一致，宁可少分也不多分。 */
    public static ShareBaseMode parse(String value) {
        if (value != null) {
            for (ShareBaseMode m : values()) {
                if (m.name().equalsIgnoreCase(value.trim())) return m;
            }
        }
        return PRE_TAX;
    }

    /**
     * 算基数。
     *
     * @param gross           本单实收（含税价时含税）
     * @param ratePercent     税率百分数（5.00 = 5%）；null 视为 0
     * @param includedInPrice 价格是否含税
     * @return 基数，四舍五入到分。逐笔先舍入再汇总，结算单金额才等于明细之和
     */
    public Base baseOf(BigDecimal gross, BigDecimal ratePercent, boolean includedInPrice) {
        BigDecimal rate = ratePercent == null ? BigDecimal.ZERO : ratePercent;
        if (this == GROSS || !includedInPrice || rate.signum() == 0) {
            // 没有发生折算：税率记 0（与 V155 列注释一致），看明细的人不会误以为扣过税
            return new Base(this, BigDecimal.ZERO, gross.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal divisor = BigDecimal.ONE.add(rate.movePointLeft(2));
        return new Base(this, rate, gross.divide(divisor, 2, RoundingMode.HALF_UP));
    }

    /** 一笔订单的分成基数快照。 */
    public record Base(ShareBaseMode mode, BigDecimal taxRate, BigDecimal amount) {
    }
}
