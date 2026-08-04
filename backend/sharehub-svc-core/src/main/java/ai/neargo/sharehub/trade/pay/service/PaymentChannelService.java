package ai.neargo.sharehub.trade.pay.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.ChannelScopeEntry;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PaymentChannelDetail;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PaymentChannelEntry;
import ai.neargo.sharehub.trade.pay.entity.PayChannel;

import java.util.List;

/**
 * 支付渠道配置（pay_channel）。纯配置读写 → 继承通用 CRUD。
 *
 * <p><b>密钥只出掩码</b>：本服务读写的实体与 VO 都不含明文密钥（ADR-005）。
 * 明文轮换走 KMS/vault 的独立流程，不经过这里。
 *
 * <p>国家/币种/能力是 [db-design §1.7] 的多值拆表，读写都提供行与 CSV 两套入口。
 */
public interface PaymentChannelService extends CrudService<PayChannel, PaymentChannelEntry> {

    /** {@code scope_type} 取值（[db-design §1.7]）。 */
    String SCOPE_COUNTRY = "COUNTRY";
    String SCOPE_CURRENCY = "CURRENCY";
    String SCOPE_CAPABILITY = "CAPABILITY";

    /** 渠道 + 全部适用范围行。 */
    PaymentChannelDetail detail(String channelCode);

    /** 按渠道读回全部范围行。 */
    List<ChannelScopeEntry> scopesOf(String channelCode);

    /** 按渠道 + 类型（COUNTRY/CURRENCY/CAPABILITY）读回一组 {@code scope_value}。 */
    List<String> scopeValues(String channelCode, String scopeType);

    /** 整组替换某一类型下的值（先软删旧行再插新行）。 */
    void replaceScope(String channelCode, String scopeType, List<String> scopeValues);

    /** CSV 入口：把前端的 {@code "AE,SA"} 拆成行。空串 = 清空。 */
    void replaceScopeCsv(String channelCode, String scopeType, String csv);

    /** 反查：某国家 + 币种下启用且具备指定能力的渠道码。 */
    List<String> channelsFor(String country, String currency, String capability);
}
