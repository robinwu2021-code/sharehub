package ai.neargo.sharehub.platform.md.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.MarketCountry;
import ai.neargo.sharehub.platform.md.entity.MdMarketCountry;
import ai.neargo.sharehub.platform.md.entity.MdRegion;
import ai.neargo.sharehub.platform.md.mapper.MdMarketCountryMapper;
import ai.neargo.sharehub.platform.md.mapper.MdRegionMapper;
import ai.neargo.sharehub.platform.md.service.MarketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 多国家市场实现。
 *
 * <p><b>{@code cityCount} 是聚合不是列</b>（[db-design §1.4]）：本表没有该列，
 * 出参时按 {@code md_region.parent_id = countryCode} 实时数子节点。
 * 老 DDL 里那一列（人工维护）与本口径冲突，已在交付报告列为待清理项。
 * 逐行一次 count 在字典规模（几十个国家）下可接受；若将来国家数上百，改成一次 group by 汇总。
 */
@Service
public class MarketServiceImpl extends AbstractCrudService<MdMarketCountry, MarketCountry> implements MarketService {

    private final MdRegionMapper regionMapper;

    public MarketServiceImpl(MdMarketCountryMapper mapper, MdRegionMapper regionMapper) {
        super(mapper);
        this.regionMapper = regionMapper;
    }

    @Override
    protected String keyColumn() {
        return "country_code";
    }

    @Override
    protected String keyOf(MdMarketCountry e) {
        return e.getCountryCode();
    }

    @Override
    protected void setKey(MdMarketCountry e, String no) {
        e.setCountryCode(no);
    }

    // keyPrefix() 不覆盖 → 自然键 ISO alpha-2，新建必须显式给 countryCode

    @Override
    protected String[] keywordColumns() {
        return new String[]{"country_code", "name", "name_en"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"status", "currency"};
    }

    @Override
    protected String orderColumn() {
        return "country_code";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(MdMarketCountry e) {
        if (e.getStatus() == null) e.setStatus("PLANNED");
    }

    @Override
    protected MarketCountry toVO(MdMarketCountry e) {
        return new MarketCountry(e.getCountryCode(), e.getName(), e.getCurrency(), e.getTimezone(),
                e.getCompliance(), cityCount(e.getCountryCode()), e.getStatus());
    }

    /** 已开城市数 = 挂在该国家下的地区节点数（明细以 md_region 为准）。 */
    private Integer cityCount(String countryCode) {
        if (countryCode == null) return 0;
        Long n = regionMapper.selectCount(new LambdaQueryWrapper<MdRegion>()
                .eq(MdRegion::getParentId, countryCode));
        return n == null ? 0 : n.intValue();
    }
}
