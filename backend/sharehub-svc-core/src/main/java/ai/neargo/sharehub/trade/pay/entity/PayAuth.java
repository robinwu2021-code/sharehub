package ai.neargo.sharehub.trade.pay.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 免押编排状态（pay_auth，[db-design §5.3]，菜单：押金与欠费）。
 *
 * <p>实际冻结发生在 nearpay，本表只镜像编排状态：
 * FROZEN（已冻结）→ CAPTURED（已扣款）/ RELEASED（已释放）。
 * {@code capturedAmount} ≤ {@code freezeAmount}，超额扣款必须走新支付单而非改本表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_auth")
public class PayAuth extends BaseEntity {

    private String authNo;

    private String orderNo;

    /** db-design §5.3 关键列含 {@code c_user_no}；v1 DDL 未建，见交付报告。 */
    private String cUserNo;

    /** 冻结额。 */
    private BigDecimal freezeAmount;

    /** 已扣额。 */
    private BigDecimal capturedAmount;

    /** FROZEN / CAPTURED / RELEASED。 */
    private String status;

    /** nearpay 侧预授权引用。 */
    private String nearpayAuthNo;

    /** 冻结到期时刻（db-design §5.3 关键列；v1 DDL 未建，见交付报告）。 */
    private String expireAt;
}
