package ai.neargo.sharehub.platform.sys.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 开放平台应用（openapi_app）—— 第三方调用方的凭据与限流。
 *
 * <p>业务键前缀 {@code APP}（{@link ai.neargo.sharehub.common.BizKey#OPENAPI_APP}）。
 *
 * <p><b>密钥只落哈希</b>：{@code appSecretHash} 入库，明文仅在创建时一次性返回给调用方、
 * 之后不可再读（ADR-005 的一贯口径：powerbank 不落任何明文密钥）。
 * 出参 VO **绝不包含** {@code appSecretHash}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("openapi_app")
public class OpenapiApp extends BaseEntity {
    private String appNo;
    private String name;
    /** 调用方标识（公开）。 */
    private String appKey;
    /** 密钥哈希；明文落 KMS/vault，不入库、不出参。 */
    private String appSecretHash;
    /** 授权范围（权限码数组）JSON 文本。 */
    private String scopes;
    /** 限流（次/分钟，0=不限）。 */
    private Integer rateLimit;
    /** ENABLED/DISABLED。 */
    private String status;
    /**
     * 上次密钥重置时间（null = 从未重置）。
     * 只留时间戳不留明文 —— 明文由 KMS/vault 持有，见 {@link #appSecretHash} 的口径。
     */
    private java.time.LocalDateTime secretResetAt;
}
