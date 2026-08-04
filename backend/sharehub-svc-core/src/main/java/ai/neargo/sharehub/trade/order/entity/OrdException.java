package ai.neargo.sharehub.trade.order.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 异常订单（{@code ord_exception}，[db-design §5.1]）。
 *
 * <p>四类异常：{@code NOT_EJECTED}(未弹出) / {@code NOT_RETURNED}(未归还) /
 * {@code OVERTIME_BUYOUT}(超时买断) / {@code DOUBLE_CHARGE}(重复扣费)。
 * 状态只有 {@code OPEN → HANDLED} 一步，处置人/处置时间由服务端回填（见 OrderExceptionServiceImpl）。
 *
 * <p>主键/租户/审计/乐观锁/软删列见 {@link BaseEntity}；{@code region_id} 是本表独有的数据范围列，
 * BaseEntity 没有，故在此声明。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_exception")
public class OrdException extends BaseEntity {

    /** 业务键 UK。前缀见 {@code OrderExceptionServiceImpl#KEY_PREFIX}（BizKey 尚未登记，见交付报告）。 */
    private String exceptionNo;

    private String regionId;

    private String orderNo;

    private String cUserNo;

    private String cabinetNo;

    /** NOT_EJECTED / NOT_RETURNED / OVERTIME_BUYOUT / DOUBLE_CHARGE */
    private String type;

    /** 涉及金额。 */
    private BigDecimal amount;

    private String currency;

    /** OPEN / HANDLED */
    private String status;

    /** 处置人 employee_no（服务端回填，不信前端）。 */
    /** 处置动作：REFUND / COMPENSATE / WORK_ORDER / IGNORE（V31）。 */
    private String handleAction;

    /** 处置结果说明（V31）。 */
    private String handleResult;

    /** 产出的退款单号（V31，handle_action=REFUND 时回填）。 */
    private String refundNo;

    /** 产出的工单号（V31，handle_action=WORK_ORDER 时回填）。 */
    private String workOrderNo;

    private String handledBy;

    /** 空 = 尚未处理。 */
    private String handledAt;
}
