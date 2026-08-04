package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 结算单（stl_settlement，[db-design §5.5]）。
 *
 * <p><b>没有人工创建入口</b>（[api/README §六·A]）：结算单只由周期批处理
 * {@code POST /internal/trade/settlements/generate} 产出，运营端只能查看与确认。
 * 状态 GEN → CONFIRMED → PAID，迁移由 {@code SettlementStateMachine} 把关。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stl_settlement")
public class StlSettlement extends BaseEntity {

    private String settleNo;

    /** VENUE / AGENT。 */
    private String payeeType;

    private String payeeNo;

    private String payeeName;

    /** 账期 {@code YYYY-MM}（[db-design §1.5] {@code CHAR(7)}）。 */
    private String period;

    private BigDecimal totalAmount;

    private String currency;

    /** GEN / CONFIRMED / PAID。 */
    private String status;

    /** 确认人（V31，CONFIRMED 时回填）。 */
    private String confirmedBy;

    /** 确认时间（V31）。 */
    private java.time.LocalDateTime confirmedAt;
}
