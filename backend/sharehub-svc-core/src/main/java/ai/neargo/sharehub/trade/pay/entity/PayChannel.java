package ai.neargo.sharehub.trade.pay.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 支付渠道配置（pay_channel，[db-design §5.3]）。
 *
 * <p><b>密钥只出掩码</b>（ADR-005）：本类**刻意不声明任何明文密钥字段** ——
 * 明文 API key / 签名密钥落 KMS/vault，**不入库、不进实体、不进 VO、不进日志**。
 * 表里只有 {@link #apiKeyMasked} / {@link #apiSecretMasked} 两列供运营核对末四位。
 * 谁要在这里加 {@code apiKey} 明文字段，先回去看 ADR-005。
 *
 * <p><b>表子域 ≠ API 前缀</b>：表前缀归 trade（{@code pay_}），端点却在
 * {@code /api/platform/payment-channels}（页面挂在「系统设置」菜单下）——
 * 这是全文唯一一处错位，见 [db-design §5.3] 与 [api/README §52]。
 *
 * <p>{@code channelCode} 是**自然键**（STRIPE / TABBY / NEARPAY…），新建必须显式提供，不代为取号。
 * 适用国家/币种/能力见 {@link PayChannelScope}（[db-design §1.7] 多值拆表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_channel")
public class PayChannel extends BaseEntity {

    /** 自然键。 */
    private String channelCode;

    private String channelName;

    private String channelNameEn;

    private String channelNameAr;

    /** DELEGATED（委托 nearpay 执行）/ DIRECT（平台直连）。 */
    private String mode;

    private String apiBase;

    /** 商户号：非密钥，可入库。 */
    private String merchantId;

    /** 调用密钥掩码，如 {@code sk_live_****3f9a}。明文在 KMS/vault。 */
    private String apiKeyMasked;

    /** 验签密钥掩码。明文在 KMS/vault。 */
    private String apiSecretMasked;

    /** ENABLED / DISABLED。 */
    private String status;
}
