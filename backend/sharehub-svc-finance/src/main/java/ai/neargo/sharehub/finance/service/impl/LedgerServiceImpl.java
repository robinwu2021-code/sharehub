package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.dto.FinDtos.LedgerEntry;
import ai.neargo.sharehub.finance.dto.FinDtos.LedgerLine;
import ai.neargo.sharehub.finance.dto.FinDtos.LedgerPostReq;
import ai.neargo.sharehub.finance.entity.AcctAccount;
import ai.neargo.sharehub.finance.entity.AcctLedger;
import ai.neargo.sharehub.finance.mapper.AcctAccountMapper;
import ai.neargo.sharehub.finance.mapper.AcctLedgerMapper;
import ai.neargo.sharehub.finance.service.LedgerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 账务分录实现。**只增，不改不删** —— 见 {@link LedgerService} 类注释。
 *
 * <p>{@link #post} 是本域唯一的写账口子，任何业务（分润入账、结算出账、提现扣款）
 * 都必须经它落分录，好处是「借贷平衡」这条铁律只需要在一个地方守住。
 */
@Service
public class LedgerServiceImpl implements LedgerService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";

    private final AcctLedgerMapper mapper;
    private final AcctAccountMapper accountMapper;

    public LedgerServiceImpl(AcctLedgerMapper mapper, AcctAccountMapper accountMapper) {
        this.mapper = mapper;
        this.accountMapper = accountMapper;
    }

    @Override
    public PageResult<LedgerEntry> page(Integer page, Integer size, String keyword,
                                        String accountNo, String bizType) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<AcctLedger> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(AcctLedger::getEntryNo, kw)
                    .or().like(AcctLedger::getVoucherNo, kw)
                    .or().like(AcctLedger::getOrderNo, kw)
                    .or().like(AcctLedger::getAccount, kw));
        }
        if (accountNo != null && !accountNo.isBlank()) w.eq(AcctLedger::getAccountNo, accountNo);
        if (bizType != null && !bizType.isBlank()) w.eq(AcctLedger::getBizType, bizType);
        w.orderByDesc(AcctLedger::getId);

        Page<AcctLedger> r = mapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(LedgerServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public List<LedgerEntry> byVoucher(String voucherNo) {
        if (voucherNo == null || voucherNo.isBlank()) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<AcctLedger>()
                        .eq(AcctLedger::getVoucherNo, voucherNo)
                        .orderByAsc(AcctLedger::getId))
                .stream().map(LedgerServiceImpl::toVO).toList();
    }

    @Override
    @Transactional
    public String post(LedgerPostReq req) {
        if (req == null || req.lines() == null || req.lines().isEmpty()) {
            throw new IllegalArgumentException("记账分录不能为空");
        }

        // —— 借贷平衡校验：这是复式记账的定义本身，不平就不是一笔合法的账 ——
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        String currency = null;
        for (LedgerLine ln : req.lines()) {
            if (ln.amount() == null || ln.amount().signum() <= 0) {
                throw new IllegalArgumentException("分录金额必须为正数（方向由 direction 表达，不用负数）");
            }
            if (currency == null) {
                currency = ln.currency();
            } else if (!currency.equals(ln.currency())) {
                // 跨币种记账必须先经汇率换算成同一记账本位币，混记会让「平衡」失去意义
                throw new IllegalArgumentException("同一凭证不得混记多币种: " + currency + " / " + ln.currency());
            }
            if (DEBIT.equals(ln.direction())) {
                debit = debit.add(ln.amount());
            } else if (CREDIT.equals(ln.direction())) {
                credit = credit.add(ln.amount());
            } else {
                throw new IllegalArgumentException("分录方向只能是 DEBIT / CREDIT: " + ln.direction());
            }
        }
        if (debit.compareTo(credit) != 0) {
            throw new IllegalArgumentException("借贷不平衡: DEBIT=" + debit + " CREDIT=" + credit);
        }
        if (debit.signum() == 0) {
            throw new IllegalArgumentException("空账（借贷双方均为 0）无意义");
        }

        String voucherNo = (req.voucherNo() == null || req.voucherNo().isBlank())
                ? FinNos.nextNo(mapper, "voucher_no", BizKey.VOUCHER, 8)
                : req.voucherNo();
        String tenantId = SecurityUtils.tenantId();
        String now = LocalDateTime.now().format(TS);
        String entryNo = FinNos.nextNo(mapper, "entry_no", BizKey.LEDGER_ENTRY, 8);
        long seq = Long.parseLong(entryNo.substring(BizKey.LEDGER_ENTRY.length()));

        for (LedgerLine ln : req.lines()) {
            AcctLedger e = new AcctLedger();
            e.setTenantId(tenantId);
            // 同一凭证内逐条递增，避免每条都回表扫号
            e.setEntryNo(BizKey.LEDGER_ENTRY + String.format("%08d", seq++));
            e.setVoucherNo(voucherNo);
            e.setOrderNo(req.orderNo());
            e.setAccountNo(ln.accountNo());
            e.setAccount(ln.account());
            e.setDirection(ln.direction());
            e.setAmount(ln.amount());
            e.setCurrency(ln.currency());
            e.setSummary(req.summary());
            e.setBizType(req.bizType());
            e.setBizNo(req.bizNo());
            e.setCreatedAt(now);
            mapper.insert(e);
            applyBalance(ln);
        }
        return voucherNo;
    }

    /**
     * 同步账户余额快照。
     *
     * <p>余额<b>不是真相源</b>（真相源是分录流水），这里维护的只是一份读取快照。
     * 口径按「资金账户」处理：DEBIT 增、CREDIT 减；账户不存在时跳过（分录照样落，
     * 账不能因为快照表缺行而记不上）。
     */
    private void applyBalance(LedgerLine ln) {
        if (ln.accountNo() == null || ln.accountNo().isBlank()) return;
        AcctAccount acc = accountMapper.selectOne(new LambdaQueryWrapper<AcctAccount>()
                .eq(AcctAccount::getAccountNo, ln.accountNo()).last("limit 1"));
        if (acc == null) return;
        BigDecimal base = acc.getBalance() == null ? BigDecimal.ZERO : acc.getBalance();
        acc.setBalance(DEBIT.equals(ln.direction()) ? base.add(ln.amount()) : base.subtract(ln.amount()));
        accountMapper.updateById(acc);
    }

    private static LedgerEntry toVO(AcctLedger e) {
        return new LedgerEntry(e.getEntryNo(), e.getVoucherNo(), e.getOrderNo(), e.getAccountNo(),
                e.getAccount(), e.getDirection(), e.getAmount(), e.getCurrency(),
                e.getSummary(), e.getBizType(), e.getBizNo(), e.getCreatedAt());
    }
}
