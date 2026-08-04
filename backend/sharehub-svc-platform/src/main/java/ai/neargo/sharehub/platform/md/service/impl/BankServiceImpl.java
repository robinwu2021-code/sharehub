package ai.neargo.sharehub.platform.md.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos.BankEntry;
import ai.neargo.sharehub.platform.md.entity.MdBank;
import ai.neargo.sharehub.platform.md.mapper.MdBankMapper;
import ai.neargo.sharehub.platform.md.service.BankService;
import org.springframework.stereotype.Service;

/**
 * 银行字典实现 —— **字典类实体的金标准样板**。
 * 全部行为来自 {@link AbstractCrudService}，本类只声明「键是哪列、怎么搜、能按什么筛、怎么转 VO」。
 */
@Service
public class BankServiceImpl extends AbstractCrudService<MdBank, BankEntry> implements BankService {

    public BankServiceImpl(MdBankMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "bank_code";
    }

    @Override
    protected String keyOf(MdBank e) {
        return e.getBankCode();
    }

    @Override
    protected void setKey(MdBank e, String no) {
        e.setBankCode(no);
    }

    // keyPrefix() 不覆盖 → null → 自然键，新建必须显式给 bankCode

    @Override
    protected String[] keywordColumns() {
        return new String[]{"bank_code", "bank_name", "bank_name_en"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"country", "currency", "status"};
    }

    @Override
    protected String orderColumn() {
        return "bank_code";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected BankEntry toVO(MdBank e) {
        return new BankEntry(e.getBankCode(), e.getBankName(), e.getBankNameEn(),
                e.getCountry(), e.getCurrency(), e.getSwiftPrefix(), e.getIbanLength(), e.getStatus());
    }
}
