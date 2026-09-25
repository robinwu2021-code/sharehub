package ai.neargo.sharehub.api.core.port;

import java.util.Collection;
import java.util.Map;

/** 订单的系统读（站点关闭门禁、告警站点画像）。豁免数据范围。 */
public interface OrderQueryPort {

    /** 各站点进行中（出宝中 / 使用中）的订单数。 */
    Map<String, Long> inFlightCountBySites(Collection<String> siteNos);

    /** 各站点近 days 天的实收 GMV（告警站点画像：A/B/C 分级）。 */
    Map<String, java.math.BigDecimal> gmvBySites(Collection<String> siteNos, int days);

    /** 各站点在 [from, to) 内按月（YYYY-MM）的已结算收入（批次 G1 低效站点）。没有收入的月份不出现。 */
    Map<String, Map<String, java.math.BigDecimal>> monthlyGmvBySites(Collection<String> siteNos, java.time.LocalDate from,
                                                                     java.time.LocalDate to);

    /** 各站点近 days 天按小时（业务时区）的下单量：siteNo → (hour 0–23 → count)（告警站点画像：高峰时段）。 */
    Map<String, Map<Integer, Long>> ordersByHour(Collection<String> siteNos, int days);
}
