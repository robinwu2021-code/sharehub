package ai.neargo.sharehub.dev.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 设备保护动作（dev_protection，V97）。一条 =「谁」要求「对哪台柜 / 哪个仓」做「什么」。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_protection")
public class DevProtection extends BaseEntity {
    private String protectionNo;
    private String cabinetNo;
    /** -1 = 整柜。 */
    private Integer slotIndex;
    private String action;
    private String holderType;
    private String holderRef;
    private String reason;
    /** 1 生效中；NULL 已释放（NULL 不参与唯一键）。释放时要能写 NULL，故 ALWAYS。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer active;
    private LocalDateTime releasedAt;
    private String releaseReason;
    private String siteNo;
    private String agentNo;
}
