package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.finance.WithdrawalStatus;

import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.WithdrawFeePolicy;
import ai.neargo.sharehub.finance.WithdrawalStateMachine;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutAccount;
import ai.neargo.sharehub.finance.service.PayoutAccountService;
import ai.neargo.sharehub.finance.dto.FinDtos.PayReceiptReq;
import ai.neargo.sharehub.finance.dto.FinDtos.WithdrawApplyReq;
import ai.neargo.sharehub.finance.dto.FinDtos.Withdrawal;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;
import ai.neargo.sharehub.finance.mapper.StlWithdrawalMapper;
import ai.neargo.sharehub.finance.service.WithdrawalService;
import ai.neargo.common.core.PageResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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

    /** 打款渠道白名单。MANUAL = 人工转账后回填（nearpay 未接前的唯一真实路径）。 */
    private static final java.util.Set<String> PAY_CHANNELS = java.util.Set.of("NEARPAY", "MANUAL");

    private final StlWithdrawalMapper mapper;
    private final WithdrawFeePolicy feePolicy;
    private final WithdrawalStateMachine stateMachine;
    private final PayoutAccountService payoutAccounts;
    private final ai.neargo.sharehub.api.platform.port.AgentDirectoryPort agents;

    public WithdrawalServiceImpl(StlWithdrawalMapper mapper, WithdrawFeePolicy feePolicy,
                                 WithdrawalStateMachine stateMachine,
                                 PayoutAccountService payoutAccounts,
                                 ai.neargo.sharehub.api.platform.port.AgentDirectoryPort agents) {
        this.agents = agents;
        this.mapper = mapper;
        this.feePolicy = feePolicy;
        this.stateMachine = stateMachine;
        this.payoutAccounts = payoutAccounts;
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

        requireAgentNotSuspended(req.payeeType(), req.payeeNo());
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
    public PageResult<Withdrawal> page(Integer page, Integer size, String keyword, String status, String payeeNo) {
        int p = (page == null || page < 1) ? 1 : page;
        int sz = (size == null || size < 1) ? 10 : Math.min(size, 200);
        LambdaQueryWrapper<StlWithdrawal> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(StlWithdrawal::getWithdrawNo, keyword)
                    .or().like(StlWithdrawal::getPayeeName, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(StlWithdrawal::getStatus, status);
        // 按收款方**编号**精确筛。keyword 只匹配单号与 payeeName，而同名代理不罕见 ——
        // 前端原先的兜底是「按名字取一页，再在页内按编号过滤」，那会丢行：
        // 翻页按名字翻，落在当前页之外的同名记录根本取不回来，
        // 于是「这个代理的提现」少了几笔，界面上看不出少了。total 同理是假的。
        if (payeeNo != null && !payeeNo.isBlank()) w.eq(StlWithdrawal::getPayeeNo, payeeNo.trim());
        w.orderByDesc(StlWithdrawal::getId);
        Page<StlWithdrawal> r = mapper.selectPage(new Page<>(p, sz), w);
        return new PageResult<>(r.getRecords().stream().map(WithdrawalServiceImpl::toVO).toList(), r.getTotal());
    }

    /**
     * 代理停用冻结提现（对齐清单 F1 · E8）：停用期间不能申请、不能审批通过、不能确认打款 ——
     * 已提交的单停在原状态即「挂起」，恢复启用后继续走。分润照常计提，钱没丢，只是暂时拿不走。
     */
    private void requireAgentNotSuspended(String payeeType, String payeeNo) {
        if (!"AGENT".equalsIgnoreCase(payeeType) || payeeNo == null) return;
        var a = agents.briefOf(payeeNo);
        // 清退「结清中」例外：这一步就是要把钱结给它，冻着就永远结不清
        if (a != null && !a.enabled() && !a.settlingExit()) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.withdrawal.agent_suspended", payeeNo);
        }
    }

    @Override
    @Transactional
    public Withdrawal audit(String withdrawNo, boolean approve, String rejectReason) {
        StlWithdrawal e = require(withdrawNo);
        if (approve) requireAgentNotSuspended(e.getPayeeType(), e.getPayeeNo());   // 驳回照常可做：停用期间的单可以直接驳掉

        // —— 合规下界：驳回必须留原因，没有原因的驳回等于没有审批记录 ——
        if (!approve && (rejectReason == null || rejectReason.isBlank())) {
            throw new IllegalArgumentException("驳回提现必须填写驳回原因");
        }

        /*
         * —— 通过前先确认「钱打得出去」（B3）——
         *
         * 在此之前这一步是缺的：审批照样通过，而收款账户压根没录 —— 审批完不知道往哪打钱。
         * 这正是 ADR-030 §3.6 那条「审核通过 ≠ 能拿钱」：
         * agt_agent.status=ENABLED 只说明能经营，能不能收钱要看这里。
         *
         * **拦在审批这一步，而不是打款那一步**：打款时才发现的话，这笔提现已经对用户
         * 显示「已通过」了，再退回去解释一遍代价大得多。
         */
        if (approve) {
            PayoutAccount acct = payoutAccounts.defaultOf(e.getPayeeType(), e.getPayeeNo())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "该受益方还没有可用的收款账户，无法通过 —— 请先补录收款账户"));
            /*
             * 落**快照**而不是只存引用：账户日后改名或换卡，这笔已审批的提现要能对得上
             * 当初的打款回单（本仓既有的快照原则，领域模型 §2.3）。
             * 引用也一并留着 —— 否则查不到「这笔打给的是哪条账户记录」。
             */
            e.setPayoutAccountNo(acct.accountNo());
            e.setPayoutAccountName(acct.accountName());
            e.setPayoutAccountMasked(acct.accountMasked());
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

    @Override
    @Transactional
    public Withdrawal pay(String withdrawNo, PayReceiptReq req) {
        if (req == null || req.success() == null) {
            throw new IllegalArgumentException("请说明打款结果：成功还是失败");
        }
        StlWithdrawal e = require(withdrawNo);
        boolean ok = Boolean.TRUE.equals(req.success());
        if (ok) requireAgentNotSuspended(e.getPayeeType(), e.getPayeeNo());

        /*
         * —— 成功必须带渠道流水号 ——
         * 「已到账」这三个字在对账时要能被证实。没有流水号的话，事后只有一句人说的话，
         * 既对不上银行回单，出了纠纷也无从查起。**失败则不强制**：
         * 有些失败（余额不足、账号不存在）根本没产生流水。
         */
        if (ok && (req.payRef() == null || req.payRef().isBlank())) {
            throw new IllegalArgumentException("登记到账必须填写渠道流水号（银行回单号 / nearpay 打款单号）");
        }
        if (!ok && (req.failReason() == null || req.failReason().isBlank())) {
            throw new IllegalArgumentException("登记打款失败必须填写失败原因");
        }

        // 非法迁移由状态机拦（例如对 APPLY/PAID 的单子登记回执）——
        // 在这里抛，而不是让 UPDATE 静默改掉一条不该动的记录
        e.setStatus(stateMachine.next(e.getStatus(), ok ? "PAY" : "FAIL"));

        String channel = (req.channel() == null || req.channel().isBlank())
                ? "MANUAL" : req.channel().trim().toUpperCase(Locale.ROOT);
        if (!PAY_CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("打款渠道非法: " + channel + "（仅 " + PAY_CHANNELS + "）");
        }
        e.setPayChannel(channel);
        e.setPayRef(req.payRef() == null || req.payRef().isBlank() ? null : req.payRef().trim());

        // 登记人服务端回填，且**与审批人分列** —— 查「谁批的、谁放的款」不用翻操作日志
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        e.setPayerNo(Optional.ofNullable(u).map(LoginUser::userNo).orElse(null));
        e.setPayerName(Optional.ofNullable(u).map(LoginUser::username).orElse(null));

        if (ok) {
            e.setPaidAt(LocalDateTime.now().format(TS));
            e.setFailReason(null);
        } else {
            e.setFailReason(req.failReason().trim());
        }

        /*
         * 撞唯一键 uk_stl_withdrawal_payref = 这笔渠道流水已经记在另一张单上。
         * **必须翻译成人看得懂的话**：裸的 DuplicateKeyException 会变成 500「服务器错误」，
         * 而这恰恰是对账时最需要看清的一条 —— 财务得知道是「流水号填重了」，
         * 而不是以为系统坏了、换个浏览器再试一遍。
         */
        try {
            mapper.updateById(e);
        } catch (DuplicateKeyException dup) {
            throw new IllegalArgumentException(
                    "该渠道流水号已登记在另一张提现单上，请核对后重填：" + e.getPayRef());
        }
        return toVO(require(withdrawNo));
    }

    private StlWithdrawal require(String withdrawNo) {
        StlWithdrawal e = mapper.selectOne(new LambdaQueryWrapper<StlWithdrawal>()
                .eq(StlWithdrawal::getWithdrawNo, withdrawNo).last("limit 1"));
        if (e == null) throw BizException.notFound(withdrawNo);
        return e;
    }

    private static Withdrawal toVO(StlWithdrawal e) {
        BigDecimal amount = e.getAmount() == null ? BigDecimal.ZERO : e.getAmount();
        BigDecimal fee = e.getFee() == null ? BigDecimal.ZERO : e.getFee();
        return new Withdrawal(e.getWithdrawNo(), e.getAccountNo(), e.getPayeeType(), e.getPayeeNo(),
                e.getPayeeName(), amount, fee,
                amount.subtract(fee),   // 实际到账 = amount - fee，派生值不落库（[db-design §5.5]）
                e.getCurrency(), e.getBankCode(), e.getStatus(), e.getAppliedAt(), e.getApplicantNo(),
                e.getAuditorName(), e.getAuditedAt(), e.getRejectReason(), e.getPaidAt(),
                e.getPayChannel(), e.getPayRef(), e.getPayerName(), e.getFailReason());
    }
}
