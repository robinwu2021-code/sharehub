package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.ConsumerContext;
import ai.neargo.sharehub.portal.core.dto.MpTradeDtos.ConsumerOrderVO;
import ai.neargo.sharehub.platform.sys.service.BizRuleService;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentResult;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayAuthEntry;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayOrderEntry;
import ai.neargo.sharehub.trade.pay.service.PaymentService;
import ai.neargo.sharehub.trade.service.RentOrderService;
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

    /** 免押冻结额度（AED）：与 {@code RentOrderServiceImpl.DEPOSIT} 同源的骨架常量（ADR-005 支付委托 nearpay）。 */
    private static final java.math.BigDecimal DEPOSIT_FREEZE = java.math.BigDecimal.valueOf(50);

    private final RentOrderService orders;
    private final ConsumerOrderAssembler cards;
    private final PaymentService payments;
    private final BizRuleService bizRules;

    public RentController(RentOrderService orders, PaymentService payments, BizRuleService bizRules,
                          ConsumerOrderAssembler cards) {
        this.cards = cards;
        this.orders = orders;
        this.payments = payments;
        this.bizRules = bizRules;
    }

    /**
     * 扫码借出：{@code {cabinetNo, useFreeDeposit, couponNo}} → 创单，返回订单号 + 充电宝 + 指令号。
     *
     * <p><b>{@code useFreeDeposit} 此前被丢掉了</b>：这里只读 {@code cabinetNo}，
     * 于是确认页上那个「免押金 / 支付押金」的单选**点哪个都一样** ——
     * 选免押的人已经被冻结了一笔预授权，订单上又记一笔 50 押金，
     * 同一笔钱在他眼里出现两次。不报错，只是账看起来不对。
     *
     * <p>缺省 {@code true}：确认页默认就是免押（{@code useFree = ref(true)}），
     * 老客户端不传这个字段时按它当时的界面语义走。
     */
    @PostMapping("/orders/rent")
    public RentResult rent(@RequestBody Map<String, Object> body) {
        // 逐个取值不能图省事写 String.valueOf(get(...))：键缺席时它给的是字符串 "null"，
        // 于是「没扫码」会被当成「有个叫 null 的柜子」，报成「该设备暂不可借」
        Object cabinet = body == null ? null : body.get("cabinetNo");
        String cabinetNo = cabinet == null ? null : String.valueOf(cabinet);
        Object free = body == null ? null : body.get("useFreeDeposit");
        Object coupon = body == null ? null : body.get("couponNo");
        return orders.rent(ConsumerContext.userNo(), cabinetNo,
                free == null || Boolean.parseBoolean(String.valueOf(free)),
                coupon == null ? null : String.valueOf(coupon));
    }

    /**
     * 我的订单（属主过滤，只见自己）。
     *
     * <p>出参是 C 端投影 {@link ConsumerOrderVO}，不是运营端那个 {@code RentOrder} ——
     * 后者缺借还两端的门店名、也把运营干预统计带给了消费者，见 {@code MpTradeDtos} 类注释。
     * 列表不带 fees/timeline（时间线要逐单查事件表，挂列表上就是 N+1）。
     */
    @GetMapping("/orders")
    public PageResult<ConsumerOrderVO> myOrders(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        PageResult<RentOrder> r = orders.pageByOwner(ConsumerContext.userNo(), page, size);
        return new PageResult<>(cards.list(r.getList()), r.getTotal());
    }

    /** 进行中订单（首页快捷入口）：无则 200 + null data，端上按无单渲染。 */
    @GetMapping("/orders/ongoing")
    public ConsumerOrderVO ongoing() {
        return cards.one(orders.ongoingOf(ConsumerContext.userNo()));
    }

    /** 订单详情：属主鉴权，非本人 → 403（防横向越权 IDOR）。 */
    @GetMapping("/orders/{orderNo}")
    public ConsumerOrderVO detail(@PathVariable String orderNo) {
        // 走豁免数据范围的取数：否则非属主查询在 SQL 层就被过滤成空，
        // 守卫拿不到行、无从判定，403「无权」会退化成 400「不存在」
        RentOrder o = orders.detailForConsumer(orderNo);
        ConsumerContext.assertOwner(o.cUserNo());
        return cards.detail(o);
    }

    /** 买断：不还了，按 {@code sys_biz_rule} 计费兜底分区的买断价结单（属主鉴权）。 */
    @PostMapping("/orders/{orderNo}/buyout")
    public ConsumerOrderVO buyout(@PathVariable String orderNo) {
        RentOrder o = orders.detailForConsumer(orderNo);
        ConsumerContext.assertOwner(o.cUserNo());
        var billing = bizRules.get() == null ? null : bizRules.get().billing();
        return cards.detail(orders.buyout(orderNo, billing == null ? null : billing.buyoutPrice()));
    }

    /**
     * 免押预授权（借出前）：冻结额度走 {@code pay_auth}（StubPaymentPort，ADR-005 不真扣款）。
     * 尚无订单号，pay_auth 先挂柜机号做业务引用，借出后由借出流程回填 —— 骨架阶段约定。
     */
    @PostMapping("/deposit/free")
    public Map<String, Object> depositFree(@RequestBody Map<String, String> body) {
        String cabinetNo = body == null ? null : body.get("cabinetNo");
        if (cabinetNo == null || cabinetNo.isBlank()) throw new IllegalArgumentException("柜机号为空");
        PayAuthEntry auth = payments.preAuth(cabinetNo, ConsumerContext.userNo(),
                DEPOSIT_FREEZE, "AED", null, "DEPFREE-" + cabinetNo + "-" + ConsumerContext.userNo());
        return Map.of("authNo", auth.authNo(), "frozen", auth.freezeAmount());
    }

    /**
     * 支付（{@code scene}=ORDER/…）。桩通道即时 PAID → 映射 c-app 的 SUCCESS；
     * 真实收银台参数（cashierParams）待 nearpay 接入（ADR-005），当前恒 null。
     */
    @PostMapping("/pay")
    public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
        String orderNo = body.get("orderNo") == null ? null : String.valueOf(body.get("orderNo"));
        String scene = body.get("scene") == null ? "ORDER" : String.valueOf(body.get("scene"));
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("充值等无单支付待充值单流程接入，当前仅支持按订单支付（orderNo 必填）");
        }
        RentOrder o = orders.detailForConsumer(orderNo);
        ConsumerContext.assertOwner(o.cUserNo());
        java.math.BigDecimal amount = body.get("amount") == null
                ? java.math.BigDecimal.valueOf(o.feeAmount())
                : new java.math.BigDecimal(String.valueOf(body.get("amount")));
        PayOrderEntry p = payments.pay(orderNo, ConsumerContext.userNo(), scene, amount,
                o.currency(), null, "PAY-" + orderNo);
        String status = "PAID".equals(p.status()) ? "SUCCESS"
                : "FAILED".equals(p.status()) ? "FAILED" : "PENDING";
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("payNo", p.payNo());
        out.put("status", status);
        out.put("cashierParams", null);   // nearpay 收银台参数，接入后回填
        return out;
    }
}
