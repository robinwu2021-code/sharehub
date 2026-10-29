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

import java.util.List;
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
    private final ai.neargo.sharehub.trade.order.service.TaxInvoiceService taxInvoices;
    private final ai.neargo.sharehub.portal.finance.TaxInvoiceDocuments taxInvoiceDocs;

    public RentController(RentOrderService orders, PaymentService payments, BizRuleService bizRules,
                          ConsumerOrderAssembler cards,
                          ai.neargo.sharehub.trade.order.service.TaxInvoiceService taxInvoices,
                          ai.neargo.sharehub.portal.finance.TaxInvoiceDocuments taxInvoiceDocs) {
        this.taxInvoices = taxInvoices;
        this.taxInvoiceDocs = taxInvoiceDocs;
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
    public RentResult rent(@RequestBody ai.neargo.sharehub.trade.dto.MpTradeReqs.RentReq body) {
        // record 顺带解决了裸 Map 的一个坑：原先逐个取值时若写
        // String.valueOf(get("cabinetNo"))，键缺席给的是字符串 "null" ——
        // 「没扫码」会被当成「有个叫 null 的柜子」，报成「该设备暂不可借」。
        return orders.rent(ConsumerContext.userNo(), body == null ? null : body.cabinetNo(),
                body == null || body.freeDeposit(),
                body == null ? null : body.couponNo());
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
    /** 本人某订单的税务票据（V181，只含已开出的；开关关着时为空列表）。 */
    @GetMapping("/orders/{orderNo}/invoices")
    public List<ai.neargo.sharehub.portal.finance.TaxInvoiceView> invoices(@PathVariable String orderNo) {
        RentOrder o = orders.detailForConsumer(orderNo);
        ConsumerContext.assertOwner(o.cUserNo());
        return taxInvoices.listForUser(o.cUserNo(), orderNo).stream()
                .map(ai.neargo.sharehub.portal.finance.TaxInvoiceView::of).toList();
    }

    /** 下载本人某张税务票据的 PDF（K8 / RQ-APP-05）。票必须属于这张单，单必须属于本人。 */
    @GetMapping("/orders/{orderNo}/invoices/{invoiceNo}/pdf")
    public void invoicePdf(@PathVariable String orderNo, @PathVariable String invoiceNo,
                           jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        RentOrder o = orders.detailForConsumer(orderNo);
        ConsumerContext.assertOwner(o.cUserNo());
        var t = taxInvoices.listForUser(o.cUserNo(), orderNo).stream()
                .filter(x -> x.getInvoiceNo().equals(invoiceNo)).findFirst()
                .orElseThrow(() -> ai.neargo.sharehub.common.BizException.notFound(invoiceNo));
        taxInvoiceDocs.pdf(t).writeTo(response);
    }

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
    public Map<String, Object> depositFree(
            @RequestBody ai.neargo.sharehub.trade.dto.MpTradeReqs.DepositFreeReq body) {
        // 先查欠费再冻结：此前欠费用户点「立即借」，免押预授权照样冻上，下一步借出才被 409 拦下 ——
        // 钱冻了、宝没借到（真机实测：deposit/free 200 → rent 409）。接真通道后那是真实的卡上冻结
        orders.requireNoArrears(ConsumerContext.userNo());
        String cabinetNo = body == null ? null : body.cabinetNo();
        if (cabinetNo == null || cabinetNo.isBlank()) throw new IllegalArgumentException("柜机号为空");
        PayAuthEntry auth = payments.preAuth(cabinetNo, ConsumerContext.userNo(),
                DEPOSIT_FREEZE, "AED", null, "DEPFREE-" + cabinetNo + "-" + ConsumerContext.userNo());
        return Map.of("authNo", auth.authNo(), "frozen", auth.freezeAmount());
    }

    /**
     * 补缴欠费单（{@code scene}=RENT/BUYOUT/…）。桩通道即时 PAID → 映射 c-app 的 SUCCESS；
     * 真实收银台参数（cashierParams）待 nearpay 接入（ADR-005），当前恒 null。
     */
    @PostMapping("/pay")
    public Map<String, Object> pay(@RequestBody ai.neargo.sharehub.trade.dto.MpTradeReqs.PayReq body) {
        String orderNo = body == null ? null : body.orderNo();
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("充值等无单支付待充值单流程接入，当前仅支持按订单支付（orderNo 必填）");
        }
        RentOrder o = orders.detailForConsumer(orderNo);
        ConsumerContext.assertOwner(o.cUserNo());
        /*
         * 金额**只从订单上取，不收请求体**。此前是「body 里有 amount 就用它」，
         * 而 PaymentServiceImpl.pay 只做 requirePositive、从不与订单应付比对 ——
         * POST /mp/trade/pay {orderNo, amount: 0.01} 就能给应付 17 的单记一笔 0.01，
         * 而幂等键是 "PAY-" + orderNo，先到的那笔把这张单的支付金额锁死。
         * 隔壁 recharge 的注释早把这条规矩写清楚了：收前端传的金额，
         * 「充 1 元到账 100」就是一次普通的改参数请求。
         */
        // 2026-09-27 起这个口就是**补缴**（C-BL-04）：结算时已自动请款，只有欠费单才需要再付。
        // 此前它不看订单状态、也不幂等 —— 对同一张单调几次就收几次钱。状态校验与金额在 repay 里。
        PayOrderEntry p = orders.repay(orderNo, body.payType());
        String status = "PAID".equals(p.status()) ? "SUCCESS"
                : "FAILED".equals(p.status()) ? "FAILED" : "PENDING";
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("payNo", p.payNo());
        out.put("status", status);
        out.put("cashierParams", null);   // nearpay 收银台参数，接入后回填
        return out;
    }
}
