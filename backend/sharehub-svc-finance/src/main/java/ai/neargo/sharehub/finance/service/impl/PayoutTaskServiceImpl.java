package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.dto.AgentBrief;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.PayoutTaskStatus;
import ai.neargo.sharehub.finance.SettlementStateMachine;
import ai.neargo.sharehub.finance.SettlementStatus;
import ai.neargo.sharehub.finance.WithdrawalOrigin;
import ai.neargo.sharehub.finance.WithdrawalStateMachine;
import ai.neargo.sharehub.finance.WithdrawalStatus;
import ai.neargo.sharehub.finance.dto.FinDtos.PayReceiptReq;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutConfirmItem;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutConfirmReq;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutExportFile;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutPreview;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutPreviewItem;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutTask;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutTaskItem;
import ai.neargo.sharehub.finance.entity.StlPayoutAccount;
import ai.neargo.sharehub.finance.entity.StlPayoutExport;
import ai.neargo.sharehub.finance.entity.StlPayoutTask;
import ai.neargo.sharehub.finance.entity.StlSettlement;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;
import ai.neargo.sharehub.finance.mapper.StlPayoutAccountMapper;
import ai.neargo.sharehub.finance.mapper.StlPayoutExportMapper;
import ai.neargo.sharehub.finance.mapper.StlPayoutTaskMapper;
import ai.neargo.sharehub.finance.mapper.StlSettlementMapper;
import ai.neargo.sharehub.finance.mapper.StlWithdrawalMapper;
import ai.neargo.sharehub.finance.service.PayoutTaskService;
import ai.neargo.sharehub.finance.service.WithdrawalService;
import ai.neargo.sharehub.identity.FieldCipher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 付款任务（V158，需求方案 §八 / D8 / D12；优化方案 §2.5）。
 *
 * <h2>几条不显然的</h2>
 * <ul>
 *   <li><b>金额只由服务端按结算单重算</b>：生成任务只收截止日，客户端传不了金额 —— 预览与生成之间结算单变了，
 *       以生成那一刻为准。</li>
 *   <li><b>防重付两道闸</b>：结算单上的 {@code withdraw_no} 非空即「在途或已付」，不会被纳入；
 *       同一时刻至多一个未结束的任务。回写结算单时按「withdraw_no 仍为空」做条件更新并核对行数，
 *       并发生成时后到的那个会整体回滚，而不是把同一张结算单付两次。</li>
 *   <li><b>四眼</b>：复核人不得是发起人；确认到账走现有 {@link WithdrawalService#pay}，
 *       「审批人不得兼放款人」由那里按系统配置拦。</li>
 *   <li><b>失败与撤回把结算单放回去</b>：清空 {@code withdraw_no}，下次任务自动带上。</li>
 *   <li><b>不收提现手续费</b>：这是平台主动付款，不是商家申请提现（优化方案 §八 待确认 1 的默认）。</li>
 * </ul>
 *
 * <p>记账（应付冲减）不在这里：结算单确认时目前也没有记「应付增加」，只记减少会让余额变负。
 * 两侧一起补，见优化方案差距 G19。
 */
@Service
public class PayoutTaskServiceImpl implements PayoutTaskService {

    private static final Logger log = LoggerFactory.getLogger(PayoutTaskServiceImpl.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String MERCHANT = "AGENT";
    private static final String ACCOUNT_ACTIVE = "ACTIVE";
    private static final String TENANT = "MAIN";

    /** 预览里「本次不能付款」的原因码（前端出文案；不在服务端拼中文，阿语/英语界面也要看得懂）。 */
    static final String NO_ACCOUNT = "NO_ACCOUNT";
    static final String NO_FULL_ACCOUNT = "NO_FULL_ACCOUNT";
    static final String SUSPENDED = "SUSPENDED";
    static final String MIXED_CURRENCY = "MIXED_CURRENCY";

    private final StlPayoutTaskMapper tasks;
    private final StlPayoutExportMapper exports;
    private final StlSettlementMapper settlements;
    private final StlWithdrawalMapper withdrawals;
    private final StlPayoutAccountMapper accounts;
    private final WithdrawalStateMachine withdrawalStates;
    private final SettlementStateMachine settlementStates;
    private final WithdrawalService withdrawalService;
    private final AgentDirectoryPort agents;
    private final FieldCipher cipher;

    public PayoutTaskServiceImpl(StlPayoutTaskMapper tasks, StlPayoutExportMapper exports,
                                 StlSettlementMapper settlements, StlWithdrawalMapper withdrawals,
                                 StlPayoutAccountMapper accounts, WithdrawalStateMachine withdrawalStates,
                                 SettlementStateMachine settlementStates, WithdrawalService withdrawalService,
                                 AgentDirectoryPort agents, FieldCipher cipher) {
        this.tasks = tasks;
        this.exports = exports;
        this.settlements = settlements;
        this.withdrawals = withdrawals;
        this.accounts = accounts;
        this.withdrawalStates = withdrawalStates;
        this.settlementStates = settlementStates;
        this.withdrawalService = withdrawalService;
        this.agents = agents;
        this.cipher = cipher;
    }

    // ── 预览 ────────────────────────────────────────────────────────────

    @Override
    public PayoutPreview preview(LocalDate cutoffDate) {
        return build(requireCutoff(cutoffDate)).preview;
    }

    /** 预览与生成共用一份口径：先按商家分组，再逐个判能不能付。 */
    private Plan build(LocalDate cutoff) {
        List<StlSettlement> due = settlements.selectList(new LambdaQueryWrapper<StlSettlement>()
                .eq(StlSettlement::getStatus, SettlementStatus.CONFIRMED.name())
                .eq(StlSettlement::getPayeeType, MERCHANT)
                .isNull(StlSettlement::getWithdrawNo)).stream()
                .filter(s -> s.getTotalAmount() != null && s.getTotalAmount().signum() > 0)
                .filter(s -> periodEnd(s.getPeriod()) != null && !periodEnd(s.getPeriod()).isAfter(cutoff))
                .toList();

        Map<String, List<StlSettlement>> byPayee = new TreeMap<>();
        for (StlSettlement s : due) byPayee.computeIfAbsent(s.getPayeeNo(), k -> new ArrayList<>()).add(s);

        Plan plan = new Plan();
        List<PayoutPreviewItem> items = new ArrayList<>();
        int payable = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, List<StlSettlement>> en : byPayee.entrySet()) {
            List<StlSettlement> group = en.getValue();
            group.sort((a, b) -> a.getPeriod().compareTo(b.getPeriod()));
            BigDecimal amount = group.stream().map(StlSettlement::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String currency = group.get(0).getCurrency();
            StlPayoutAccount acct = defaultAccount(en.getKey());

            String blocked = null;
            if (group.stream().anyMatch(s -> !currency.equals(s.getCurrency()))) blocked = MIXED_CURRENCY;
            else if (acct == null) blocked = NO_ACCOUNT;
            else if (acct.getAccountEnc() == null || acct.getAccountEnc().length == 0) blocked = NO_FULL_ACCOUNT;
            else if (suspended(en.getKey())) blocked = SUSPENDED;

            items.add(new PayoutPreviewItem(en.getKey(), group.get(0).getPayeeName(),
                    group.stream().map(StlSettlement::getSettleNo).toList(),
                    group.stream().map(StlSettlement::getPeriod).toList(),
                    amount, currency, acct == null ? null : acct.getAccountMasked(), blocked));
            if (blocked == null) {
                payable++;
                total = total.add(amount);
                plan.payable.put(en.getKey(), new PlanItem(group, amount, currency, acct));
            }
        }
        String currency = plan.payable.values().stream().map(PlanItem::currency).findFirst().orElse("AED");
        plan.preview = new PayoutPreview(cutoff.toString(), currency, payable, total, items);
        return plan;
    }

    // ── 生成 ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PayoutTask create(LocalDate cutoffDate) {
        LocalDate cutoff = requireCutoff(cutoffDate);
        Long open = tasks.selectCount(new LambdaQueryWrapper<StlPayoutTask>()
                .in(StlPayoutTask::getStatus, PayoutTaskStatus.PENDING_REVIEW.name(), PayoutTaskStatus.PENDING_TRANSFER.name()));
        if (open != null && open > 0) throw BizException.conflict("error.payout.task_open");

        Plan plan = build(cutoff);
        if (plan.payable.isEmpty()) throw BizException.badRequest("error.payout.nothing_to_pay", cutoff.toString());
        if (plan.payable.values().stream().map(PlanItem::currency).distinct().count() > 1) {
            // 一个任务对应一次银行批量转账，混币种的批次银行不收
            throw BizException.badRequest("error.payout.mixed_currency");
        }

        LoginUser u = SecurityUtils.currentUser().orElse(null);
        StlPayoutTask t = new StlPayoutTask();
        t.setTaskNo(FinNos.nextNo(tasks, "task_no", BizKey.PAYOUT_TASK, 6));
        t.setTenantId(TENANT);
        t.setCutoffDate(cutoff);
        t.setCurrency(plan.preview.currency());
        t.setItemCount(plan.payable.size());
        t.setTotalAmount(plan.preview.payableTotal());
        t.setStatus(PayoutTaskStatus.PENDING_REVIEW.name());
        t.setCreatedBy(u == null ? null : u.userNo());
        t.setCreatedName(u == null ? null : u.username());
        t.setExportCount(0);
        tasks.insert(t);

        for (Map.Entry<String, PlanItem> en : plan.payable.entrySet()) {
            PlanItem it = en.getValue();
            StlWithdrawal w = new StlWithdrawal();
            w.setTenantId(TENANT);
            w.setWithdrawNo(FinNos.nextNo(withdrawals, "withdraw_no", BizKey.WITHDRAWAL, 6));
            w.setTaskNo(t.getTaskNo());
            w.setOrigin(WithdrawalOrigin.PAYOUT_TASK.name());
            w.setPayeeType(MERCHANT);
            w.setPayeeNo(en.getKey());
            w.setPayeeName(it.group().get(0).getPayeeName());
            w.setAmount(it.amount());
            w.setFee(BigDecimal.ZERO);
            w.setCurrency(it.currency());
            w.setBankCode(it.account().getBankCode());
            // 收款账户在生成那一刻快照：之后商家换卡，已出的转账文件与这笔记录不受影响
            w.setPayoutAccountNo(it.account().getAccountNo());
            w.setPayoutAccountName(it.account().getAccountName());
            w.setPayoutAccountMasked(it.account().getAccountMasked());
            w.setStatus(WithdrawalStatus.APPLY.name());
            w.setAppliedAt(LocalDateTime.now().format(TS));
            w.setApplicantNo(t.getCreatedBy());
            withdrawals.insert(w);

            List<String> nos = it.group().stream().map(StlSettlement::getSettleNo).toList();
            int n = settlements.update(null, new LambdaUpdateWrapper<StlSettlement>()
                    .in(StlSettlement::getSettleNo, nos).isNull(StlSettlement::getWithdrawNo)
                    .set(StlSettlement::getWithdrawNo, w.getWithdrawNo()));
            if (n != nos.size()) {
                // 预览之后、生成之前有别人把其中的结算单纳入了另一张单 —— 整体回滚，不付两次
                throw BizException.conflict("error.payout.settlement_taken", en.getKey());
            }
        }
        log.info("付款任务已生成 taskNo={} cutoff={} 笔数={} 合计={} {}", t.getTaskNo(), cutoff,
                t.getItemCount(), t.getTotalAmount(), t.getCurrency());
        return detail(t.getTaskNo());
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    @Override
    public PageResult<PayoutTask> page(Integer page, Integer size, String status) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 100);
        LambdaQueryWrapper<StlPayoutTask> w = new LambdaQueryWrapper<StlPayoutTask>().orderByDesc(StlPayoutTask::getId);
        if (status != null && !status.isBlank()) w.eq(StlPayoutTask::getStatus, PayoutTaskStatus.of(status).name());
        Page<StlPayoutTask> r = tasks.selectPage(new Page<>(p, s), w);
        // 列表不带明细：一个任务可能有上百笔，列表页用不着
        return new PageResult<>(r.getRecords().stream().map(t -> toVO(t, List.of())).toList(), r.getTotal());
    }

    @Override
    public PayoutTask detail(String taskNo) {
        StlPayoutTask t = require(taskNo);
        return toVO(t, itemsOf(t.getTaskNo()));
    }

    // ── 复核 ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PayoutTask audit(String taskNo, boolean approve, String rejectReason) {
        StlPayoutTask t = require(taskNo);
        requireStatus(t, PayoutTaskStatus.PENDING_REVIEW);
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        String reviewer = u == null ? null : u.userNo();
        if (reviewer != null && reviewer.equals(t.getCreatedBy())) {
            throw BizException.conflict("error.payout.self_review", taskNo);
        }
        if (!approve && (rejectReason == null || rejectReason.isBlank())) {
            throw BizException.badRequest("error.payout.reject_reason_required");
        }

        for (StlWithdrawal w : withdrawalsOf(taskNo)) {
            if (approve && suspended(w.getPayeeNo())) {
                throw BizException.conflict("error.withdrawal.agent_suspended", w.getPayeeNo());
            }
            w.setStatus(withdrawalStates.next(w.getStatus(), approve ? "APPROVE" : "REJECT"));
            w.setAuditorNo(reviewer);
            w.setAuditorName(u == null ? null : u.username());
            w.setAuditedAt(LocalDateTime.now().format(TS));
            w.setRejectReason(approve ? null : rejectReason.trim());
            withdrawals.updateById(w);
            if (!approve) release(w.getWithdrawNo());
        }

        t.setStatus((approve ? PayoutTaskStatus.PENDING_TRANSFER : PayoutTaskStatus.CANCELLED).name());
        t.setReviewedBy(reviewer);
        t.setReviewedName(u == null ? null : u.username());
        t.setReviewedAt(LocalDateTime.now());
        t.setRejectReason(approve ? null : rejectReason.trim());
        tasks.updateById(t);
        log.info("付款任务复核 taskNo={} 结论={} 复核人={}", taskNo, approve ? "通过" : "驳回", reviewer);
        return detail(taskNo);
    }

    // ── 导出 ────────────────────────────────────────────────────────────

    /**
     * 银行批量转账文件（CSV，UTF-8 带 BOM —— Excel 直接打开不乱码）。
     *
     * <p>含完整 IBAN，所以：单独的权限码、每次导出落一条记录（人、时间、文件摘要）、文件不在服务器落盘。
     * 附言写提现单号，银行回单按行匹配时靠它。
     */
    @Override
    @Transactional
    public PayoutExportFile export(String taskNo) {
        StlPayoutTask t = require(taskNo);
        requireStatus(t, PayoutTaskStatus.PENDING_TRANSFER);

        StringBuilder csv = new StringBuilder("﻿");
        csv.append("seq,withdraw_no,beneficiary_name,bank_code,iban,amount,currency,reference\r\n");
        int seq = 0;
        for (StlWithdrawal w : withdrawalsOf(taskNo)) {
            seq++;
            if (!WithdrawalStatus.PAYING.name().equals(w.getStatus())) continue;
            StlPayoutAccount acct = accounts.selectOne(new LambdaQueryWrapper<StlPayoutAccount>()
                    .eq(StlPayoutAccount::getAccountNo, w.getPayoutAccountNo()).last("limit 1"));
            String iban = acct == null || acct.getAccountEnc() == null ? null : cipher.decrypt(acct.getAccountEnc());
            if (iban == null) throw BizException.badRequest("error.payout.account_incomplete", w.getWithdrawNo());
            csv.append(seq).append(',')
                    .append(cell(w.getWithdrawNo())).append(',')
                    .append(cell(w.getPayoutAccountName())).append(',')
                    .append(cell(w.getBankCode())).append(',')
                    .append(cell(iban)).append(',')
                    .append(w.getAmount().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()).append(',')
                    .append(cell(w.getCurrency())).append(',')
                    .append(cell(w.getWithdrawNo())).append("\r\n");
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        LoginUser u = SecurityUtils.currentUser().orElse(null);
        StlPayoutExport e = new StlPayoutExport();
        e.setTaskNo(taskNo);
        e.setFormat("CSV");
        e.setFileSha256(sha256(bytes));
        e.setExportedBy(u == null ? null : u.userNo());
        e.setExportedName(u == null ? null : u.username());
        e.setExportedAt(LocalDateTime.now());
        e.setTenantId(TENANT);
        exports.insert(e);
        t.setExportCount((t.getExportCount() == null ? 0 : t.getExportCount()) + 1);
        tasks.updateById(t);
        // 不打账号：日志不落敏感信息（CLAUDE.md 日志规范第 3 条）
        log.info("付款任务转账文件已导出 taskNo={} 第{}次 sha256={}", taskNo, t.getExportCount(), e.getFileSha256());
        return new PayoutExportFile("payout-" + taskNo + ".csv", "text/csv; charset=UTF-8", bytes);
    }

    // ── 确认到账 ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PayoutTask confirm(String taskNo, PayoutConfirmReq req) {
        StlPayoutTask t = require(taskNo);
        requireStatus(t, PayoutTaskStatus.PENDING_TRANSFER);
        if (req == null || req.bankBatchRef() == null || req.bankBatchRef().isBlank()) {
            throw BizException.badRequest("error.common.missing_parameter", "bankBatchRef");
        }
        LocalDate transferDate = parseDate(req.transferDate(), "transferDate");

        List<StlWithdrawal> all = withdrawalsOf(taskNo);
        Map<String, Integer> seqOf = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) seqOf.put(all.get(i).getWithdrawNo(), i + 1);

        // 没传逐笔结果 = 整批成功；传了 = 只处理列出的那几笔
        List<PayoutConfirmItem> results = req.results() == null || req.results().isEmpty()
                ? all.stream().filter(w -> WithdrawalStatus.PAYING.name().equals(w.getStatus()))
                     .map(w -> new PayoutConfirmItem(w.getWithdrawNo(), true, null, null)).toList()
                : req.results();

        for (PayoutConfirmItem r : results) {
            Integer seq = seqOf.get(r.withdrawNo());
            if (seq == null) throw BizException.badRequest("error.payout.not_in_task", r.withdrawNo(), taskNo);
            boolean ok = !Boolean.FALSE.equals(r.success());
            String ref = ok && (r.payRef() == null || r.payRef().isBlank()) ? taskNo + "-" + seq : r.payRef();
            // 走现有打款登记：状态机、流水号唯一、「审批人不得兼放款人」都在那里，不另写一份
            withdrawalService.pay(r.withdrawNo(), new PayReceiptReq(ok, "MANUAL", ok ? ref : null, ok ? null : r.failReason()));
            if (ok) markSettlementsPaid(r.withdrawNo());
            else release(r.withdrawNo());
        }

        boolean stillPaying = withdrawalsOf(taskNo).stream()
                .anyMatch(w -> WithdrawalStatus.PAYING.name().equals(w.getStatus()));
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        t.setBankBatchRef(req.bankBatchRef().trim());
        t.setTransferDate(transferDate);
        t.setConfirmedBy(u == null ? null : u.userNo());
        t.setConfirmedAt(LocalDateTime.now());
        if (!stillPaying) t.setStatus(PayoutTaskStatus.DONE.name());
        tasks.updateById(t);
        log.info("付款任务确认到账 taskNo={} 本次处理 {} 笔，{}", taskNo, results.size(), stillPaying ? "仍有未确认" : "已全部完成");
        return detail(taskNo);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 到账：该提现单纳入的结算单置已付（CONFIRMED → PAID，经状态机）。 */
    private void markSettlementsPaid(String withdrawNo) {
        for (StlSettlement s : settlements.selectList(new LambdaQueryWrapper<StlSettlement>()
                .eq(StlSettlement::getWithdrawNo, withdrawNo))) {
            s.setStatus(settlementStates.next(s.getStatus(), "PAY"));
            settlements.updateById(s);
        }
    }

    /** 失败或撤回：结算单回到可付款范围。用条件更新显式置空 —— updateById 默认跳过 null 字段，那样写等于没写。 */
    private void release(String withdrawNo) {
        settlements.update(null, new LambdaUpdateWrapper<StlSettlement>()
                .eq(StlSettlement::getWithdrawNo, withdrawNo).set(StlSettlement::getWithdrawNo, null));
    }

    private List<StlWithdrawal> withdrawalsOf(String taskNo) {
        return withdrawals.selectList(new LambdaQueryWrapper<StlWithdrawal>()
                .eq(StlWithdrawal::getTaskNo, taskNo).orderByAsc(StlWithdrawal::getId));
    }

    private List<PayoutTaskItem> itemsOf(String taskNo) {
        List<StlWithdrawal> ws = withdrawalsOf(taskNo);
        List<PayoutTaskItem> out = new ArrayList<>();
        for (int i = 0; i < ws.size(); i++) {
            StlWithdrawal w = ws.get(i);
            List<String> settleNos = settlements.selectList(new LambdaQueryWrapper<StlSettlement>()
                    .eq(StlSettlement::getWithdrawNo, w.getWithdrawNo())).stream().map(StlSettlement::getSettleNo).toList();
            out.add(new PayoutTaskItem(i + 1, w.getWithdrawNo(), w.getPayeeNo(), w.getPayeeName(), w.getAmount(),
                    w.getCurrency(), w.getPayoutAccountName(), w.getPayoutAccountMasked(), w.getStatus(),
                    settleNos, w.getPayRef(), w.getPaidAt(),
                    w.getFailReason() != null ? w.getFailReason() : w.getRejectReason()));
        }
        return out;
    }

    private StlPayoutAccount defaultAccount(String payeeNo) {
        return accounts.selectOne(new LambdaQueryWrapper<StlPayoutAccount>()
                .eq(StlPayoutAccount::getPayeeType, MERCHANT).eq(StlPayoutAccount::getPayeeNo, payeeNo)
                .eq(StlPayoutAccount::getStatus, ACCOUNT_ACTIVE).eq(StlPayoutAccount::getIsDefault, 1)
                // limit 1 必须带确定排序（同 PayoutAccountServiceImpl.defaultOf）：否则「今天打给这张卡、明天打给那张」
                .orderByAsc(StlPayoutAccount::getId).last("limit 1"));
    }

    /** 停用的商家不付款；清退「结清中」例外 —— 这一步就是要把钱结给它（同 WithdrawalServiceImpl）。 */
    private boolean suspended(String payeeNo) {
        AgentBrief a = agents.briefOf(payeeNo);
        return a != null && !a.enabled() && !a.settlingExit();
    }

    private StlPayoutTask require(String taskNo) {
        StlPayoutTask t = tasks.selectOne(new LambdaQueryWrapper<StlPayoutTask>()
                .eq(StlPayoutTask::getTaskNo, taskNo).last("limit 1"));
        if (t == null) throw BizException.notFound(taskNo);
        return t;
    }

    private static void requireStatus(StlPayoutTask t, PayoutTaskStatus want) {
        if (!want.name().equals(t.getStatus())) {
            throw BizException.conflict("error.payout.bad_status", t.getTaskNo(), t.getStatus());
        }
    }

    private static LocalDate requireCutoff(LocalDate d) {
        if (d == null) throw BizException.badRequest("error.common.missing_parameter", "cutoffDate");
        return d;
    }

    private static LocalDate parseDate(String v, String name) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.common.missing_parameter", name);
        try {
            return LocalDate.parse(v.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw BizException.badRequest("error.common.invalid_value", v);
        }
    }

    /** 账期 YYYY-MM 的最后一天；格式不对返回 null（这张单不纳入，而不是让整个预览失败）。 */
    static LocalDate periodEnd(String period) {
        try {
            return YearMonth.parse(period).atEndOfMonth();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** CSV 单元格：含逗号、引号、换行的加引号并转义；以 = + - @ 开头的加单引号前缀，防表格公式注入。 */
    private static String cell(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PayoutTask toVO(StlPayoutTask t, List<PayoutTaskItem> items) {
        return new PayoutTask(t.getTaskNo(), str(t.getCutoffDate()), t.getCurrency(),
                t.getItemCount() == null ? 0 : t.getItemCount(), t.getTotalAmount(), t.getStatus(),
                t.getCreatedBy(), t.getCreatedName(), str(t.getCreatedAt()),
                t.getReviewedName(), str(t.getReviewedAt()), t.getRejectReason(),
                t.getBankBatchRef(), str(t.getTransferDate()), str(t.getConfirmedAt()),
                t.getExportCount() == null ? 0 : t.getExportCount(), items);
    }

    private static String str(Object o) {
        return Optional.ofNullable(o).map(Object::toString).orElse(null);
    }

    private record PlanItem(List<StlSettlement> group, BigDecimal amount, String currency, StlPayoutAccount account) {
    }

    private static final class Plan {
        final Map<String, PlanItem> payable = new LinkedHashMap<>();
        PayoutPreview preview;
    }
}
