package ai.neargo.sharehub.api.platform.dto;

import java.time.LocalDate;

/** 合同摘要（跨模块）：设备上线门禁与业务告警判定只关心「这个站点有没有生效合同」。 */
public record ContractBrief(String contractNo, String siteNo, String venueNo, String status,
                           LocalDate startAt, LocalDate endAt, String shareMode) {
}
