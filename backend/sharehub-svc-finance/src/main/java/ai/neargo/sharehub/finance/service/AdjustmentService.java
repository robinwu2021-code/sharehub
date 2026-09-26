package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.event.SiteClosedEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 结算调整项（对齐清单 C9）：撤场关闭 → 按最后一份合同生成押金 / 进场费调整项，财务确认后并入下一次出账。 */
public interface AdjustmentService {

    /**
     * @param period 按账期的调整（保底补差）填 {@code YYYY-MM}；一次性的（撤场结清）为空串。
     *               **缺了它界面上看不出这笔补差属于哪个月** —— 同一个合同每月一笔，
     *               金额还可能一样，不带账期就分不清是这个月的还是上个月的重复生成。
     */
    record Adjustment(String adjNo, String payeeType, String payeeNo, String payeeName, String kind, String siteNo,
                      String contractNo, BigDecimal amount, BigDecimal suggestedAmount, String currency, String status,
                      String settleNo, String source, String note, String confirmedBy, LocalDateTime confirmedAt,
                      LocalDateTime createdAt, String period) {
    }

    record ConfirmReq(BigDecimal amount, String note) {
    }

    record VoidReq(String reason) {
    }

    /** 幂等：同一站点 × 合同 × 种类只生成一次（唯一键兜底）。返回生成条数。 */
    int onSiteClosed(SiteClosedEvent e);

    /**
     * @param kind   按类型筛（DEPOSIT_REFUND / ENTRY_FEE_SETTLE / GUARANTEE_TOPUP）
     * @param source 按来源筛（SITE_CLOSED / GUARANTEE）
     * @param period 按账期筛（{@code YYYY-MM}）
     */
    PageResult<Adjustment> page(Integer page, Integer size, String status, String payeeNo, String siteNo,
                                String kind, String source, String period);

    /** 确认：可改金额（改了必须写说明），PENDING → CONFIRMED。 */
    Adjustment confirm(String adjNo, BigDecimal amount, String note);

    Adjustment voidIt(String adjNo, String reason);

    /**
     * 保底补差（G2）：账期内「保底（按生效天数折算）− 该合同带来的场地方分成」为正的，生成一条已确认的补差调整项。
     * 合同算出来的钱不需要人再拍一次板，直接 CONFIRMED，随本期出账。幂等（按合同 × 账期）。只处理按月结算的合同。
     */
    int topUpGuarantees(String period);
}
