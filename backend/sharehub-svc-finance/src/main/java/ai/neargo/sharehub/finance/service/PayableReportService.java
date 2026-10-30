package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;

import java.math.BigDecimal;

/**
 * 跨期应付报表（运营方原始需求 SHR-05，第 6 批）：按 收款方 × 期间 列出 应得 / 已出结算单 / 已付 / 未付。
 *
 * <p>应得 = 该期间分润明细合计（退款冲回的负数明细一并计入）；已出结算单 = 已确认或已付的结算单金额；
 * 已付 = 已付结算单金额；未付 = 应得 − 已付。正是「这个商家我们还欠多少、欠在哪几个月」。
 */
public interface PayableReportService {

    record PayableRow(String payeeType, String payeeNo, String payeeName, String period, String currency,
                      BigDecimal earned, BigDecimal settled, BigDecimal paid, BigDecimal outstanding) {
    }

    /**
     * @param fromPeriod / toPeriod {@code yyyy-MM}，含两端；空 = 不限
     * @param onlyOutstanding true = 只列未付不为 0 的行
     */
    PageResult<PayableRow> page(Integer page, Integer size, String payeeType, String payeeNo,
                                String fromPeriod, String toPeriod, boolean onlyOutstanding);
}
