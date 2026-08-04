package ai.neargo.sharehub.trade.order.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 押金与欠费（{@code ord_deposit}，[db-design §5.1]）。
 *
 * <p>一单一押金（{@code uk_deposit_order} on {@code order_no}，ER: {@code ord_order 1─0..1 ord_deposit}）。
 * 状态 {@code HELD}(冻结) / {@code RELEASED}(已解冻) / {@code BOUGHT_OUT}(买断) / {@code ARREARS}(欠费)。
 * 手工解冻只允许从 {@code HELD} 出发 —— {@code BOUGHT_OUT}/{@code ARREARS} 的钱已进收入或待追偿，
 * 再解冻就是资金漏洞。
 *
 * <p>主键/租户/审计列见 {@link BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_deposit")
public class OrdDeposit extends BaseEntity {

    /** 业务键 UK，前缀 {@code DEP}。 */
    private String depositNo;

    private String regionId;

    /** UNIQUE —— 一单一押金。 */
    private String orderNo;

    private String cUserNo;

    /** 押金额。 */
    private BigDecimal amount;

    private String currency;

    /** HELD / RELEASED / BOUGHT_OUT / ARREARS */
    private String status;

    /** 欠费额（{@code status=ARREARS} 时有意义）。 */
    private BigDecimal arrearsAmount;

    /** 空 = 尚未释放。 */
    private String releasedAt;

    // ── 处置留痕：买断 / 催缴 ──

    /** 买断金额。**不得超过押金额** —— 押金抵购机款，抵不了更多。 */
    private java.math.BigDecimal buyoutAmount;
    private java.time.LocalDateTime buyoutAt;

    /** 催缴次数。**累加不覆盖** —— 催了几次是判断转买断/坏账的依据。 */
    private Integer dunCount;
    private java.time.LocalDateTime lastDunAt;
    private String lastDunChannel;

    /** 最近操作人。服务端取登录态，不信入参（与提现审批同一红线）。 */
    private String operatorName;
    private String note;
}
