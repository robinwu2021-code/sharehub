package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutAccount;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutAccountReq;
import ai.neargo.sharehub.finance.entity.StlPayoutAccount;
import ai.neargo.sharehub.finance.mapper.StlPayoutAccountMapper;
import ai.neargo.sharehub.finance.service.PayoutAccountService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 收款账户实现。 */
@Service
public class PayoutAccountServiceImpl implements PayoutAccountService {

    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    /**
     * 受益方类型。
     *
     * <p>⚠️ <b>是 {@code AGENT} 不是 {@code OPERATOR}</b>，尽管 ADR-029 把代理商抽象成了
     * 「运营主体 Operator」。理由很实际：{@code share_record.payee_type} 与
     * {@code stl_withdrawal.payee_type} 现网存的都是 {@code AGENT}，
     * 本表要和它们对得上 —— 一张表用新词、另两张用旧词，join 不上、也查不出「这笔打给了谁」。
     *
     * <p>改名归改名的窗口（ADR-029 §5.1 的 B 步，随 ADR-021 的 S1 一起做），
     * <b>那时三张表一起改</b>；在那之前新表跟着旧词走，比提前用新词更安全。
     */
    private static final List<String> PAYEE_TYPES = List.of("AGENT", "VENUE");

    private final StlPayoutAccountMapper mapper;

    public PayoutAccountServiceImpl(StlPayoutAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<PayoutAccount> page(Integer page, Integer size,
                                          String payeeType, String payeeNo, String keyword) {
        int p = page == null || page < 1 ? 1 : page;
        int sz = size == null || size < 1 ? 10 : size;
        LambdaQueryWrapper<StlPayoutAccount> w = new LambdaQueryWrapper<>();
        if (payeeType != null && !payeeType.isBlank()) w.eq(StlPayoutAccount::getPayeeType, payeeType);
        if (payeeNo != null && !payeeNo.isBlank()) w.eq(StlPayoutAccount::getPayeeNo, payeeNo);
        if (keyword != null && !keyword.isBlank()) {
            /*
             * 只搜户名与受益方编号。**不搜 accountMasked** —— 掩码是显示值，
             * 同号段的账号掩码可能相同，按它搜会把不相干的账户捞出来
             * （与入驻队列不搜手机号掩码同一条理由）。
             */
            w.and(x -> x.like(StlPayoutAccount::getAccountName, keyword)
                    .or().like(StlPayoutAccount::getPayeeNo, keyword));
        }
        w.orderByDesc(StlPayoutAccount::getIsDefault).orderByDesc(StlPayoutAccount::getId);
        Page<StlPayoutAccount> r = mapper.selectPage(new Page<>(p, sz), w);
        return new PageResult<>(r.getRecords().stream().map(PayoutAccountServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public Optional<PayoutAccount> defaultOf(String payeeType, String payeeNo) {
        if (payeeType == null || payeeNo == null) return Optional.empty();
        StlPayoutAccount e = mapper.selectOne(new LambdaQueryWrapper<StlPayoutAccount>()
                .eq(StlPayoutAccount::getPayeeType, payeeType)
                .eq(StlPayoutAccount::getPayeeNo, payeeNo)
                .eq(StlPayoutAccount::getStatus, ACTIVE)
                .eq(StlPayoutAccount::getIsDefault, 1)
                /*
                 * ⚠️ **limit 1 必须带确定排序**。「恰好一个默认」是应用层保证的，
                 * 万一被破坏（并发、数据修补），无序的 limit 1 会随机挑一个 ——
                 * 「今天打给这张卡、明天打给那张」这种故障没人能复现。
                 */
                .orderByAsc(StlPayoutAccount::getId)
                .last("limit 1"));
        return Optional.ofNullable(e).map(PayoutAccountServiceImpl::toVO);
    }

    @Override
    @Transactional
    public PayoutAccount save(PayoutAccountReq req) {
        requireText(req.payeeNo(), "受益方编号必填");
        if (!PAYEE_TYPES.contains(req.payeeType())) {
            // 提示必须与 PAYEE_TYPES 同源：原文写的是「只能是 OPERATOR 或 VENUE」，
            // 而 OPERATOR 恰恰是会被这一行拒掉的值 —— 照着提示改一遍照样 400。
            throw new IllegalArgumentException("受益方类型只能是 " + String.join(" 或 ", PAYEE_TYPES));
        }
        requireText(req.bankCode(), "开户行必填");
        requireText(req.accountName(), "户名必填");
        requireText(req.accountMasked(), "账号必填");

        boolean insert = req.accountNo() == null || req.accountNo().isBlank();
        StlPayoutAccount e = insert ? new StlPayoutAccount() : require(req.accountNo());
        if (insert) {
            e.setAccountNo(nextNo());
            e.setTenantId("MAIN");
            e.setStatus(ACTIVE);
        }
        e.setPayeeType(req.payeeType());
        e.setPayeeNo(req.payeeNo());
        e.setBankCode(req.bankCode());
        e.setAccountName(req.accountName().trim());
        // 入参给的是明文，这里只落掩码；明文入 pii（PDPL）——本批不建 pii 库，先不落明文
        e.setAccountMasked(mask(req.accountMasked()));
        e.setCurrency(req.currency() == null || req.currency().isBlank() ? "AED" : req.currency());

        /*
         * 第一个账户自动成为默认 —— 否则「录了账户却还是不能收款」，
         * 而用户完全看不出还差一步。
         */
        boolean makeDefault = Boolean.TRUE.equals(req.makeDefault())
                || (insert && countOf(req.payeeType(), req.payeeNo()) == 0);
        e.setIsDefault(makeDefault ? 1 : (e.getIsDefault() == null ? 0 : e.getIsDefault()));

        if (insert) mapper.insert(e); else mapper.updateById(e);

        if (makeDefault) clearOtherDefaults(e);
        return toVO(require(e.getAccountNo()));
    }

    @Override
    @Transactional
    public PayoutAccount disable(String accountNo) {
        StlPayoutAccount e = require(accountNo);
        /*
         * 停用默认账户要挡住：停完这个受益方就没有可用账户了，而提现照样能申请 ——
         * 直到审批那一刻才发现打不出去。先把默认切到别的账户上。
         */
        if (Integer.valueOf(1).equals(e.getIsDefault())) {
            long others = mapper.selectCount(new LambdaQueryWrapper<StlPayoutAccount>()
                    .eq(StlPayoutAccount::getPayeeType, e.getPayeeType())
                    .eq(StlPayoutAccount::getPayeeNo, e.getPayeeNo())
                    .eq(StlPayoutAccount::getStatus, ACTIVE)
                    .ne(StlPayoutAccount::getAccountNo, accountNo));
            if (others > 0) {
                throw new IllegalArgumentException("这是默认收款账户，请先把默认切到其它账户再停用");
            }
        }
        e.setStatus(DISABLED);
        e.setIsDefault(0);
        mapper.updateById(e);
        return toVO(require(accountNo));
    }

    // ——————————————————————— 内部 ———————————————————————

    private void clearOtherDefaults(StlPayoutAccount kept) {
        List<StlPayoutAccount> siblings = mapper.selectList(new LambdaQueryWrapper<StlPayoutAccount>()
                .eq(StlPayoutAccount::getPayeeType, kept.getPayeeType())
                .eq(StlPayoutAccount::getPayeeNo, kept.getPayeeNo())
                .eq(StlPayoutAccount::getIsDefault, 1)
                .ne(StlPayoutAccount::getAccountNo, kept.getAccountNo()));
        for (StlPayoutAccount s : siblings) {
            s.setIsDefault(0);
            mapper.updateById(s);
        }
    }

    private long countOf(String payeeType, String payeeNo) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<StlPayoutAccount>()
                .eq(StlPayoutAccount::getPayeeType, payeeType)
                .eq(StlPayoutAccount::getPayeeNo, payeeNo));
        return n == null ? 0 : n;
    }

    private StlPayoutAccount require(String accountNo) {
        StlPayoutAccount e = mapper.selectOne(new LambdaQueryWrapper<StlPayoutAccount>()
                .eq(StlPayoutAccount::getAccountNo, accountNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("收款账户不存在: " + accountNo);
        return e;
    }

    /** 业务键：扫同前缀最大号 + 1（数字序，不能用字典序——位宽不一致时会取错）。 */
    private String nextNo() {
        List<Object> top = mapper.selectObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StlPayoutAccount>()
                .select("MAX(CAST(SUBSTRING(account_no, 3) AS UNSIGNED)) AS mx")
                .likeRight("account_no", "PA"));
        long n = 0L;
        if (top != null && !top.isEmpty() && top.get(0) != null) {
            try {
                n = Long.parseLong(String.valueOf(top.get(0)));
            } catch (NumberFormatException ignore) {
                n = 0L;
            }
        }
        return "PA" + String.format("%03d", n + 1);
    }

    /** 掩码：保留末 4 位。与手机号掩码同一条约束 —— **只供显示，不可做等值判断**。 */
    private static String mask(String raw) {
        String t = raw.replaceAll("\\s", "");
        return t.length() <= 4 ? "****" : "****" + t.substring(t.length() - 4);
    }

    private static void requireText(String v, String msg) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(msg);
    }

    private static PayoutAccount toVO(StlPayoutAccount e) {
        return new PayoutAccount(e.getAccountNo(), e.getPayeeType(), e.getPayeeNo(),
                e.getBankCode(), e.getAccountName(), e.getAccountMasked(),
                e.getCurrency(), Integer.valueOf(1).equals(e.getIsDefault()), e.getStatus());
    }
}
