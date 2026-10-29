package ai.neargo.sharehub.inv.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 设备供应商（inv_supplier，V201）：到货登记的来源、不合格「退供应商」的去向。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_supplier")
public class InvSupplier extends BaseEntity {

    /** 业务键。 */
    private String supplierNo;
    private String name;
    private String contactName;
    private String phone;
    private String email;
    private String address;
    private String note;
    /** 归档时间；空为启用。 */
    private LocalDateTime archivedAt;
}
