package ai.neargo.sharehub.trade.order.dto;

import java.math.BigDecimal;

/**
 * trade/order 子域出参 VO + 入参请求体。
 *
 * <p><b>约定</b>（[骨架规约 §3]）：新域一律用域内 dto 文件，不往顶层 {@code dto/Dto.java} 追加
 * —— 那是早期骨架的共享 DTO 集合，已冻结。
 *
 * <p>出参字段**镜像 ops-web {@code lib/types/order.ts}** 的同名 interface，前端不需要任何适配层。
 * 两处刻意的命名差（与 DDL 列名不一致，见交付报告）：
 * <ul>
 *   <li>{@code c_user_no} → VO 里叫 {@code userNo}（前端全域统一用 userNo）；</li>
 *   <li>{@code ord_refund.psp_txn_no} → VO 里也叫 {@code pspTxnNo}。<b>2026-09-24 拉齐</b>：
 *       此前 VO 迁就前端的笔误写成 {@code psgTxnNo}，两边一起错所以对齐工具也看不出来；
 *       而同族的 {@code RechargeOrderRow} 用的是正确的 {@code pspTxnNo}，于是**那一处前端一直读到 undefined**。
 *       同一个字段两种拼法比错一次更贵，故全部收敛到 {@code psp_}（[db-design §6.2]）。</li>
 * </ul>
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    // ——————————————————————— 出参 VO ———————————————————————

    /** 异常订单行，镜像前端 {@code OrderException}。 */
    /** 异常单行，镜像前端 {@code OrderException}（含处置动作/结果与下游单据回填，V31）。 */
    public record OrderException(String exceptionNo, String orderNo, String type, String cabinetNo,
                                 String userNo, BigDecimal amount, String currency, String status,
                                 String handledBy, String handledAt, String createdAt,
                                 String handleAction, String handleResult,
                                 String refundNo, String workOrderNo) {
    }

    /** 投诉订单行，镜像前端 {@code OrderComplaint}（{@code wo_no} → {@code workOrderNo}）。 */
    public record OrderComplaint(String complaintNo, String orderNo, String userNo, String issueType,
                                 String description, String screenshotUrl, String submittedAt,
                                 String status, String handlerName, String handledAt,
                                 String resolution, String resolutionNote, String workOrderNo) {
    }

    /** 退款审批单行，镜像前端 {@code RefundRecord}（含 {@code AuditTrail} 三列）。 */
    public record RefundRecord(String refundNo, String orderNo, String userNo, BigDecimal amount,
                               String currency, String reason, String applicantName, String appliedAt,
                               String status, String idempotencyKey, String pspTxnNo,
                               String auditorName, String auditedAt, String rejectReason) {
    }

    /** 预约订单行，镜像前端 {@code Reservation}。 */
    public record Reservation(String reservationNo, String userNo, String type, String siteNo,
                              String siteName, String cabinetNo, String reservedFrom, String reservedTo,
                              BigDecimal holdFee, String currency, String status, String orderNo) {
    }

    /** 押金与欠费行，镜像前端 {@code DepositRecord}。 */
    public record DepositRecord(String depositNo, String orderNo, String userNo, BigDecimal amount,
                                String currency, String status, BigDecimal arrearsAmount,
                                String releasedAt, String createdAt,
                                BigDecimal buyoutAmount, String buyoutAt,
                                Integer dunCount, String lastDunAt, String lastDunChannel,
                                String operatorName, String note) {
    }

    /** 免费订单行，镜像前端 {@code FreeOrder}。数据源是 {@code ord_order}，不是独立表。 */
    public record FreeOrder(String orderNo, String userNo, String nickname, String whitelistReason,
                            BigDecimal waivedAmount, String currency, String siteName,
                            String cabinetNo, String startedAt, String endedAt, Integer duration) {
    }

    /**
     * 押金转买断入参（裸 Map → record，2026-09-26；见 known-map-request-bodies.txt）。
     *
     * <p><b>字段叫 {@code reason} 不叫 {@code note}</b>：前端 {@code DepositBuyoutPayload}
     * 发的是 {@code reason}，而端点此前读 {@code note} —— <b>买断原因静默丢弃</b>，
     * 而买断是没收用户押金，`ord_deposit.note` 是这笔钱唯一的说明。
     *
     * <p><b>刻意不含 {@code amount}</b>：买断金额 = 押金全额，由服务端定
     * （{@code DepositServiceImpl}：「不得超过押金额 —— 押金抵购机款，抵不了更多」）。
     * 前端原先有个 amount 输入框，填什么都不生效 —— 一个永远不生效的金额输入框
     * 比没有更糟，它让人以为可以少收或多收。
     */
    public record DepositBuyoutReq(String reason) {
    }

    /** 欠款催缴入参。{@code channel} 必选、{@code note} 可空（镜像前端 {@code ArrearsDunPayload}）。 */
    public record DepositDunReq(String channel, String note) {
    }

    /** 免费订单页头统计（成本管控口径，**全量**非当前页），镜像前端 {@code FreeOrderStats}。 */
    public record FreeOrderStats(long monthCount, BigDecimal waivedTotal, String currency) {
    }

    /** 订单状态时间线一条（{@code ord_event_log}），供订单详情页渲染。 */
    public record OrderEvent(String orderNo, String fromStatus, String toStatus, String event,
                             String operator, String createdAt) {
    }

    // ——————————————————————— 入参请求体 ———————————————————————

    /**
     * 异常处置。{@code handledBy}/{@code handledAt} **不在此列** —— 处置人由服务端按当前登录人回填。
     *
     * @param note 处置说明（写入时间线，不落 ord_exception）
     */
    /**
     * 异常处置入参（前端发 {@code {action, handleResult, ...}} 扁平体）。
     * {@code action}=work_order/refund/close；{@code refundNo/workOrderNo} 为已产出的下游单号回填。
     */
    public record ExceptionHandleReq(String note, String action, String handleResult,
                                     String refundNo, String workOrderNo) {
    }

    /** 投诉登记（客服代客登记电话/线下投诉；C 端提交走 {@code POST /mp/user/report} 派生）。 */
    public record ComplaintCreateReq(String orderNo, String userNo, String issueType,
                                     String description, String screenshotUrl) {
    }

    /**
     * 投诉处理。{@code handlerName}/{@code handledAt} 由服务端回填。
     *
     * @param resolution REFUND / COMPENSATE / REJECT / EXPLAINED
     */
    public record ComplaintHandleReq(String resolution, String resolutionNote) {
    }

    /**
     * 退款申请。
     *
     * @param idempotencyKey 幂等键，**必填**，由调用方生成（[db-design §1.6]）。空则 400，
     *                       服务端<b>不</b>自补：只有调用方知道「这是同一次用户点击」，
     *                       服务端派生的键必然次次不同，重试就会插出多条退款单 —— 钱退两次。
     */
    public record RefundApplyReq(String orderNo, String userNo, BigDecimal amount, String currency,
                                 String reason, String idempotencyKey) {
    }

    /**
     * 退款审批。
     *
     * <p>**不含审批人字段**：{@code auditorName}/{@code auditedAt} 一律由服务端按当前登录人回填，
     * 前端传什么都不采信 —— 资金审批的留痕不能由被审批方自述。
     *
     * @param approved     true=通过，false=驳回
     * @param rejectReason 驳回**必填**，为空抛 {@link IllegalArgumentException}
     */
    public record RefundAuditReq(Boolean approved, String rejectReason) {
    }
}
