package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.finance.WithdrawalStatus;

import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.WithdrawFeePolicy;
import ai.neargo.sharehub.finance.WithdrawalStateMachine;
import ai.neargo.sharehub.finance.dto.FinDtos.WithdrawApplyReq;
import ai.neargo.sharehub.finance.dto.FinDtos.Withdrawal;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;
import ai.neargo.sharehub.finance.mapper.StlWithdrawalMapper;
import ai.neargo.sharehub.finance.service.WithdrawalService;
import ai.neargo.common.core.PageResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 提现实现 —— 资金审批合规四件套（{@code fee} / {@code auditor_no}+{@code auditor_name} /
 * {@code audited_at} / {@code reject_reason}）全部在这里落地。
 *
 * <p>三条不肯让步的规则：
 * <ol>
 *   <li><b>手续费服务端算</b>：来源 {@link WithdrawFeePolicy}（真实口径 {@code sys_biz_rule(WITHDRAW)}），
 *       申请入参里根本没有 fee 字段；</li>
 *   <li><b>审批人服务端回填</b>：取登录会话，不接受前端传的 auditorName —— 否则审批留痕可被伪造；</li>
 *   <li><b>驳回必须有原因</b>：空原因直接抛异常，不落库。</li>
 * </ol>
 */
@Service
public class WithdrawalServiceImpl implements WithdrawalService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StlWithdrawalMapper mapper;
    private final WithdrawFeePolicy feePolicy;
    private final WithdrawalStateMachine stateMachine;

    public WithdrawalServiceImpl(StlWithdrawalMapper mapper, WithdrawFeePolicy feePolicy,
                                 WithdrawalStateMachine stateMachine) {
        this.mapper = mapper;
        this.feePolicy = feePolicy;
        this.stateMachine = stateMachine;
    }

    @Override
    @Transactional
    public Withdrawal apply(WithdrawApplyReq req) {
        if (req == null) throw new IllegalArgumentException("提现申请不能为空");
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new IllegalArgumentException("提现金额必须大于 0");
        }
        if (req.payeeNo() == null || req.payeeNo().isBlank()) {
            throw new IllegalArgumentException("收款主体 payeeNo 必填");
        }

        BigDecimal fee = feePolicy.feeOf(req.amount(), req.currency(), req.payeeType());
        if (fee != null && fee.compareTo(req.amount()) >= 0) {
            throw new IllegalArgumentException("手续费不得大于等于提现金额，请提高提现额度");
        }

        StlWithdrawal e = new StlWithdrawal();
        e.setTenantId(SecurityUtils.tenantId());
        e.setWithdrawNo(FinNos.nextNo(mapper, "withdraw_no", BizKey.WITHDRAWAL, 6));
        e.setAccountNo(req.accountNo());
        e.setPayeeType(req.payeeType());
        e.setPayeeNo(req.payeeNo());
        e.setPayeeName(req.payeeName());
        e.setAmount(req.amount());
        e.setFee(fee);                     // 服务端算，不接受入参
        e.setCurrency(req.currency());
        e.setBankCode(req.bankCode());
        e.setStatus(WithdrawalStatus.APPLY.name());              // 状态由服务端置，不接受入参
        e.setAppliedAt(LocalDateTime.now().format(TS));
        e.setApplicantNo(SecurityUtils.userNo());
        mapper.insert(e);
        return toVO(require(e.getWithdrawNo()));
    }

    /**
     * 提现审核队列。原来这个列表在 {@code TradeController} 读内存 SeedData 并返回 6 字段的旧 VO，
     * 前端却要 fee/auditorName/auditedAt/rejectReason —— 迁来落库版后契约才对得上。
     */
    @Override
    public PageResult<Withdrawal> page(Integer page, Integer size, String keyword, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int sz = (size == null || size < 1) ? 10 : Math.min(size, 200);
        LambdaQueryWrapper<StlWithdrawal> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(StlWithdrawal::getWithdrawNo, keyword)
                    .or().like(StlWithdrawal::getPayeeName, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(StlWithdrawal::getStatus, status);
        w.orderByDesc(StlWithdrawal::getId);
        Page<StlWithdrawal> r = mapper.selectPage(new Page<>(p, sz), w);
        return new PageResult<>(r.getRecords().stream().map(WithdrawalServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    @Transactional
    public Withdrawal audit(String withdrawNo, boolean approve, String rejectReason) {
        StlWithdrawal e = require(withdrawNo);

        // —— 合规下界：驳回必须留原因，没有原因的驳回等于没有审批记录 ——
        if (!approve && (rejectReason == null || rejectReason.isBlank())) {
            throw new IllegalArgumentException("驳回提现必须填写驳回原因");
        }

        e.setStatus(stateMachine.next(e.getStatus(), approve ? "APPROVE" : "REJECT"));

        // —— 审批人一律服务端回填（不读入参），保证留痕不可伪造 ——
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        e.setAuditorNo(Optional.ofNullable(u).map(LoginUser::userNo).orElse(null));
        e.setAuditorName(Optional.ofNullable(u).map(LoginUser::username).orElse(null));
        e.setAuditedAt(LocalDateTime.now().format(TS));
        e.setRejectReason(approve ? null : rejectReason.trim());

        mapper.updateById(e);
        return toVO(require(withdrawNo));
    }

    private StlWithdrawal require(String withdrawNo) {
        StlWithdrawal e = mapper.selectOne(new LambdaQueryWrapper<StlWithdrawal>()
                .eq(StlWithdrawal::getWithdrawNo, withdrawNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("提现单不存在: " + withdrawNo);
        return e;
    }

    private static Withdrawal toVO(StlWithdrawal e) {
        BigDecimal amount = e.getAmount() == null ? BigDecimal.ZERO : e.getAmount();
        BigDecimal fee = e.getFee() == null ? BigDecimal.ZERO : e.getFee();
        return new Withdrawal(e.getWithdrawNo(), e.getAccountNo(), e.getPayeeType(), e.getPayeeNo(),
                e.getPayeeName(), amount, fee,
                amount.subtract(fee),   // 实际到账 = amount - fee，派生值不落库（[db-design §5.5]）
                e.getCurrency(), e.getBankCode(), e.getStatus(), e.getAppliedAt(), e.getApplicantNo(),
                e.getAuditorName(), e.getAuditedAt(), e.getRejectReason(), e.getPaidAt());
    }
}
