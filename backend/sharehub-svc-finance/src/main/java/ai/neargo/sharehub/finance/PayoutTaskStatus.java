package ai.neargo.sharehub.finance;

/**
 * 付款任务状态（{@code stl_payout_task.status}，V158）。
 *
 * <pre>
 *   PENDING_REVIEW --复核通过--> PENDING_TRANSFER --全部确认（到账或失败）--> DONE
 *   PENDING_REVIEW --复核驳回--> CANCELLED
 * </pre>
 * DONE 与 CANCELLED 是终态。同一时刻至多一个非终态任务（防同一批结算单被付两次之外的第二道闸）。
 */
public enum PayoutTaskStatus {
    PENDING_REVIEW,
    PENDING_TRANSFER,
    DONE,
    CANCELLED;

    public boolean open() {
        return this == PENDING_REVIEW || this == PENDING_TRANSFER;
    }

    public static PayoutTaskStatus of(String v) {
        try {
            return valueOf(v == null ? "" : v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ai.neargo.sharehub.common.BizException.badRequest("error.common.invalid_value", v);
        }
    }
}
