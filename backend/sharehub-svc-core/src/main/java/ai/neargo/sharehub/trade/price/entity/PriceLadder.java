package ai.neargo.sharehub.trade.price.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 计价阶梯（{@code price_ladder}）。区间语义 {@code [fromQty, toQty)}，
 * {@code toQty=null} 为无上限。<b>单段即「无阶梯」</b> ——
 * 不为简单场景另设一条无阶梯路径，一种表达方式减少分支。
 *
 * <p>{@code unitPrice} 是 {@code DECIMAL(18,4)}：电价常见 0.6543/度，两位小数不够用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_ladder")
public class PriceLadder extends BaseEntity {

    private String ladderNo;
    private String itemNo;
    private Integer seq;
    private BigDecimal fromQty;
    private BigDecimal toQty;
    private BigDecimal unitPrice;
}
