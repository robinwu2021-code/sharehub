package ai.neargo.powerbank.trade.entity;

import ai.neargo.powerbank.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 租借订单实体（ord_rent）。镜像 Dto.RentOrder；主键/审计列见 {@link BaseEntity}。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_rent")
public class OrdRent extends BaseEntity {
    private String orderNo;
    private String cUserNo;
    private String cabinetNo;
    private String returnCabinetNo;
    private String powerbankNo;
    private String locationName;
    private String status;
    private String rentStartAt;
    private String rentEndAt;
    private Integer durationMin;
    private Double feeAmount;
    private Double depositAmount;
    private String currency;
}
