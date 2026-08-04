package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * trade 域间/南向端点（{@code /internal/trade/**}）：由 access-gateway 归还事件驱动结单。
 * 内网受信（运营端安全链，需服务凭证）；非 C 端属主链。
 */
@RestController
@RequestMapping("/internal/trade")
public class TradeInternalController {

    private final RentOrderService orders;

    public TradeInternalController(RentOrderService orders) {
        this.orders = orders;
    }

    /** 归还结单：IN_USE→RETURNED→SETTLED（计费）。body 可带 {@code returnCabinetNo}。 */
    @PostMapping("/orders/{orderNo}/return")
    public OkResult returnOrder(@PathVariable String orderNo,
                                @RequestBody(required = false) Map<String, String> body) {
        return orders.returnOrder(orderNo, body == null ? null : body.get("returnCabinetNo"));
    }
}
