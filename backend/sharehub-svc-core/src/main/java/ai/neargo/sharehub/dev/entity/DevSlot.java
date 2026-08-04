package ai.neargo.sharehub.dev.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 仓位实体（dev_slot，[db-design §3.1]）。UK(cabinet_no, slot_index)。
 *
 * <p>仓位是机柜的从属明细，没有独立业务键，故不设 {@code slotNo}；对外由
 * {@code cabinetNo + slotIndex} 定位。主键/审计列见 {@link BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_slot")
public class DevSlot extends BaseEntity {

    private String cabinetNo;

    private Integer slotIndex;

    /** 在仓充电宝业务键；空表示空仓。 */
    private String powerbankNo;

    /** LOCKED / UNLOCKED。 */
    private String lockStatus;

    /** OK / FAULT。 */
    private String health;
}
