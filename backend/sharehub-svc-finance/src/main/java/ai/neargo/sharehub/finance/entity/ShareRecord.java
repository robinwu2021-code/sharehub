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

    /** 归属结算周期 YYYY-MM（V34：写入定格，不再由 created_at 现推）。 */
    private String period;

    /** 分润基数（GMV 快照，V34：不再由 amount/rate 反推 —— 固定额/阶梯分润反推必错）。 */
    private BigDecimal grossAmount;

    /**
     * 分成基数口径（V155，需求方案 D9）：{@link ai.neargo.sharehub.finance.ShareBaseMode} 的名字。
     * 空 = V155 之前生成，当时按含税实收。改口径只影响之后的订单，所以每笔都要自带口径。
     */
    private String baseMode;

    /** 折算所用税率，百分数口径（5.00 = 5%）。GROSS 口径或价外税时为 0。 */
    private BigDecimal taxRate;

    /** 分成基数：{@code amount = baseAmount × rate}（向下取整到分）。PRE_TAX 时为实收折回税前。 */
    private BigDecimal baseAmount;

    /**
     * 分成依据（V53 / ADR-027）：INVEST / DEVELOP / OPERATE / REFER。
     *
     * <p>一个代理在一个站点上可以既出资又运维，两笔钱的比例与去向都不同。
     * 没有这一列时它们只能合成一条，**结算争议里说不清「这 8% 里几个点是运维」**。
     *
     * <p>VENUE 维度留空串（维度本身即依据）。<b>不可为 null</b> ——
     * 幂等键 {@code uk_srec_order_payee_basis} 含本列，而唯一索引把 NULL 之间视为互不相同，
     * 可空就等于幂等保护形同虚设且不报错（V49 的 uk_scope_target 已实测踩过一次）。
     */
    private String basis;

    /**
     * 费率来源：合同号（VENUE）或规则号（AGENT）。V37。
     *
     * <p>排错时「这笔为什么是 15%」要能当场回答。只存 rate 不存来源的话，
     * 规则或合同一改，历史分润的依据就永远查不回来了。
     */
    private String sourceNo;

    // createdAt / updatedAt / version / deleted 见 BaseEntity，**不重复声明**（[SKELETON_BRIEF §4]）。
    // 统计口径里的 period（YYYY-MM）就是从 created_at 现算的，本表没有也不需要 period 列。
}
