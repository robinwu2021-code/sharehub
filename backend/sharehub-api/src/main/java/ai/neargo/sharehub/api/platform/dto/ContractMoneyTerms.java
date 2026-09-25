package ai.neargo.sharehub.api.platform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 合同上的钱（撤场结清用，C9）：押金、进场费与期限。只给 finance 生成结算调整项。 */
public record ContractMoneyTerms(String contractNo, String siteNo, String venueNo, String venueName, String status,
                                 String currency, BigDecimal depositAmount, BigDecimal entryFee,
                                 LocalDate startAt, LocalDate endAt) {
}
