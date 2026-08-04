package ai.neargo.sharehub.gw.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 硬件供应商（gw_vendor，[db-design §四]）—— **全局表，不注入 {@code tenant_id}**（[§1.4]）。
 *
 * <p>{@code vendor_code} 是自然键。混合硬件接入（ADR-001）的入口：
 * {@link #accessMode} ∈ {@code TCP/MQTT/HTTP_API} 决定走哪条南向通道，
 * 也决定 {@link GwVendorConfig} 里哪些参数有意义（HTTP_API 才需要 {@code apiBase}/{@code appKey}）。
 *
 * <p>与全局表 {@code md_bank} 一致：继承 {@code BaseEntity} 带来的 {@code tenantId}
 * 在本表无对应列（见 {@link ai.neargo.sharehub.alarm.entity.DevAlarmCode} 的同款说明）。
 *
 * <p>⚠️ 现有 {@code portal/ops/VendorController} 仍读内存 {@code SeedData.vendors()}，
 * 尚未切到本实体；切换归网关分片后续处理，本分片只补持久化模型。
 */
@Data
@EqualsAndHashCode(callSuper = true)
// 全局表：无 tenant_id 列（db-design §1.3 全局表清单 tenant/iam_permission/dict_/md_/gw_vendor）。
// 不排除会让 MyBatis-Plus 拼出不存在的列 —— 这类不一致在「没人查过这张表」时不会暴露，
// 一旦有代码第一次 SELECT 它就是 Unknown column（price_rule 就是这么炸的）。
@TableName(value = "gw_vendor", excludeProperty = "tenantId")
public class GwVendor extends BaseEntity {

    /** 自然键，如 {@code cd-tech}/{@code sd-power}/{@code chargenow}。 */
    private String vendorCode;

    private String name;

    /** TCP/MQTT/HTTP_API。 */
    private String accessMode;

    /** HTTP_API 模式的厂商开放平台地址。 */
    private String apiBase;

    /** 在网设备数（冗余统计列，供供应商接入页直接展示）。 */
    private Integer deviceCount;

    /** ENABLED/DISABLED。 */
    private String status;
}
