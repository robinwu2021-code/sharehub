package ai.neargo.sharehub.user.asset.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 充值订单（usr_recharge_order，[db-design §6.2]）。运营端归「财务 · 用户账」分组。
 *
 * <p><b>{@code pspTxnNo} 不是 {@code psgTxnNo}</b>：前端 {@code RechargeOrder.psgTxnNo} 是笔误，
 * [db-design §6.2] 明确「全库统一 {@code psp_}」（对齐 {@code ord_refund.psp_txn_no}），以后端为准。
 *
 * <p>日期范围筛选一律以 {@code createdAt} 为准 —— {@code PENDING}/{@code FAILED} 单没有 {@code paidAt}，
 * 按 {@code paidAt} 筛会把未支付单整段漏掉。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_recharge_order")
public class UsrRechargeOrder extends BaseEntity {

    private String rechargeNo;

    private String regionId;

    private String cUserNo;

    /** 昵称快照（不回溯），列表直出免得再跳用户页。 */
    private String nickname;

    /** 空 = 自定义金额充值。 */
    private String packageNo;

    private BigDecimal payAmount;

    private BigDecimal giftAmount;

    /** 实际入账 = pay + gift。 */
    private BigDecimal creditAmount;

    private String currency;

    /** → {@code pay_channel.channel_code}。 */
    private String channelCode;

    /** PENDING / PAID / FAILED / REFUNDED。 */
    private String status;

    /** PSP 交易号；未支付为空。 */
    private String pspTxnNo;

    /** 空 = 尚未支付。 */
    private String paidAt;
}
