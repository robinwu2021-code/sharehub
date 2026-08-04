package ai.neargo.sharehub.gw.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商接入配置（gw_vendor_config，[db-design §四]）。粒度 {@code UK(vendor_code, tenant_id)}
 * —— 同一厂商可按租户给不同密钥；{@code tenant_id} 空/MAIN 视为全局默认。
 *
 * <p><b>两把密钥不是一把</b>：{@link #appSecret} 是调用厂商接口的签名密钥（出方向），
 * {@link #verifyKey} 是验厂商回调签名的密钥（入方向）。合成一列会导致轮换其一时必然停摆另一向。
 * 两者都标 {@code [KMS]}：**明文不入库**，本列只存密文引用（同 {@code pay_channel} 的
 * {@code api_key_masked}/{@code api_secret_masked} 处理）。
 *
 * <p>DDL 无 {@code version}/{@code deleted}，故不继承 {@code BaseEntity}。
 */
@Data
@TableName("gw_vendor_config")
public class GwVendorConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String vendorCode;

    /** 租户级差异；空 = 全局默认。 */
    private String tenantId;

    private String apiBase;

    /** 调用密钥（出方向）。 */
    private String appKey;

    /** 调用签名密钥 {@code [KMS]} —— 密文引用，非明文。 */
    private String appSecret;

    /** 回调验签密钥 {@code [KMS]} —— 密文引用，非明文。 */
    private String verifyKey;

    /** 回调来源 IP 白名单（逗号分隔）。 */
    private String ipWhitelist;

    /** driver 参数，JSON 原文。 */
    private String params;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
