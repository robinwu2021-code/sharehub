package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 仓库（inv_warehouse，[db-design §3.3]）。调拨的一端；另一端可能是站点或点位，见 {@link InvTransfer}。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_warehouse")
public class InvWarehouse extends BaseEntity {

    /** 业务键。 */
    private String warehouseNo;

    /** 区域 → {@code md_region.region_id}。 */
    private String regionId;

    private String name;

    private String address;
}
