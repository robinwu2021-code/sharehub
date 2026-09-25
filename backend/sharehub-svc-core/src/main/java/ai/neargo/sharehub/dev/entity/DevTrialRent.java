package ai.neargo.sharehub.dev.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 试借还记录（dev_trial_rent，V97）。不产生订单、不计费。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_trial_rent")
public class DevTrialRent extends BaseEntity {
    private String trialNo;
    private String cabinetNo;
    private Integer slotIndex;
    private String powerbankNo;
    private String status;
    private String ejectCommandNo;
    private LocalDateTime ejectedAt;
    private LocalDateTime returnedAt;
    private String failReason;
    private String operator;
    private String siteNo;
    private String agentNo;
}
