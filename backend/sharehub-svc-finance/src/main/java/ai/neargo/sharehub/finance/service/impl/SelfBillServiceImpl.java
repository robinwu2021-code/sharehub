package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.api.platform.port.TaxRatePort;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.time.BizClock;
import ai.neargo.sharehub.finance.WithdrawalStatus;
import ai.neargo.sharehub.finance.entity.StlSelfBill;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;
import ai.neargo.sharehub.finance.mapper.StlSelfBillMapper;
import ai.neargo.sharehub.finance.mapper.StlWithdrawalMapper;
import ai.neargo.sharehub.finance.service.SelfBillService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 自开票出单。税率与收货方取平台开票主体那一国的税务设置（{@code invoice.issuer_country}）；
 * <b>付款额视为含税</b>：付给商家的就是全部，税从里面倒推（未确认项按配置化原则定一个口径并写明，改口径改这里）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfBillServiceImpl implements SelfBillService {

    static final String KEY_ENABLED = "finance.share.self_billing";
    static final String KEY_PREFIX = "finance.share.self_billing.prefix";
    private static final String TENANT = "MAIN";

    private final StlSelfBillMapper mapper;
    private final StlWithdrawalMapper withdrawals;
    private final SysParamPort params;
    private final TaxRatePort taxes;
    private final AgentDirectoryPort agents;
    private final BizClock clock;

    @Override
    public void onWithdrawalPaid(StlWithdrawal w) {
        if (params.intOf(KEY_ENABLED, 0) != 1) return;
        try {
            issue(w, false);
        } catch (RuntimeException ex) {
            log.error("自开票出单失败，到账登记不受影响 withdrawNo={}；请运营到 财务 › 发票 › 自开票 补出", w.getWithdrawNo(), ex);
        }
    }

    @Override
    public StlSelfBill issueFor(String withdrawNo) {
        StlWithdrawal w = withdrawals.selectOne(new LambdaQueryWrapper<StlWithdrawal>()
                .eq(StlWithdrawal::getWithdrawNo, withdrawNo).last("limit 1"));
        if (w == null) throw BizException.notFound(withdrawNo);
        if (!WithdrawalStatus.PAID.name().equals(w.getStatus())) throw BizException.conflict("error.self_bill.not_paid", withdrawNo);
        StlSelfBill b = issue(w, true);
        return b != null ? b : existing(withdrawNo);
    }

    /** @param strict 运营手动补出时，缺 TRN / 缺主体要明确报错；自动出单时只记日志跳过 */
    private StlSelfBill issue(StlWithdrawal w, boolean strict) {
        StlSelfBill had = existing(w.getWithdrawNo());
        if (had != null) return had;
        AgentDirectoryPort.MerchantTaxProfile m = agents.taxProfileOf(w.getPayeeNo());
        if (m == null || m.trn() == null || m.trn().isBlank()) {
            if (strict) throw BizException.conflict("error.self_bill.no_trn", w.getPayeeNo());
            log.info("商家无 VAT 税号，不出自开票 withdrawNo={} payeeNo={}", w.getWithdrawNo(), w.getPayeeNo());
            return null;
        }
        TaxRatePort.Issuer is = taxes.issuerOf(params.textOf("invoice.issuer_country", "AE"));
        if (is == null || !is.missing().isEmpty()) {
            String miss = is == null ? "taxSetting" : String.join(",", is.missing());
            if (strict) throw BizException.conflict("error.self_bill.issuer_missing", miss);
            log.error("平台开票主体缺项 {}，自开票未出 withdrawNo={}；请运营在 系统设置 › 税率与发票 补齐后补出", miss, w.getWithdrawNo());
            return null;
        }
        StlSelfBill b = new StlSelfBill();
        b.setTenantId(TENANT);
        b.setWithdrawNo(w.getWithdrawNo());
        b.setAgentNo(w.getPayeeNo());
        b.setSupplierName(m.name());
        b.setSupplierAddress(m.address());
        b.setSupplierTrn(m.trn());
        b.setRecipientName(is.legalName());
        b.setRecipientAddress(is.address());
        b.setRecipientTrn(is.trn());
        b.setTaxRatePercent(is.ratePercent());
        BigDecimal gross = (w.getAmount() == null ? BigDecimal.ZERO : w.getAmount()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.divide(BigDecimal.ONE.add(is.ratePercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)),
                2, RoundingMode.HALF_UP);
        b.setGrossAmount(gross);
        b.setNetAmount(net);
        b.setTaxAmount(gross.subtract(net));
        b.setCurrency(w.getCurrency() == null ? "AED" : w.getCurrency());
        b.setPayRef(w.getPayRef());
        b.setIssuedAt(clock.now());
        for (int attempt = 0; attempt < 3; attempt++) {
            b.setBillNo(nextNo());
            try {
                mapper.insert(b);
                log.info("自开票已出 billNo={} withdrawNo={} agentNo={} gross={}", b.getBillNo(), b.getWithdrawNo(), b.getAgentNo(), gross);
                return b;
            } catch (DuplicateKeyException dup) {
                StlSelfBill again = existing(w.getWithdrawNo());
                if (again != null) return again;   // 并发确认：另一边已出
            }
        }
        log.error("自开票取号连续冲突，未出 withdrawNo={}；请运营稍后补出", w.getWithdrawNo());
        return null;
    }

    private StlSelfBill existing(String withdrawNo) {
        return mapper.selectOne(new LambdaQueryWrapper<StlSelfBill>().eq(StlSelfBill::getTenantId, TENANT)
                .eq(StlSelfBill::getWithdrawNo, withdrawNo).last("limit 1"));
    }

    private String nextNo() {
        String head = params.textOf(KEY_PREFIX, "SB").trim() + "-" + clock.today().getYear() + "-";
        StlSelfBill last = mapper.selectOne(new LambdaQueryWrapper<StlSelfBill>().select(StlSelfBill::getBillNo)
                .eq(StlSelfBill::getTenantId, TENANT).likeRight(StlSelfBill::getBillNo, head)
                .orderByDesc(StlSelfBill::getBillNo).last("limit 1"));
        long n = last == null ? 1 : Long.parseLong(last.getBillNo().substring(head.length())) + 1;
        return head + String.format("%06d", n);
    }

    @Override
    public PageResult<StlSelfBill> page(Integer page, Integer size, String agentNo, String keyword) {
        Page<StlSelfBill> p = mapper.selectPage(new Page<>(page == null ? 1 : Math.max(1, page),
                        size == null ? 20 : Math.min(200, Math.max(1, size))),
                new LambdaQueryWrapper<StlSelfBill>()
                        .eq(agentNo != null && !agentNo.isBlank(), StlSelfBill::getAgentNo, agentNo)
                        .and(keyword != null && !keyword.isBlank(), q -> q.like(StlSelfBill::getBillNo, keyword.trim())
                                .or().like(StlSelfBill::getWithdrawNo, keyword.trim())
                                .or().like(StlSelfBill::getSupplierName, keyword.trim()))
                        .orderByDesc(StlSelfBill::getId));
        return new PageResult<>(p.getRecords(), p.getTotal());
    }

    @Override
    public StlSelfBill get(String billNo) {
        StlSelfBill b = mapper.selectOne(new LambdaQueryWrapper<StlSelfBill>().eq(StlSelfBill::getBillNo, billNo).last("limit 1"));
        if (b == null) throw BizException.notFound(billNo);
        return b;
    }
}
