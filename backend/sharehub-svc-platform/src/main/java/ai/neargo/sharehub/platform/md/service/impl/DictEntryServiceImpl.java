package ai.neargo.sharehub.platform.md.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.DictEntry;
import ai.neargo.sharehub.platform.md.entity.DictItem;
import ai.neargo.sharehub.platform.md.mapper.DictItemMapper;
import ai.neargo.sharehub.platform.md.service.DictEntryService;
import org.springframework.stereotype.Service;

/** 参数字典实现：全部行为来自 {@link AbstractCrudService}，本类只声明键/搜索/筛选/转 VO。 */
@Service
public class DictEntryServiceImpl extends AbstractCrudService<DictItem, DictEntry> implements DictEntryService {

    public DictEntryServiceImpl(DictItemMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "dict_no";
    }

    @Override
    protected String keyOf(DictItem e) {
        return e.getDictNo();
    }

    @Override
    protected void setKey(DictItem e, String no) {
        e.setDictNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.DICT; // DC —— 取号走基类 max+1，禁止「前缀 + 数组长度」
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"dict_no", "group_code", "code", "label"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"groupCode", "enabled"};
    }

    @Override
    protected String orderColumn() {
        return "sort";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(DictItem e) {
        if (e.getSort() == null) e.setSort(0);
        if (e.getEnabled() == null) e.setEnabled(1);
    }

    @Override
    protected DictEntry toVO(DictItem e) {
        return new DictEntry(e.getDictNo(), e.getGroupCode(), e.getCode(), e.getLabel(),
                e.getSort(), e.getEnabled() != null && e.getEnabled() == 1);
    }
}
