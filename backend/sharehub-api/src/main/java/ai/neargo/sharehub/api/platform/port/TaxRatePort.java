package ai.neargo.sharehub.api.platform.port;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 开票主体（运营方原文 C01 未确认 → 做成配置，2026-10-29）：取该国的税务设置行，无则取 {@code *} 默认行。
     * 换主体（Jun Sheng → Junverse）= 改这一行；每张发票开出时把主体整份快照进去，历史发票不受影响。
     *
     * @return 连默认行都没有时返回 {@code null}
     */
    Issuer issuerOf(String country);

    /** 开票主体快照；{@link #missing()} 非空时不得开票。 */
    record Issuer(String country, String legalName, String address, String trn, String title,
                  BigDecimal ratePercent, boolean includedInPrice) {
        /** 缺哪些必填项（法定名称、地址、TRN）—— 开票前检查，缺了就拒绝并如实告诉运营。 */
        public List<String> missing() {
            List<String> m = new ArrayList<>();
            if (legalName == null || legalName.isBlank()) m.add("issuerLegalName");
            if (address == null || address.isBlank()) m.add("issuerAddress");
            if (trn == null || trn.isBlank()) m.add("trn");
            return m;
        }
    }
}
