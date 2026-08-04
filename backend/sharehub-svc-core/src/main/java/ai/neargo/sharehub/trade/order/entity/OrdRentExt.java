package ai.neargo.sharehub.trade.order.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单扩展 - 充电宝（{@code ord_rent_ext}，ADR-018）。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：扩展表没有 {@code tenant_id}/{@code version}/{@code deleted}，
 * 生命周期完全依附主表 {@code ord_order}，由主表的租户隔离与软删覆盖。
 * 主键就是 {@code order_no}（1:1），不用自增 id —— 扩展表没有独立身份。
 *
 * <p>装的是**只有充电宝才有**的东西：借走的实物、异地归还、买断、时长。
 * 充电桩用 {@code ord_charge_ext}（枪/kWh/表底数），储物柜用 {@code ord_locker_ext}（格口/取件码）。
 */
@Data
@TableName("ord_rent_ext")
public class OrdRentExt {

    @TableId(type = IdType.INPUT)
    private String orderNo;

    private String powerbankNo;
    private String returnCabinetNo;
    private Integer returnSlotIndex;
    private Integer buyout;
    private Integer durationMin;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
