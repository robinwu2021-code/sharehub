package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.api.platform.port.TaxRatePort;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.common.time.BizClock;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.trade.order.entity.OrdTaxInvoice;
import ai.neargo.sharehub.trade.order.mapper.OrdTaxInvoiceMapper;
import ai.neargo.sharehub.trade.order.service.TaxInvoiceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * C 端租赁税务发票（V181）。开票失败绝不向上抛 —— 收款、退款审批已经成立，票据是它们的副产品。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxInvoiceServiceImpl implements TaxInvoiceService {

    static final String KEY_ENABLED = "invoice.b2c.enabled";
    static final String KEY_TRIGGER = "invoice.b2c.trigger";
    static final String KEY_CREDIT_NOTE = "invoice.credit_note.on_refund";
    static final String KEY_PREFIX = "invoice.b2c.prefix";
    static final String KEY_ISSUER_COUNTRY = "invoice.issuer_country";
    static final String ON_SETTLE = "ON_SETTLE";
    static final String KIND_INVOICE = "INVOICE";
    static final String KIND_CREDIT_NOTE = "CREDIT_NOTE";
    static final String BLOCKED = "BLOCKED";
    private static final String TENANT = "MAIN";

    private final OrdTaxInvoiceMapper mapper;
    private final OrdMapper orders;
    private final SysParamPort params;
    private final TaxRatePort taxes;
    private final BizClock clock;

    @Override
    public void onPaid(String orderNo, String payNo, boolean orderSettled) {
        if (payNo == null || !enabled()) return;
        // ON_SETTLE：没结单的收款（超时已扣款未归还）先不开，归还结单时 onSettled 补开
        if (ON_SETTLE.equalsIgnoreCase(params.textOf(KEY_TRIGGER, "ON_CHARGE").trim()) && !orderSettled) return;
        safely(orderNo, () -> issueForPay(orderNo, payNo));
    }

    @Override
    public void onSettled(String orderNo) {
        if (!enabled() || !ON_SETTLE.equalsIgnoreCase(params.textOf(KEY_TRIGGER, "ON_CHARGE").trim())) return;
        safely(orderNo, () -> {
            OrdOrder o = order(orderNo);
            if (o != null && o.getPayNo() != null) issueForPay(orderNo, o.getPayNo());
        });
    }

    @Override
    public void onRefundApproved(String orderNo, String refundNo, BigDecimal amount, String currency) {
        if (!enabled() || params.intOf(KEY_CREDIT_NOTE, 1) != 1) return;
        safely(orderNo, () -> {
            OrdTaxInvoice ref = DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                    new LambdaQueryWrapper<OrdTaxInvoice>().eq(OrdTaxInvoice::getOrderNo, orderNo)
                            .eq(OrdTaxInvoice::getKind, KIND_INVOICE).eq(OrdTaxInvoice::getStatus, ISSUED)
                            .orderByDesc(OrdTaxInvoice::getId).last("limit 1")));
            if (ref == null) return;   // 没开过票（开关后开、或主体缺失）就没有可冲的
            OrdTaxInvoice cn = draft(order(orderNo), "REFUND:" + refundNo, KIND_CREDIT_NOTE);
            cn.setRefInvoiceNo(ref.getInvoiceNo());
            cn.setRefundNo(refundNo);
            cn.setCurrency(currency == null ? ref.getCurrency() : currency);
            // 冲红沿用原票的税率与含税口径，不取当前设置 —— 否则改了税率后冲出来的税额对不上原票
            cn.setTaxRatePercent(ref.getTaxRatePercent());
            cn.setTaxIncluded(ref.getTaxIncluded());
            cn.setLineDesc("Credit note for " + ref.getInvoiceNo());
            split(cn, amount == null ? BigDecimal.ZERO : amount.negate());
            insert(cn);
        });
    }

    private void issueForPay(String orderNo, String payNo) {
        OrdOrder o = order(orderNo);
        if (o == null) return;
        OrdTaxInvoice inv = draft(o, "PAY:" + payNo, KIND_INVOICE);
        inv.setPayNo(payNo);
        inv.setCurrency(o.getCurrency() == null ? "AED" : o.getCurrency());
        inv.setLineDesc("Power bank rental " + nz(o.getRentStartAt()) + " ~ " + nz(o.getRentEndAt())
                + (o.getCabinetNo() == null ? "" : " @" + o.getCabinetNo()));
        BigDecimal paid = o.getPaidAmount() == null ? BigDecimal.ZERO : o.getPaidAmount();
        BigDecimal feeIn = splitPaid(inv, o, paid);
        if (feeIn.signum() > 0) inv.setLineDesc(inv.getLineDesc() + " + service fee " + feeIn.toPlainString());
        insert(inv);
    }

    /**
     * 按本单收款额拆税。服务费不计 VAT（C09 rent.service_fee.vat_applicable=0，借出时定格）时只对租金部分拆税，
     * 服务费原额并入净额与总额。返回本单所含服务费（0 = 无）。
     */
    private static BigDecimal splitPaid(OrdTaxInvoice inv, OrdOrder o, BigDecimal paid) {
        var fee = o == null ? null : ai.neargo.sharehub.trade.price.engine.PriceResolver.serviceFeeFromSnapshot(o.getPriceSnapshot());
        BigDecimal feeIn = fee == null ? BigDecimal.ZERO : fee.includedIn(paid);
        if (feeIn.signum() > 0 && !fee.vatApplicable()) {
            split(inv, paid.subtract(feeIn));
            inv.setNetAmount(inv.getNetAmount().add(feeIn));
            inv.setGrossAmount(inv.getGrossAmount().add(feeIn));
        } else {
            split(inv, paid);
        }
        return feeIn;
    }

    /** 建一张票并快照主体；主体不全 → BLOCKED，原因写明缺哪项。 */
    private OrdTaxInvoice draft(OrdOrder o, String sourceKey, String kind) {
        OrdTaxInvoice t = new OrdTaxInvoice();
        t.setTenantId(TENANT);
        t.setKind(kind);
        t.setSourceKey(sourceKey);
        t.setOrderNo(o.getOrderNo());
        t.setCUserNo(o.getCUserNo());
        applyIssuer(t);
        return t;
    }

    private void applyIssuer(OrdTaxInvoice t) {
        String country = params.textOf(KEY_ISSUER_COUNTRY, "AE").trim();
        TaxRatePort.Issuer is = taxes.issuerOf(country);
        if (is == null) {
            block(t, "ISSUER_MISSING:taxSetting");
            t.setTaxRatePercent(BigDecimal.ZERO);
            t.setTaxIncluded(1);
            return;
        }
        t.setIssuerCountry(is.country());
        t.setIssuerLegalName(is.legalName());
        t.setIssuerAddress(is.address());
        t.setIssuerTrn(is.trn());
        t.setIssuerTitle(is.title());
        t.setTaxRatePercent(is.ratePercent());
        t.setTaxIncluded(is.includedInPrice() ? 1 : 0);
        List<String> missing = is.missing();
        if (missing.isEmpty()) {
            t.setStatus(ISSUED);
            t.setFailReason(null);
            t.setIssuedAt(clock.now());
        } else {
            block(t, "ISSUER_MISSING:" + String.join(",", missing));
        }
    }

    private static void block(OrdTaxInvoice t, String reason) {
        t.setStatus(BLOCKED);
        t.setFailReason(reason);
        t.setIssuedAt(null);
    }

    /**
     * 拆税。收的钱就是总额：含税价 → net = gross/(1+r)、tax = gross − net；
     * 价外税 → 订单金额是税前，tax = net × r、gross = net + tax。两位小数 HALF_UP，差额落在税额上保证 net+tax=gross。
     */
    static void split(OrdTaxInvoice t, BigDecimal amount) {
        BigDecimal r = (t.getTaxRatePercent() == null ? BigDecimal.ZERO : t.getTaxRatePercent())
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal net;
        BigDecimal gross;
        if (t.getTaxIncluded() == null || t.getTaxIncluded() != 0) {
            gross = amount.setScale(2, RoundingMode.HALF_UP);
            net = gross.divide(BigDecimal.ONE.add(r), 2, RoundingMode.HALF_UP);
        } else {
            net = amount.setScale(2, RoundingMode.HALF_UP);
            gross = net.add(net.multiply(r)).setScale(2, RoundingMode.HALF_UP);
        }
        t.setNetAmount(net);
        t.setGrossAmount(gross);
        t.setTaxAmount(gross.subtract(net));
    }

    /** 占号插入；source_key 已存在 = 重复回调，静默跳过；票号撞了（并发）重取一次。 */
    private void insert(OrdTaxInvoice t) {
        if (exists(t.getSourceKey())) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            t.setInvoiceNo(nextNo(t.getKind()));
            try {
                mapper.insert(t);
                if (BLOCKED.equals(t.getStatus())) {
                    log.error("税务发票未能开出 invoiceNo={} orderNo={} reason={}；请运营在 系统设置 › 税务 补齐开票主体后到 财务 › 发票 › 租赁发票 重试",
                            t.getInvoiceNo(), t.getOrderNo(), t.getFailReason());
                } else {
                    log.info("税务票据已开 invoiceNo={} kind={} orderNo={} gross={}", t.getInvoiceNo(), t.getKind(), t.getOrderNo(), t.getGrossAmount());
                }
                return;
            } catch (DuplicateKeyException dup) {
                if (exists(t.getSourceKey())) return;
            }
        }
        log.error("税务发票取号连续冲突，本次未开 orderNo={} sourceKey={}；请运营到 财务 › 发票 › 租赁发票 核对", t.getOrderNo(), t.getSourceKey());
    }

    private boolean exists(String sourceKey) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectCount(new LambdaQueryWrapper<OrdTaxInvoice>()
                .eq(OrdTaxInvoice::getTenantId, TENANT).eq(OrdTaxInvoice::getSourceKey, sourceKey))) > 0;
    }

    /** INV-2026-000001：前缀可配，贷项通知单固定 CN；按业务日历的年份分段。 */
    private String nextNo(String kind) {
        String prefix = KIND_CREDIT_NOTE.equals(kind) ? "CN" : params.textOf(KEY_PREFIX, "INV").trim();
        String head = prefix + "-" + clock.today().getYear() + "-";
        OrdTaxInvoice last = DataScopeContext.executeWithoutScope(() -> mapper.selectOne(new LambdaQueryWrapper<OrdTaxInvoice>()
                .select(OrdTaxInvoice::getInvoiceNo).eq(OrdTaxInvoice::getTenantId, TENANT)
                .likeRight(OrdTaxInvoice::getInvoiceNo, head).orderByDesc(OrdTaxInvoice::getInvoiceNo).last("limit 1")));
        long n = last == null ? 1 : Long.parseLong(last.getInvoiceNo().substring(head.length())) + 1;
        return head + String.format("%06d", n);
    }

    private void safely(String orderNo, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            log.error("开税务发票异常，收款/退款不受影响 orderNo={}；请运营到 财务 › 发票 › 租赁发票 核对", orderNo, ex);
        }
    }

    private boolean enabled() {
        return params.intOf(KEY_ENABLED, 0) == 1;
    }

    private OrdOrder order(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                new LambdaQueryWrapper<OrdOrder>().eq(OrdOrder::getOrderNo, orderNo).last("limit 1")));
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    @Override
    public PageResult<OrdTaxInvoice> page(String orderNo, String kind, String status, int pageNum, int pageSize) {
        Page<OrdTaxInvoice> p = mapper.selectPage(new Page<>(Math.max(1, pageNum), Math.min(200, Math.max(1, pageSize))),
                new LambdaQueryWrapper<OrdTaxInvoice>()
                        .eq(orderNo != null && !orderNo.isBlank(), OrdTaxInvoice::getOrderNo, orderNo)
                        .eq(kind != null && !kind.isBlank(), OrdTaxInvoice::getKind, kind)
                        .eq(status != null && !status.isBlank(), OrdTaxInvoice::getStatus, status)
                        .orderByDesc(OrdTaxInvoice::getId));
        return new PageResult<>(p.getRecords(), p.getTotal());
    }

    @Override
    public OrdTaxInvoice get(String invoiceNo) {
        OrdTaxInvoice t = mapper.selectOne(new LambdaQueryWrapper<OrdTaxInvoice>()
                .eq(OrdTaxInvoice::getInvoiceNo, invoiceNo).last("limit 1"));
        if (t == null) throw BizException.notFound(invoiceNo);
        return t;
    }

    @Override
    public List<OrdTaxInvoice> listForExport(String orderNo, String kind, String status,
                                             java.time.LocalDate from, java.time.LocalDate to, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<OrdTaxInvoice>()
                .eq(orderNo != null && !orderNo.isBlank(), OrdTaxInvoice::getOrderNo, orderNo)
                .eq(kind != null && !kind.isBlank(), OrdTaxInvoice::getKind, kind)
                .eq(status != null && !status.isBlank(), OrdTaxInvoice::getStatus, status)
                .ge(from != null, OrdTaxInvoice::getCreatedAt, from == null ? null : clock.startOfDayAsStored(from))
                .lt(to != null, OrdTaxInvoice::getCreatedAt, to == null ? null : clock.startOfDayAsStored(to.plusDays(1)))
                .orderByAsc(OrdTaxInvoice::getInvoiceNo).last("limit " + Math.max(1, limit)));
    }

    @Override
    public OrdTaxInvoice retry(String invoiceNo) {
        OrdTaxInvoice t = get(invoiceNo);
        if (!BLOCKED.equals(t.getStatus())) throw BizException.conflict("error.common.state_changed");
        BigDecimal gross = t.getGrossAmount();
        BigDecimal net = t.getNetAmount();
        applyIssuer(t);
        if (KIND_INVOICE.equals(t.getKind())) {
            // 按新主体的税率重拆：原始金额是当时的收款额（落库的 gross）；服务费免税口径同开票时
            OrdOrder o = order(t.getOrderNo());
            BigDecimal paid = o != null && o.getPaidAmount() != null ? o.getPaidAmount() : (t.getTaxIncluded() != 0 ? gross : net);
            splitPaid(t, o, paid);
        }
        if (mapper.updateById(t) == 0) throw BizException.conflict("error.common.state_changed");
        log.info("重试开税务发票 invoiceNo={} status={} reason={}", invoiceNo, t.getStatus(), t.getFailReason());
        return t;
    }

    @Override
    public List<OrdTaxInvoice> listForUser(String cUserNo, String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(new LambdaQueryWrapper<OrdTaxInvoice>()
                .eq(OrdTaxInvoice::getCUserNo, cUserNo).eq(OrdTaxInvoice::getOrderNo, orderNo)
                .eq(OrdTaxInvoice::getStatus, ISSUED).orderByAsc(OrdTaxInvoice::getId)));
    }
}
