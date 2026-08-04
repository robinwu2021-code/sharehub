package ai.neargo.sharehub.trade.order.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 预约订单（{@code ord_reservation}，[db-design §5.1]）。
 *
 * <p>竞品是「预约充电桩」；充电宝映射为 {@code BORROW}(预约取宝) / {@code RETURN}(预约还位)，
 * 解热门点位高峰占位。状态 {@code PENDING → FULFILLED/EXPIRED/CANCELLED}，
 * **只有 PENDING 可取消**且必须服务端复校（前端灰按钮只是乐观提示）。
 *
 * <p>主键/租户/审计列见 {@link BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_reservation")
public class OrdReservation extends BaseEntity {

    /** 业务键 UK，前缀 {@code RSV}。 */
    private String reservationNo;

    private String regionId;

    private String cUserNo;

    /** BORROW / RETURN */
    private String type;

    private String siteNo;

    /** 站点名快照（不随源改名回溯）。 */
    private String siteName;

    /** 指定机柜；空 = 站点级预约。 */
    private String cabinetNo;

    /** 预约窗口起。 */
    private String reservedFrom;

    /** 预约窗口止。 */
    private String reservedTo;

    /** 占位费（超时未取产生；规则在业务规则页配置）。 */
    private BigDecimal holdFee;

    private String currency;

    /** PENDING / FULFILLED / EXPIRED / CANCELLED */
    private String status;

    /** 履约后回填 {@code ord_order.order_no}。 */
    private String orderNo;
}
