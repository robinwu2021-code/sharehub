package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.event.SiteClosedEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 结算调整项（对齐清单 C9）：撤场关闭 → 按最后一份合同生成押金 / 进场费调整项，财务确认后并入下一次出账。 */
public interface AdjustmentService {

    record Adjustment(String adjNo, String payeeType, String payeeNo, String payeeName, String kind, String siteNo,
                      String contractNo, BigDecimal amount, BigDecimal suggestedAmount, String currency, String status,
                      String settleNo, String source, String note, String confirmedBy, LocalDateTime confirmedAt,
                      LocalDateTime createdAt) {
    }

    record ConfirmReq(BigDecimal amount, String note) {
    }

    record VoidReq(String reason) {
    }

    /** 幂等：同一站点 × 合同 × 种类只生成一次（唯一键兜底）。返回生成条数。 */
    int onSiteClosed(SiteClosedEvent e);

    PageResult<Adjustment> page(Integer page, Integer size, String status, String payeeNo, String siteNo);

    /** 确认：可改金额（改了必须写说明），PENDING → CONFIRMED。 */
    Adjustment confirm(String adjNo, BigDecimal amount, String note);

    Adjustment voidIt(String adjNo, String reason);

    /**
     * 保底补差（G2）：账期内「保底（按生效天数折算）− 该合同带来的场地方分成」为正的，生成一条已确认的补差调整项。
     * 合同算出来的钱不需要人再拍一次板，直接 CONFIRMED，随本期出账。幂等（按合同 × 账期）。只处理按月结算的合同。
     */
    int topUpGuarantees(String period);
}
