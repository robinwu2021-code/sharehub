package ai.neargo.sharehub.platform.sys.port;

import ai.neargo.sharehub.api.platform.port.TaxRatePort;
import ai.neargo.sharehub.platform.sys.entity.SysTaxSetting;
import ai.neargo.sharehub.platform.sys.mapper.SysTaxSettingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** {@link TaxRatePort} 的本地实现。每次现查：运营改了税率，下一笔分成就按新税率算。 */
@Component
public class LocalTaxRate implements TaxRatePort {

    private static final String TENANT = "MAIN";
    private static final String DEFAULT_ROW = "*";

    private final SysTaxSettingMapper settings;

    public LocalTaxRate(SysTaxSettingMapper settings) {
        this.settings = settings;
    }

    @Override
    public TaxRate rateOf(String country) {
        SysTaxSetting s = country == null || country.isBlank() ? null : find(country.trim().toUpperCase());
        if (s == null) s = find(DEFAULT_ROW);
        if (s == null) return null;
        BigDecimal rate = s.getRatePercent() == null ? BigDecimal.ZERO : s.getRatePercent();
        return new TaxRate(s.getCountry(), rate, s.getIncludedInPrice() == null || s.getIncludedInPrice() != 0);
    }

    @Override
    public Issuer issuerOf(String country) {
        SysTaxSetting s = find(country);
        if (s == null) return null;
        BigDecimal rate = s.getRatePercent() == null ? BigDecimal.ZERO : s.getRatePercent();
        return new Issuer(s.getCountry(), s.getIssuerLegalName(), s.getIssuerAddress(), s.getTrn(), s.getInvoiceTitle(),
                rate, s.getIncludedInPrice() == null || s.getIncludedInPrice() != 0);
    }

    private SysTaxSetting find(String country) {
        return settings.selectOne(new LambdaQueryWrapper<SysTaxSetting>()
                .eq(SysTaxSetting::getTenantId, TENANT).eq(SysTaxSetting::getCountry, country).last("limit 1"));
    }
}
