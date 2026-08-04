package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 调拨明细（inv_transfer_item，[db-design §3.3 / §1.7 多值拆表]）。
 *
 * <p>{@link #powerbankNo} 与 {@link #cabinetNo} 二选一，由所属单的 {@code itemType} 决定填哪一列。
 * {@link #checked} 是签收核对位：收货时逐件勾，勾齐才允许整单 {@code IN_TRANSIT → DONE}
 * —— 这是「调拨少了一台」能被当场发现而不是月底盘点才发现的原因。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_transfer_item")
public class InvTransferItem extends BaseEntity {

    /** 所属调拨单 → {@link InvTransfer#getTransferNo()}。 */
    private String transferNo;

    /** 充电宝（itemType=POWERBANK 时填）。 */
    private String powerbankNo;

    /** 机柜（itemType=CABINET 时填）。 */
    private String cabinetNo;

    /** 是否已签收核对（TINYINT(1) → Integer）。 */
    private Integer checked;
}
