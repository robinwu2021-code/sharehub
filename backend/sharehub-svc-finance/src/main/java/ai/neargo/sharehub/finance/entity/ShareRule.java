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

    /**
     * 分成依据（V53 / ADR-027）：INVEST / DEVELOP / OPERATE / REFER；VENUE 维度留空串。
     *
     * <p>同一个代理可以有多条规则，各对应一项责任 —— 「他出资拿 5%、运维拿 8%」。
     * 取价时按 {@code (dimension, payeeNo, basis)} 找，找不到再退回同分成方的通用规则。
     */
    private String basis;

    /**
     * CHANNEL_SPLIT（渠道直分）/ LEDGER（账务记账后结算），取值域 {@link ai.neargo.sharehub.api.common.ShareMode}。
     *
     * <p>⚠️ 这行注释此前**在 {@code basis} 上面**，被后加的 basis javadoc 挤成了孤儿 ——
     * 两个连续 javadoc 只有后一个生效，于是 {@code mode} 变成了「没有声明词表的列」，
     * 而它恰好就是当年落了 17 行 {@code RATE} 的那一列（V66 归一）。
     */
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
