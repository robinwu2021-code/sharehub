package ai.neargo.sharehub.api.platform.port;

import java.math.BigDecimal;

/**
 * 税率读取（platform.sys 实现，数据在「税率与发票设置」{@code sys_tax_setting}）。
 *
 * <p>财务按税前算分成时要把含税实收折回税前，税率只有这一个出处 —— 不在财务侧另存一份，
 * 否则运营改了税率，分成与发票会各用各的。
 */
public interface TaxRatePort {

    /**
     * 某国家当前适用的税率。
     *
     * @param country ISO alpha-2；找不到时回落到默认行 {@code '*'}
     * @return 找不到任何行时返回 {@code null}，由调用方决定怎么处理（通常是告警并按 0 处理）
     */
    TaxRate rateOf(String country);

    /**
     * @param ratePercent     百分数口径（5.00 = 5%），不是 0..1
     * @param includedInPrice 价格是否已含税；价外税时实收本身就是税前，不需要折算
     */
    record TaxRate(String country, BigDecimal ratePercent, boolean includedInPrice) {
    }
}
