package ai.neargo.sharehub.finance.statement;

import java.math.BigDecimal;
import java.util.List;

/**
 * 场地方对账单（对齐清单 G3，裁决 #2：首版不做场地方门户，结算单导出对账单线下发送）。
 * 一张结算单一份对账单：订单汇总、按合同 × 比例的分成计算、保底补差与其它调整项。
 */
public interface StatementService {

    record ShareLine(String contractNo, BigDecimal rate, long orders, BigDecimal gross, BigDecimal amount) {
    }

    record AdjustLine(String adjNo, String kind, String contractNo, String siteNo, String period, BigDecimal amount, String note) {
    }

    record Statement(String settleNo, String payeeType, String payeeNo, String payeeName, String period, String currency,
                     String status, long orderCount, BigDecimal grossTotal, BigDecimal shareTotal, List<ShareLine> shares,
                     BigDecimal adjustTotal, List<AdjustLine> adjustments, BigDecimal total) {
    }

    Statement statement(String settleNo);

    /** 可打印的对账单（浏览器「打印 → 存为 PDF」）；lang = zh / en / ar（ar 右到左）。 */
    String html(String settleNo, String lang);
}
