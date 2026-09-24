package ai.neargo.sharehub.finance.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * trade/finance 子域出参 VO。字段镜像 ops-web {@code lib/types/finance.ts} 的同名 interface。
 *
 * <p><b>约定</b>（[SKELETON_BRIEF §3]）：新域一律用域内 dto 文件，不往顶层 {@code dto/Dto.java} 追加
 * —— 那个文件是早期骨架的共享 DTO 集合，已冻结。顶层 {@code Dto} 里同名的
 * {@code ShareRule}/{@code Settlement}/{@code Withdrawal}/{@code LedgerEntry} 是**旧内存版**，
 * 由老 {@code TradeController} 的 seed 端点使用；本文件是落库版，两者不互相引用。
 *
 * <p>金额一律 {@link BigDecimal}（[db-design §1.5] {@code DECIMAL(18,2)}），
 * 比率 {@code rate} 是 0..1 的小数，时间统一 {@code String}。
 */
public final class FinDtos {

    private FinDtos() {
    }

    // ——————————————————————— 分润 ———————————————————————

    /** 分润规则行，镜像前端 {@code ShareRule}（+ 落库补的 payeeNo/formula/currency）。 */
    /**
     * 分润规则**写入参**（白名单）。
     *
     * <p>此前这两个端点直接收实体 {@code finance.entity.ShareRule} —— 它继承 BaseEntity，
     * 带着 {@code deleted} / {@code createdAt} / {@code createdBy}。而 saveRule 是**手写保存**，
     * 不走 AbstractCrudService，那次集中加固对它**不生效**：只回填了 id/version/tenantId，
     * 剩下三个客户端传什么就写什么（MyBatis-Plus 的 updateById 只写非 null 字段）。
     * 于是往编辑端点传 {@code {"deleted":1}} 能绕过归档语义软删一条规则，
     * 传 {@code createdBy} 能伪造审计痕迹 —— 而分润规则正是结算争议时要翻的那张表。
     *
     * <p>改成只声明**业务上真该由表单改的字段**：多传的键会被 Jackson 丢掉，
     * 不再需要逐个记得去锁（黑名单 → 白名单）。
     */
    public record ShareRuleReq(String ruleNo, String dimension, String payeeNo, String payeeName,
                               String basis, String mode, java.math.BigDecimal rate, String formula,
                               Integer priority, String currency) {
    }

    public record ShareRule(String ruleNo, String dimension, String payeeNo, String payeeName,
                            String basis, String mode, BigDecimal rate, Integer priority,
                            String formula, String currency) {
    }

    /** 分润明细行（逐单），镜像前端 {@code ShareRecord}。 */
    public record ShareRecord(String recordNo, String orderNo, String dimension, String payeeNo,
                              String payeeName, String basis, BigDecimal amount, BigDecimal rate,
                              String currency, String mode, String status, String settleNo,
                              String createdAt, String period, BigDecimal grossAmount) {
    }

    /**
     * 分润统计行，镜像前端 {@code ShareSummary}。**读模型，无对应表**
     * （[db-design §12.3]）：由 {@code share_record} 按 (dimension, payeeNo, period) 聚合。
     *
     * <p>{@code pendingAmount = shareAmount - settledAmount} 是派生值，不落库。
     */
    public record ShareSummary(String dimension, String payeeNo, String payeeName, String period,
                               Long orderCount, BigDecimal gmv, BigDecimal shareAmount,
                               BigDecimal settledAmount, BigDecimal pendingAmount, String currency) {
    }

    // ——————————————————————— 账务 ———————————————————————

    /** 账务分录行，镜像前端 {@code LedgerEntry}。只读 —— 分录不可改不可删。 */
    public record LedgerEntry(String entryNo, String voucherNo, String orderNo, String accountNo,
                              String account, String direction, BigDecimal amount, String currency,
                              String summary, String bizType, String bizNo, String createdAt) {
    }

    // ——————————————————————— 结算 / 提现 ———————————————————————

    /** 结算单行，镜像前端 {@code Settlement}：确认留痕 + 明细笔数（recordCount 为聚合非列）。 */
    public record Settlement(String settleNo, String payeeType, String payeeNo, String payeeName,
                             String period, BigDecimal totalAmount, String currency, String status,
                             String confirmedBy, String confirmedAt, String createdAt, Long recordCount) {

        /** 兼容旧 8 参调用（generate 等场景），留痕字段缺省 null。 */
        public Settlement(String settleNo, String payeeType, String payeeNo, String payeeName,
                          String period, BigDecimal totalAmount, String currency, String status) {
            this(settleNo, payeeType, payeeNo, payeeName, period, totalAmount, currency, status,
                    null, null, null, null);
        }
    }

    /** 结算明细行。 */
    public record SettlementDetail(String refType, String refNo, BigDecimal amount) {
    }

    /** 结算单详情 = 主单 + 明细（{@code GET /api/trade/settlements/{settleNo}} 的出参）。 */
    public record SettlementView(Settlement settlement, List<SettlementDetail> details) {
    }

    /**
     * 提现行，镜像前端 {@code Withdrawal}（含 {@code AuditTrail} 三件套 + {@code fee}）。
     *
     * <p>{@code netAmount}（实际到账）由 {@code amount - fee} 现算，**不落库**（[db-design §5.5]）。
     */
    public record Withdrawal(String withdrawNo, String accountNo, String payeeType, String payeeNo,
                             String payeeName, BigDecimal amount, BigDecimal fee, BigDecimal netAmount,
                             String currency, String bankCode, String status, String appliedAt,
                             String applicantNo, String auditorName, String auditedAt, String rejectReason,
                             String paidAt,
                             // —— 打款回执（V57）。列表要能一眼回答「钱到底出去没有、走的哪条道」——
                             String payChannel, String payRef, String payerName, String failReason) {
    }

    /**
     * 打款回执登记入参（必要功能清单 ⑮）。
     *
     * <p><b>为什么由人来登记，而不是等 nearpay 回调</b>：nearpay 未接（MVP 硬阻塞 2），
     * 而第一批提现是人工转账。回执入口与代付通道是**两件事**——通道通了以后，
     * 回调只是换一个调用方来调同一个服务方法，这里的状态机与幂等不用重写。
     *
     * @param success  true=已到账（→PAID）；false=打款失败（→FAILED）
     * @param channel  NEARPAY / MANUAL
     * @param payRef   渠道流水号；**成功时必填**，它是这笔钱在渠道侧的唯一凭据，也是重复登记的拦截键
     * @param failReason 失败原因；**失败时必填**
     */
    public record PayReceiptReq(Boolean success, String channel, String payRef, String failReason) {
    }

    // ——————————————————————— 对账 ———————————————————————

    /** 对账批次行，镜像前端 {@code Reconcile}：跑批事实（status）+ 人工处置留痕（handle*）。 */
    public record Reconcile(String batchNo, String channel, String period, String billDate,
                            BigDecimal nearpayTotal, BigDecimal ledgerTotal, BigDecimal diff,
                            String currency, String status, String createdAt,
                            String handleStatus, String handleResult, String handleNote,
                            String handledBy, String handledAt) {
    }

    /** 对账差错明细行。 */
    public record ReconDiffRow(Long id, String batchNo, String payNo, String diffType,
                               String detail, Boolean resolved) {
    }

    // ——————————————————————— 发票 ———————————————————————

    /** 发票行，镜像前端 {@code Invoice}（finance.ts）：来源单据 + 税局票号 + 开具/作废留痕全量出参。 */
    public record Invoice(String invoiceNo, String payeeType, String payeeNo, String payeeName,
                          BigDecimal amount, String vatTrn, String currency, String status,
                          String issuedAt, String fileUrl,
                          String sourceType, String sourceNo, String invoiceCode, String invoiceNumber,
                          String issuedBy, String voidedAt, String voidedBy, String voidReason) {
    }

    /** 发票详情 = 主单 + 关联订单号（来自 {@code fin_invoice_item}，非 JSON 列）。 */
    public record InvoiceView(Invoice invoice, List<String> orderNos) {
    }

    // ——————————————————————— 入参 ———————————————————————

    /**
     * 提现申请入参（{@code POST /api/trade/withdrawals}，代理端/场地方本人发起）。
     *
     * <p>**故意不含 fee/status/auditor***：手续费由 {@code WithdrawFeePolicy} 服务端算，
     * 状态由状态机置，审批人由会话回填 —— 这三类字段允许前端传就等于把资金审批交给了客户端。
     */
    public record WithdrawApplyReq(String accountNo, String payeeType, String payeeNo, String payeeName,
                                   BigDecimal amount, String currency, String bankCode) {
    }

    /** 发票保存入参：主单字段 + 关联订单号（拆行落 {@code fin_invoice_item}）。 */
    public record InvoiceSaveReq(String invoiceNo, String payeeType, String payeeNo, String payeeName,
                                 BigDecimal amount, String vatTrn, String currency, String status,
                                 List<String> orderNos) {
    }

    /** 一组待记账分录（同一凭证，必须借贷平衡）。 */
    public record LedgerPostReq(String voucherNo, String orderNo, String bizType, String bizNo,
                                String summary, List<LedgerLine> lines) {
    }

    /** 单条分录。{@code direction} 只接受 DEBIT / CREDIT。 */
    public record LedgerLine(String accountNo, String account, String direction,
                             BigDecimal amount, String currency) {
    }

    // —— 收款账户（B3）——

    /**
     * 收款账户出参。
     *
     * <p>⚠️ 只给掩码，<b>不给明文</b>：账号明文属于 PII，运营端列表没有展示它的理由。
     * 需要核对完整账号时走单独的、带审计的查询（尚未建，见 ADR-029 §七）。
     */
    public record PayoutAccount(String accountNo, String payeeType, String payeeNo,
                                String bankCode, String accountName, String accountMasked,
                                String currency, boolean isDefault, String status) {
    }

    /**
     * 收款账户入参。
     *
     * @param accountNo     留空 = 新增
     * @param accountMasked 账号。<b>调用方传明文，服务端只落掩码</b> —— 明文入 pii（PDPL）；
     *                      这个字段名保留 masked 是为了和出参对称，避免两个名字指同一件事
     * @param makeDefault   置为默认；同一受益方其它账户会在同一事务里被清零
     */
    public record PayoutAccountReq(String accountNo, String payeeType, String payeeNo,
                                   String bankCode, String accountName, String accountMasked,
                                   String currency, Boolean makeDefault) {
    }
}