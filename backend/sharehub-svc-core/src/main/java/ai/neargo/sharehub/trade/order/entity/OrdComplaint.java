package ai.neargo.sharehub.trade.order.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 投诉订单（{@code ord_complaint}，[db-design §5.1]）。
 *
 * <p>与竞品的差别在 {@link #woNo}：投诉可直接转工单，投诉/订单/工单三者串通。
 * 转工单**幂等**——{@code wo_no} 非空即返已有值，底层还有 {@code wo_order.source_ref} UNIQUE 兜底
 * （[db-design §1.6] 幂等清单）。
 *
 * <p>主键/租户/审计列见 {@link BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_complaint")
public class OrdComplaint extends BaseEntity {

    /** 业务键 UK，前缀 {@code CPL}。 */
    private String complaintNo;

    private String regionId;

    private String orderNo;

    private String cUserNo;

    /** BILLING_DISPUTE / NOT_EJECTED / NOT_RETURNED / DEVICE_FAULT / OTHER */
    private String issueType;

    private String description;

    /** 截图证据 URL。 */
    private String screenshotUrl;

    private String submittedAt;

    /** PENDING / PROCESSING / RESOLVED / REJECTED */
    private String status;

    /** 处理人快照名（不回溯），服务端回填。 */
    private String handlerName;

    /** 空 = 尚未处理。 */
    private String handledAt;

    /** REFUND / COMPENSATE / REJECT / EXPLAINED */
    private String resolution;

    private String resolutionNote;

    /** 转工单后回填的工单号；非空即视为已转过（幂等判据）。 */
    private String woNo;
}
