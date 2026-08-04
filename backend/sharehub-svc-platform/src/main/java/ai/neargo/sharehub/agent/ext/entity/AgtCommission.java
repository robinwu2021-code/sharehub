package ai.neargo.sharehub.agent.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 代理分润配置（agt_commission，[db-design §3.5]）。
 *
 * <p><b>{@link #dimension} 决定哪一列有效，两列不可同时生效</b>：
 * <ul>
 *   <li>{@code GMV} → 用 {@link #rate}（{@code DECIMAL(5,4)}，取值 0..1，[db-design §1.5] 小数比率）；</li>
 *   <li>{@code ORDER_COUNT} → 用 {@link #fixedAmount} + {@link #currency}（单均固定额）。</li>
 * </ul>
 * 校验在 {@code AgentCommissionServiceImpl} 的 {@code beforeCreate}/{@code beforeUpdate}：
 * 维度对应的字段为空直接拒。放任空值落库的后果是结算时才炸，且那时已经算错了钱。
 *
 * <p>{@link #currency} 是 [db-design §1.5] 「金额列必带币种」的补齐项 —— v1 前端
 * {@code AgentCommission} 无币种，多国开城后无币种的金额是脏数据。
 *
 * <p>业务键前缀 {@code AC}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agt_commission")
public class AgtCommission extends BaseEntity {

    private String ruleNo;

    private String agentNo;

    /** 冗余展示名（写入时快照，不随源改名回溯）。 */
    private String agentName;

    /**
     * GMV / ORDER_COUNT。
     * <p>注意前端 {@code AgentCommission} 里这个字段叫 {@code basis}，库列名是 {@code dimension}
     * （[db-design §3.5]）；转换在 VO 层做，不改库列名。
     */
    private String dimension;

    /** dimension=GMV 时生效：分润比例 0..1。 */
    private BigDecimal rate;

    /** dimension=ORDER_COUNT 时生效：单均固定额。 */
    private BigDecimal fixedAmount;

    private String currency;

    /** CHANNEL_SPLIT（支付渠道直分） / LEDGER（账务记账后结算）。 */
    private String mode;

    /** 生效日 {@code yyyy-MM-dd}（DDL DATE）。 */
    private String effectiveAt;

    /** ACTIVE / INACTIVE。 */
    private String status;
}
