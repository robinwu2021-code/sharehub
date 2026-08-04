package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 分润记录（share_record，[db-design §5.4]）—— 逐单一条。
 *
 * <p><b>「分润统计」不建表</b>（[db-design §12.3] 读模型）：{@code ShareSummary} 全部由本表
 * 按 (dimension, payee_no, period) 聚合得出，period 取自 {@code createdAt} 的 {@code YYYY-MM}。
 * 见 {@code ShareService#summaries}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("share_record")
public class ShareRecord extends BaseEntity {

    private String recordNo;

    private String orderNo;

    /** VENUE / AGENT，与 {@link ShareRule#getDimension()} 同枚举。 */
    private String dimension;

    /** 收款主体类型（与 dimension 同值域，冗余以便结算单直接取用）。 */
    private String payeeType;

    private String payeeNo;

    private String payeeName;

    private BigDecimal amount;

    /** 命中规则时的比率快照，0..1。规则改动不重算历史分润。 */
    private BigDecimal rate;

    private String currency;

    /** CHANNEL_SPLIT / LEDGER。 */
    private String mode;

    /** PENDING（待结算）/ DONE（已结算）。 */
    private String status;

    /** 结算后回填 {@code stl_settlement.settle_no}。 */
    private String settleNo;

    // createdAt / updatedAt / version / deleted 见 BaseEntity，**不重复声明**（[SKELETON_BRIEF §4]）。
    // 统计口径里的 period（YYYY-MM）就是从 created_at 现算的，本表没有也不需要 period 列。
}
