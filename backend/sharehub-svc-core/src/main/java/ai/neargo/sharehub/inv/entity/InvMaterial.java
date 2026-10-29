package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 物料档案（inv_material，V210）。deviceType 非空 = 内置物料（机柜 / 充电宝，走设备流程）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_material")
public class InvMaterial extends BaseEntity {

    private String materialNo;
    private String name;
    /** {@link ai.neargo.sharehub.inv.MaterialCategory}。 */
    private String category;
    /** {@link ai.neargo.sharehub.inv.MaterialTracking}。 */
    private String trackingMode;
    private String uom;
    private String spec;
    private String vendorCode;
    private String defaultSupplierNo;
    private String deviceType;
    private LocalDateTime archivedAt;
}
