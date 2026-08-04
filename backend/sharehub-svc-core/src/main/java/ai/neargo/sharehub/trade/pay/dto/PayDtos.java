package ai.neargo.sharehub.trade.pay.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * trade/pay 子域出参 VO。字段镜像 ops-web {@code lib/types/system.ts} 的 {@code PaymentChannel}。
 *
 * <p><b>安全约束</b>：本文件里**不得出现任何明文密钥字段**（ADR-005）。渠道 VO 只有掩码列，
 * 明文在 KMS/vault，既不入库也不出接口。
 */
public final class PayDtos {

    private PayDtos() {
    }

    /**
     * 支付渠道行，镜像前端 {@code PaymentChannel}。
     *
     * <p>{@code countries}/{@code currencies}/{@code capabilities} 是**给前端渲染的 CSV**，
     * 由 {@code pay_channel_scope} 的行拼回（[db-design §1.7]）——
     * 库里是行，接口上是串，检索一律走行。
     */
    public record PaymentChannelEntry(String channelCode, String channelName, String channelNameEn,
                                      String channelNameAr, String mode, String status,
                                      String countries, String currencies, String capabilities,
                                      String apiBase, String merchantId,
                                      String apiKeyMasked, String apiSecretMasked,
                                      String updatedAt) {
    }

    /** 渠道适用范围一行。 */
    public record ChannelScopeEntry(String channelCode, String scopeType, String scopeValue) {
    }

    /** 支付引用行（订单详情页内展示）。 */
    public record PayOrderEntry(String payNo, String orderNo, String cUserNo, String type,
                                BigDecimal amount, String currency, String channelCode,
                                String status, String nearpayTxnNo, String paidAt) {
    }

    /** 免押编排行（押金与欠费页）。 */
    public record PayAuthEntry(String authNo, String orderNo, String cUserNo, BigDecimal freezeAmount,
                               BigDecimal capturedAmount, String status, String nearpayAuthNo, String expireAt) {
    }

    /** 渠道退款引用行（退款记录页，与 {@code ord_refund} 审批单 1:1）。 */
    public record PayRefundEntry(String refundNo, String payNo, String ordRefundNo, BigDecimal amount,
                                 String reason, String status, String nearpayRefundNo) {
    }

    /** 渠道 + 其拆表范围的组合视图（配置抽屉用）。 */
    public record PaymentChannelDetail(PaymentChannelEntry channel, List<ChannelScopeEntry> scopes) {
    }
}
