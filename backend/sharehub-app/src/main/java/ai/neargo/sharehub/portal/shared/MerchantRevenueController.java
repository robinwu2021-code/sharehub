package ai.neargo.sharehub.portal.shared;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.time.BizClock;
import ai.neargo.sharehub.finance.revenue.ShareRevenueQuery;
import ai.neargo.sharehub.trade.revenue.OrderRevenueQuery;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 商家营收（商家一期 B5；用户 2026-10-28：商家的核心诉求是查看营收）。
 *
 * <p>商家登录后首页读这三个端点。商家与运营共用：数据范围按会话收敛 —— 商家只看自己的，运营看全部。
 * 口径见需求方案 §10.1：交易额含 VAT、退款按已审批、税前营收与分成取分成明细、钱的状态按现在。
 *
 * <p>窗口缺省为本月 1 号到今天（业务时区）；环比取紧挨着的、同样长度的上一个窗口。
 */
@RestController
public class MerchantRevenueController {

    private static final int MAX_DAYS = 366;

    private final OrderRevenueQuery orders;
    private final ShareRevenueQuery shares;
    private final BizClock clock;
    private final ai.neargo.sharehub.dev.service.impl.DeviceAlertQuery devices;

    public MerchantRevenueController(OrderRevenueQuery orders, ShareRevenueQuery shares, BizClock clock,
                                     ai.neargo.sharehub.dev.service.impl.DeviceAlertQuery devices) {
        this.devices = devices;
        this.orders = orders;
        this.shares = shares;
        this.clock = clock;
    }

    @GetMapping("/api/ops/merchant-revenue/summary")
    @PreAuthorize("@perm.can('dashboard:overview:read')")
    public RevenueSummary summary(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate[] w = window(from, to);
        long days = ChronoUnit.DAYS.between(w[0], w[1]) + 1;
        LocalDate pTo = w[0].minusDays(1), pFrom = pTo.minusDays(days - 1);

        OrderRevenueQuery.Totals t = orders.totals(w[0], w[1]);
        OrderRevenueQuery.Totals pt = orders.totals(pFrom, pTo);
        ShareRevenueQuery.Shares s = shares.shares(w[0], w[1]);
        ShareRevenueQuery.Shares ps = shares.shares(pFrom, pTo);
        ShareRevenueQuery.Money m = shares.money();
        return new RevenueSummary(w[0].toString(), w[1].toString(), t.orderCount(), t.gross(), t.refund(),
                t.gross().subtract(t.refund()), s.preTax(), s.myShare(),
                m.pending(), m.settledUnpaid(), m.paid(),
                pt.orderCount(), pt.gross(), ps.myShare());
    }

    @GetMapping("/api/ops/merchant-revenue/trend")
    @PreAuthorize("@perm.can('dashboard:overview:read')")
    public List<OrderRevenueQuery.RevenueDay> trend(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate[] w = window(from, to);
        return orders.trend(w[0], w[1]);
    }

    @GetMapping("/api/ops/merchant-revenue/by-device")
    @PreAuthorize("@perm.can('dashboard:overview:read')")
    public List<OrderRevenueQuery.RevenueByDevice> byDevice(@RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) Integer limit) {
        LocalDate[] w = window(from, to);
        return orders.byDevice(w[0], w[1], limit == null ? 10 : limit);
    }

    private LocalDate[] window(String from, String to) {
        LocalDate today = clock.today();
        LocalDate f = parse(from, today.withDayOfMonth(1)), t = parse(to, today);
        if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) >= MAX_DAYS) {
            throw BizException.badRequest("error.common.invalid_value", f + " ~ " + t);
        }
        return new LocalDate[]{f, t};
    }

    private static LocalDate parse(String v, LocalDate dflt) {
        if (v == null || v.isBlank()) return dflt;
        try {
            return LocalDate.parse(v.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw BizException.badRequest("error.common.invalid_value", v);
        }
    }

    /**
     * @param net         交易额 − 退款
     * @param preTax      税前营收（分成基数合计）
     * @param pending     待结算：已产生分成、尚未进入结算单
     * @param settledUnpaid 已结未付：结算单已确认、尚未到账
     * @param paid        已到账：提现单已到账合计
     * @param prevOrderCount 上一个同长窗口（环比用）
     */
    public record RevenueSummary(String from, String to, int orderCount, BigDecimal gross, BigDecimal refund,
                                 BigDecimal net, BigDecimal preTax, BigDecimal myShare,
                                 BigDecimal pending, BigDecimal settledUnpaid, BigDecimal paid,
                                 int prevOrderCount, BigDecimal prevGross, BigDecimal prevMyShare) {
    }

    /**
     * 设备提醒：在用设备里离线几台、在线但没有可借电池几台，各带最多 20 个柜号供点进去处理。
     * 按「现在」，不随时间窗口变。
     */
    @GetMapping("/api/ops/merchant-revenue/alerts")
    @PreAuthorize("@perm.can('dashboard:overview:read')")
    public ai.neargo.sharehub.dev.service.impl.DeviceAlertQuery.DeviceAlerts alerts() {
        return devices.alerts();
    }
}
