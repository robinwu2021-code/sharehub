package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账批次（recon_task，[db-design §5.5]）—— <b>三方核对</b>：
 * nearpay 渠道账单 ↔ 本域支付引用 {@code pay_order} ↔ 账务分录 {@code acct_ledger}。
 *
 * <p>{@link #nearpayTotal} 是渠道侧金额，{@link #ledgerTotal} 是账务侧金额，
 * {@link #diff} = 两者之差；{@code diff != 0} 则 {@code status=DIFF}，差错逐笔落 {@link ReconDiff}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("recon_task")
public class ReconTask extends BaseEntity {

    private String batchNo;

    /** 支付渠道码 {@code pay_channel.channel_code}。 */
    private String channel;

    /** 账期 {@code YYYY-MM}。 */
    private String period;

    /** 账单日 {@code YYYY-MM-DD}。 */
    private String billDate;

    private BigDecimal nearpayTotal;

    private BigDecimal ledgerTotal;

    private BigDecimal diff;

    private String currency;

    /** MATCHED（平）/ DIFF（有差错）。 */
    private String status;

    // —— 差错处置留痕（V31）：status 是跑批事实（只读），以下是人工处置进度 ——

    /** OPEN / HANDLING / RESOLVED / IGNORED；已平批次为 null（无差错可处理）。 */
    private String handleStatus;

    /** VERIFIED_OK / PLATFORM_ERROR / CHANNEL_ERROR / COMPENSATED。 */
    private String handleResult;

    /** 处置说明（金额/凭证号/对接人），处置时必填。 */
    private String handleNote;

    private String handledBy;

    private LocalDateTime handledAt;
}
