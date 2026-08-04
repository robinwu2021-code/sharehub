package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对账差错明细（recon_diff，[db-design §5.5]）—— {@link ReconTask} 的子表，无独立业务键。
 *
 * <p>{@code diffType} 典型取值：{@code ONLY_IN_NEARPAY}（渠道有我方无）/
 * {@code ONLY_IN_LEDGER}（我方有渠道无）/ {@code AMOUNT_MISMATCH}（两侧金额不等）/
 * {@code STATUS_MISMATCH}（状态不一致）。
 *
 * <p><b>平账处置只置 {@link #resolved} 标记，不修改任何历史分录</b> —— 账务侧的修正必须
 * 由一笔新的红冲分录表达（{@link AcctLedger} 是只增表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("recon_diff")
public class ReconDiff extends BaseEntity {

    private String batchNo;

    /** 支付引用 {@code pay_order.pay_no}。 */
    private String payNo;

    private String diffType;

    /** 差错上下文（两侧原始金额/状态），JSON 文本。 */
    private String detail;

    /** 0 未处置 / 1 已平账（TINYINT(1)，[SKELETON_BRIEF §4] 布尔列用 Integer）。 */
    private Integer resolved;
}
