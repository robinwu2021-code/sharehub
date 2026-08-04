package ai.neargo.sharehub.user.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 钱包流水（usr_wallet_txn，[db-design §6.2]，C-WA-05）。
 *
 * <p><b>append 表</b>（月分区，财务保留 7 年）：只 INSERT 不 UPDATE/DELETE，
 * 故<b>不继承 {@code BaseEntity}</b>（无 {@code version}/{@code deleted}）。
 * 冲正走反向流水，不是改行。
 *
 * <p>{@code amount} <b>带符号</b>（与 {@code direction} 冗余但便于直接求和）：
 * {@code direction=IN} 为正、{@code OUT} 为负。
 */
@Data
@TableName("usr_wallet_txn")
public class UsrWalletTxn {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String txnNo;

    private String walletNo;

    /** 属主冗余列，供 C 端「只见自己」的属主索引 (wallet_no, c_user_no, created_at)。 */
    private String cUserNo;

    /** RECHARGE / SPEND / REFUND / BONUS。 */
    private String type;

    /** IN / OUT。 */
    private String direction;

    /** 展示标题（如「套餐充值」「租借扣费」）。 */
    private String title;

    /** 带符号金额，见类注释。 */
    private BigDecimal amount;

    private String currency;

    /** 关联业务类型（RENT_ORDER / RECHARGE / REFUND …）。 */
    private String bizType;

    /** 关联业务键。 */
    private String bizNo;

    private String createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
