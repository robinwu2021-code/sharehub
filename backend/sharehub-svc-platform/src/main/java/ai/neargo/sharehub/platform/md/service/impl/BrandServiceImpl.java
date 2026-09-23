package ai.neargo.sharehub.platform.md.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos.BrandEntry;
import ai.neargo.sharehub.platform.md.entity.MdBrand;
import ai.neargo.sharehub.platform.md.mapper.MdBrandMapper;
import ai.neargo.sharehub.platform.md.service.BrandService;
import org.springframework.stereotype.Service;

/** 品牌字典实现。行为全部来自 {@link AbstractCrudService}，本类只声明键/搜索/筛选/转 VO。 */
@Service
public class BrandServiceImpl extends AbstractCrudService<MdBrand, BrandEntry> implements BrandService {

    public BrandServiceImpl(MdBrandMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "brand_no";
    }

    @Override
    protected String keyOf(MdBrand e) {
        return e.getBrandNo();
    }

    @Override
    protected void setKey(MdBrand e, String no) {
        e.setBrandNo(no);
    }

    /** 与 bank 不同：品牌没有对外有语义的自然键，走前缀取号。 */
    @Override
    protected String keyPrefix() {
        return "BR";
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"brand_no", "name", "name_en"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"status"};
    }

    @Override
    protected String orderColumn() {
        return "brand_no";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected BrandEntry toVO(MdBrand e) {
        return new BrandEntry(e.getBrandNo(), e.getName(), e.getNameEn(), e.getNameAr(),
                e.getLogoUrl(), e.getSupportPhone(), e.getMarketCode(), e.getStatus(),
                e.getArchivedAt() == null ? null : e.getArchivedAt().toString());
    }
}
