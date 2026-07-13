package ai.neargo.powerbank.portal.mp;

import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.auth.ConsumerContext;
import ai.neargo.powerbank.dto.Dto.RentOrder;
import ai.neargo.powerbank.dto.Dto.RentResult;
import ai.neargo.powerbank.trade.service.RentOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * C 端租借（{@code /mp/trade/**}，需 CONSUMER 会话）。**属主鉴权**：只经 {@link ConsumerContext} 取/断言自己的 c_user_no，
 * 无 RBAC。借还闭环：借出→我的订单→详情（防 IDOR）。归还由设备事件驱动，走 {@code /internal/trade/.../return}。
 */
@RestController
@RequestMapping("/mp/trade")
public class RentController {

    private final RentOrderService orders;

    public RentController(RentOrderService orders) {
        this.orders = orders;
    }

    /** 扫码借出：{@code {cabinetNo}} → 创单（免押/弹仓骨架），返回订单号 + 充电宝 + 指令号。 */
    @PostMapping("/orders/rent")
    public RentResult rent(@RequestBody Map<String, String> body) {
        return orders.rent(ConsumerContext.userNo(), body == null ? null : body.get("cabinetNo"));
    }

    /** 我的订单（属主过滤，只见自己）。 */
    @GetMapping("/orders")
    public PageResult<RentOrder> myOrders(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return orders.pageByOwner(ConsumerContext.userNo(), page, size);
    }

    /** 订单详情：属主鉴权，非本人 → 403（防横向越权 IDOR）。 */
    @GetMapping("/orders/{orderNo}")
    public RentOrder detail(@PathVariable String orderNo) {
        RentOrder o = orders.detail(orderNo);
        ConsumerContext.assertOwner(o.cUserNo());
        return o;
    }
}
