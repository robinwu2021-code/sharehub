package ai.neargo.sharehub.trade.price.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 预约调价单（price_adjustment，V40）。到点自动改价、到期自动改回。
 *
 * <p><b>状态机</b>：{@code SCHEDULED → APPLIED → REVERTED}，另有两条出口
 * {@code SCHEDULED → CANCELLED}（人工撤销）与 {@code SCHEDULED/APPLIED → FAILED}（执行失败）。
 * 已生效的不能撤销 —— 价格已经对外生效过了，"撤销"没有意义，要的是「提前恢复」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_adjustment")
public class PriceAdjustment extends BaseEntity {

    private String adjustNo;

    private String planNo;

    private String name;

    /** 要改成什么。JSON 文本，见 V40 注释里「为什么不拆成列」。 */
    private String patch;

    /** 生效那一刻被改字段的原值。恢复时只写回这几个字段，不碰别人的改动。 */
    private String beforeSnapshot;

    private LocalDateTime effectiveAt;

    /** 空 = 不自动恢复（长期调价）。 */
    private LocalDateTime revertAt;

    private String reason;

    /** SCHEDULED / APPLIED / REVERTED / CANCELLED / FAILED。 */
    private String status;

    private LocalDateTime appliedAt;

    private LocalDateTime revertedAt;

    /** 执行失败原因。运营据此决定是改单还是重试 —— 只说「失败」等于没说。 */
    private String failReason;
}
