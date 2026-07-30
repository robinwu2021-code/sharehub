package ai.neargo.powerbank.dev.entity;

import ai.neargo.powerbank.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 机柜实体（dev_cabinet）。镜像 Dto.Cabinet；仓位由 Service 派生；主键/审计列见 {@link BaseEntity}。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_cabinet")
public class DevCabinet extends BaseEntity {
    private String cabinetNo;
    private String sn;
    private String vendorCode;
    private String model;
    private String locationNo;
    private String locationName;
    private Integer slotTotal;
    private Integer availableCount;
    private String onlineStatus;
    private String status;
    private String fwVersion;
    private String lastHeartbeatAt;
}
