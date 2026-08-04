package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 分润规则（share_rule，[db-design §5.4]）。
 *
 * <p><b>一张表 + {@code dimension} 维度切换</b>：VENUE（场地方）/ AGENT（代理商）共用本表，
 * 而不是按主体切两套表 —— 口径天然自洽，分润统计才能一次查完。
 *
 * <p>{@code rate} 是**小数比率 {@code DECIMAL(5,4)}，取值 0..1**（[db-design §1.5] 「{@code *_rate} = 小数」），
 * 与百分数列 {@code *_percent}（0..100）严格区分，别在这里存 30 表示 30%。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("share_rule")
public class ShareRule extends BaseEntity {

    private String ruleNo;

    /** VENUE / AGENT。 */
    private String dimension;

    private String payeeNo;

    private String payeeName;

    /** CHANNEL_SPLIT（渠道直分）/ LEDGER（账务记账后结算）。 */
    private String mode;

    /** 分成比率，0..1。 */
    private BigDecimal rate;

    /** 阶梯/复合规则表达式，JSON 文本；简单比例规则留空。 */
    private String formula;

    /** 命中优先级，数值小者先命中。 */
    private Integer priority;

    /** [db-design §1.5]：带金额语义的规则表一律补币种，多国开城后无币种的金额是脏数据。 */
    private String currency;
}
