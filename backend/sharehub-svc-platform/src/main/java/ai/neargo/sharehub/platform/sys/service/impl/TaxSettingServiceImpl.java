package ai.neargo.sharehub.platform.sys.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.sys.SysCtx;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.TaxSetting;
import ai.neargo.sharehub.platform.sys.entity.SysTaxSetting;
import ai.neargo.sharehub.platform.sys.mapper.SysTaxSettingMapper;
import ai.neargo.sharehub.platform.sys.service.TaxSettingService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 税率与发票设置实现。
 *
 * <p>与 {@code SysParamServiceImpl} 同理覆盖 {@code selectByKey}：
 * UK 是 {@code (tenant_id, country)}，单列查会串租户。
 */
@Service
public class TaxSettingServiceImpl extends AbstractCrudService<SysTaxSetting, TaxSetting> implements TaxSettingService {

    public TaxSettingServiceImpl(SysTaxSettingMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "country";
    }

    @Override
    protected String keyOf(SysTaxSetting e) {
        return e.getCountry();
    }

    @Override
    protected void setKey(SysTaxSetting e, String no) {
        e.setCountry(no);
    }

    /** 复合 UK {@code (tenant_id, country)}。 */
    @Override
    protected SysTaxSetting selectByKey(String no) {
        if (no == null || no.isBlank()) return null;
        return mapper.selectOne(new QueryWrapper<SysTaxSetting>()
                .eq("country", no)
                .eq("tenant_id", SysCtx.tenantId())
                .last("limit 1"));
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"country", "country_name", "tax_name", "trn"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"country"};
    }

    @Override
    protected String orderColumn() {
        return "country";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(SysTaxSetting e) {
        if (e.getRatePercent() == null) e.setRatePercent(BigDecimal.ZERO);
        if (e.getIncludedInPrice() == null) e.setIncludedInPrice(1);
    }

    @Override
    protected TaxSetting toVO(SysTaxSetting e) {
        return new TaxSetting(e.getCountry(), e.getCountryName(), e.getTaxName(), e.getRatePercent(),
                e.getTrn(), e.getInvoiceTitle(), SysCtx.bool(e.getIncludedInPrice()), e.getEffectiveFrom());
    }
}
