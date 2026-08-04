package ai.neargo.sharehub.gw.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 供应商标识 ↔ 平台 SN 映射（gw_device_binding，[db-design §四]）。
 *
 * <p>混合硬件接入的翻译层：各厂商上报的原始标识（{@link #rawIdentity}，可能是 IMEI、厂商自编号、
 * MAC 等各不相同的东西）在这里映射到平台统一 {@link #sn} 与 {@link #cabinetNo}。
 * {@code UK(vendor_code, raw_identity)} —— 同一原始标识在同一厂商内唯一，跨厂商可重复
 * （两家厂商各自编号撞车是常态，所以键必须带 {@code vendor_code}）。
 *
 * <p>DDL 无审计/乐观锁列，故不继承 {@code BaseEntity}。
 */
@Data
@TableName("gw_device_binding")
public class GwDeviceBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台统一设备序列号。 */
    private String sn;

    private String vendorCode;

    private String cabinetNo;

    /** 厂商原始标识（IMEI/自编号/MAC…，各家不同）。 */
    private String rawIdentity;

    /** 绑定时刻。 */
    private String boundAt;
}
