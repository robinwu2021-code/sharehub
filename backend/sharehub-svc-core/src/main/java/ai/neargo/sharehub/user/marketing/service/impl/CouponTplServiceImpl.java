package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CouponTplVO;
import ai.neargo.sharehub.user.marketing.entity.CouponTpl;
import ai.neargo.sharehub.user.marketing.mapper.CouponTplMapper;
import ai.neargo.sharehub.user.marketing.service.CouponTplService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 券模板实现。 */
@Service
public class CouponTplServiceImpl extends AbstractCrudService<CouponTpl, CouponTplVO> implements CouponTplService {

    public CouponTplServiceImpl(CouponTplMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "tpl_no";
    }

    @Override
    protected String keyOf(CouponTpl e) {
        return e.getTplNo();
    }

    @Override
    protected void setKey(CouponTpl e, String no) {
        e.setTplNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.COUPON;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"tpl_no", "name"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"type", "status", "currency"};
    }

    @Override
    protected void beforeCreate(CouponTpl e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("ACTIVE");
        if (e.getCurrency() == null || e.getCurrency().isBlank()) e.setCurrency("AED"); // ADR-009 默认币种
        if (e.getValue() == null) e.setValue(BigDecimal.ZERO);
        if (e.getThreshold() == null) e.setThreshold(BigDecimal.ZERO);
        if (e.getStock() == null) e.setStock(0);
        e.setIssued(0); // 已发放数由发券动作维护，不接受建单时传入
    }

    @Override
    protected void beforeUpdate(CouponTpl e, CouponTpl current) {
        e.setIssued(current.getIssued()); // 同上：已发放数不可被编辑覆盖
    }

    @Override
    protected CouponTplVO toVO(CouponTpl e) {
        return new CouponTplVO(e.getTplNo(), e.getName(), e.getType(),
                e.getValue(), e.getThreshold(), e.getCurrency(),
                e.getStock(), e.getIssued(), e.getStatus(),
                // 2026-09-24：此前这里硬写 null，附注「本表无归档列」——**那句是过期的**。
                // V22__archivable.sql 给 coupon_tpl 加了 archived_at，归档/恢复端点也在用。
                // 于是券归档了、库里也写了，运营端却永远看不出来：列表上它和在用的券长得一样。
                e.getArchivedAt() == null ? null : e.getArchivedAt().toString());
    }
}
