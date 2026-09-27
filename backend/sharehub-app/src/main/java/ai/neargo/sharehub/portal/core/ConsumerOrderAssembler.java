package ai.neargo.sharehub.portal.core;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.loc.LocService;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.portal.core.dto.MpTradeDtos.ConsumerOrderVO;
import ai.neargo.sharehub.portal.core.dto.MpTradeDtos.FeeItemVO;
import ai.neargo.sharehub.portal.core.dto.MpTradeDtos.OrderStepVO;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.FeeEstimate;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.trade.service.RentOrderService;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderEvent;
import ai.neargo.sharehub.trade.order.service.OrderEventLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单 → C 端订单投影（{@code trade} × {@code dev} × {@code loc} × {@code ord_event_log}）。
 *
 * <p>组合发生在 portal 层，与找柜 BFF 同一口径（见 {@code MpNearbyController} 类注释）。
 *
 * <p><b>门店名要批量取</b>：机柜与站点各一次查询，与订单条数无关。
 * 逐单去查是 N+1，而订单列表恰恰是最长的那个列表。
 */
@Component
public class ConsumerOrderAssembler {

    /** 费用明细的类型码；文案由端上按语言映射（见 {@link FeeItemVO}）。 */
    private static final String FEE_RENT = "RENT";
    private static final String FEE_COUPON = "COUPON";
    private static final String FEE_WAIVE = "WAIVE";
    private static final String FEE_COMPENSATE = "COMPENSATE";
    private static final String FEE_MEMBER = "MEMBER";

    private final CabinetService cabinets;
    private final LocService loc;
    private final OrderEventLogService eventLog;
    private final ObjectProvider<RentOrderService> orders;

    /**
     * {@code orders} 用 {@link ObjectProvider} 延迟取：{@code RentOrderService} 的实现
     * 与本类同在一个上下文里，直接构造注入会和它的依赖链绕成环（它已经依赖了一串 dev/loc 服务）。
     * 这里只在「单条投影且进行中」时用一次，延迟解析代价可以忽略。
     */
    public ConsumerOrderAssembler(CabinetService cabinets, LocService loc, OrderEventLogService eventLog,
                                  ObjectProvider<RentOrderService> orders) {
        this.cabinets = cabinets;
        this.loc = loc;
        this.eventLog = eventLog;
        this.orders = orders;
    }

    /** 列表投影：不带 fees/timeline（见 {@link ConsumerOrderVO}）。 */
    public List<ConsumerOrderVO> list(List<RentOrder> orders) {
        if (orders == null || orders.isEmpty()) return List.of();
        Names names = namesOf(orders);
        List<ConsumerOrderVO> out = new ArrayList<>();
        for (RentOrder o : orders) {
            out.add(of(o, names, null, null));
        }
        return out;
    }

    /** 详情投影：带费用明细、状态时间线，进行中的单还带实时试算。 */
    public ConsumerOrderVO detail(RentOrder o) {
        if (o == null) return null;
        Names names = namesOf(List.of(o));
        return withEstimate(of(o, names, fees(o), timeline(o.orderNo())), o);
    }

    /** 单条但不带时间线（进行中订单的顶部条，只要能渲染一行就够）—— 带试算，那张卡上的数就是它。 */
    public ConsumerOrderVO one(RentOrder o) {
        if (o == null) return null;
        return withEstimate(of(o, namesOf(List.of(o)), null, null), o);
    }

    /**
     * 补上进行中的实时试算。非进行中 {@code estimateNow} 返回 null，原样返回。
     *
     * <p>这三个字段存在的理由：{@code ord_order.duration_min}/{@code fee_amount}
     * <b>只在归还那一刻写入</b> —— 进行中的单它们恒为 0，而 C 端「实时计时计费」
     * 照着渲染就是一个不动的 0，用户唯一的判断方式是退出重进。界面不报错，只是什么都没发生。
     */
    private ConsumerOrderVO withEstimate(ConsumerOrderVO vo, RentOrder o) {
        RentOrderService svc = orders.getIfAvailable();
        if (svc == null) return vo;
        FeeEstimate est = svc.estimateNow(o.orderNo());
        if (est == null) return vo;
        return new ConsumerOrderVO(vo.orderNo(), vo.cUserNo(), vo.status(), vo.cabinetNo(), vo.siteName(),
                vo.returnCabinetNo(), vo.returnSiteName(), vo.powerbankNo(), vo.locationName(),
                vo.rentStartAt(), vo.rentEndAt(), vo.durationMin(), vo.feeAmount(), vo.depositAmount(),
                vo.currency(), vo.fees(), vo.timeline(),
                est.payable(), est.durationMin(), est.capped());
    }

    private ConsumerOrderVO of(RentOrder o, Names names, List<FeeItemVO> fees, List<OrderStepVO> timeline) {
        return new ConsumerOrderVO(
                o.orderNo(), o.cUserNo(), o.status(),
                o.cabinetNo(), names.siteNameOf(o.cabinetNo()),
                o.returnCabinetNo(), names.siteNameOf(o.returnCabinetNo()),
                o.powerbankNo(), o.locationName(),
                o.rentStartAt(), o.rentEndAt(), o.durationMin(),
                BigDecimal.valueOf(o.feeAmount()), BigDecimal.valueOf(o.depositAmount()), o.currency(),
                fees, timeline);
    }

    /**
     * 费用明细。**由订单上已有的列派生，不新建存储** ——
     * 只放非零项：给每单挂一条「减免 0.00」是纯噪音。
     *
     * <p>押金单列一项而不是并进租借费：它是要退回的，和真实支出不是一回事。
     */
    private List<FeeItemVO> fees(RentOrder o) {
        List<FeeItemVO> out = new ArrayList<>();
        BigDecimal payable = BigDecimal.valueOf(o.feeAmount());
        BigDecimal coupon = nz(o.couponAmount());
        BigDecimal waived = nz(o.waivedAmount());
        BigDecimal member = nz(o.memberDiscountAmount());

        /*
         * 租借费这一行是**折扣前**的应收，不是 feeAmount。
         *
         * feeAmount 存的是折后应付，而下面又把券与减免各记一条负数行 ——
         * 直接拿它当租借费，这张表就加不出实付来：
         *   · 免费单：feeAmount=0（0 不出行）、减免 -24 ⇒ 全表只有「优惠减免 -24.00」一行，
         *     读起来像平台欠用户 24，而他实际付了 0；
         *   · 用券单：应收 20、券 3、feeAmount=17 ⇒ 全表只有「租借费 17.00」，
         *     用户特地挑的那张券在账单上**完全不出现**。
         * 两种都不报错，只是账不对。
         *
         * 折扣前应收 = 折后应付 + 券抵扣 + 减免 + 会员折扣，各项都已落列，不必再存一列。
         *
         * **新增一种折扣就必须同时加进这个和式**，否则那笔优惠会被这张表静默吞掉：
         * 会员九折单的表现是「租借费」直接显示折后价、账单上看不到会员两个字，
         * 而合计仍然对得上 —— 没有任何地方会报错。
         */
        add(out, FEE_RENT, payable.add(coupon).add(waived).add(member));
        // 会员折扣 / 券 / 减免记成负数：明细逐项相加要等于实付，否则这张表看着就不像账
        add(out, FEE_MEMBER, member.negate());
        add(out, FEE_COUPON, coupon.negate());
        add(out, FEE_WAIVE, waived.negate());
        add(out, FEE_COMPENSATE, o.compensateAmount());
        /*
         * **押金不进这张表**（2026-09-27 H5 对接实测）。
         *
         * 它此前是这里的最后一行，于是订单详情页上是这样的：
         *     租借费   3.00
         *     押金    50.00
         *     ────────────
         *     合计     3.00
         * —— 端上合计取的是 feeAmount（实付），而明细里多一笔 50。
         * **账在屏幕上加不起来**，正是上面那段注释说要避免的「看着就不像账」。
         *
         * 押金不是费用：它可退，也不构成本单应付。而它本来就有自己的出参字段
         * {@code depositAmount}，端上应当在合计之下单独列一行「押金（可退）」。
         * 放进 fees 等于让同一笔钱在契约里出现两次，而其中一次还会破坏
         * 「逐项相加 = 实付」这条本表唯一的不变量。
         */
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void add(List<FeeItemVO> out, String type, BigDecimal amount) {
        if (amount != null && amount.signum() != 0) out.add(new FeeItemVO(type, amount));
    }

    /**
     * 状态时间线。取 {@code ord_event_log}，只出 {@code toStatus} 与时间。
     *
     * <p>这张表四个 service 一直在写，而读它的调用方长期是 0 个 —— 写进去的东西谁也看不到。
     */
    private List<OrderStepVO> timeline(String orderNo) {
        List<OrderStepVO> out = new ArrayList<>();
        for (OrderEvent e : eventLog.timeline(orderNo)) {
            if (e.toStatus() == null) continue;   // 非状态迁移的事件（纯备注）不进时间线
            out.add(new OrderStepVO(e.toStatus(), e.createdAt()));
        }
        return out;
    }

    // ─────────────────── 机柜 → 站点 → 店名（两次查询，与订单条数无关）───────────────────

    private Names namesOf(List<RentOrder> orders) {
        boolean any = orders.stream().anyMatch(o -> o.cabinetNo() != null || o.returnCabinetNo() != null);
        if (!any) return new Names(Map.of(), Map.of());
        return DataScopeContext.executeWithoutScope(() -> {
            // C 端会话的 spec 是 SELF，而 dev_cabinet / loc_site 没有 c_user_no 锚点 ——
            // 与 MpNearbyController 同一处理：显式豁免，不给运营表编假 SELF 锚点。
            /*
             * **按键批查本页需要的那些**，不是「拉一页全量再建映射」。
             *
             * 此前是 cabinets.page(1, 500) + loc.pageSites(1, 200) —— 两个数都是分页
             * 默认值、不是业务上限。机柜过 500 台或站点过 200 个，靠后那些的订单在
             * 订单列表上**站点名就是空的**，而接口一路 200、页面照常渲染。
             * 原注释写着「按当前规模（百级机柜）一次拉全量即可」——
             * 实测测试库已有 1600+ 站点，那句话早就不成立了，而它不会自己报错。
             */
            java.util.Set<String> cabinetNos = new java.util.HashSet<>();
            for (var o : orders) {
                if (o.cabinetNo() != null) cabinetNos.add(o.cabinetNo());
                if (o.returnCabinetNo() != null) cabinetNos.add(o.returnCabinetNo());
            }
            Map<String, String> cabinetToSite = new HashMap<>();
            for (Cabinet c : cabinets.byNos(cabinetNos)) {
                if (c.cabinetNo() != null) cabinetToSite.put(c.cabinetNo(), c.siteNo());
            }
            Map<String, String> siteToName = new HashMap<>();
            for (Site s : loc.sitesByNos(cabinetToSite.values())) {
                siteToName.put(s.siteNo(), s.name());
            }
            return new Names(cabinetToSite, siteToName);
        });
    }

    private record Names(Map<String, String> cabinetToSite, Map<String, String> siteToName) {
        String siteNameOf(String cabinetNo) {
            if (cabinetNo == null) return null;
            String siteNo = cabinetToSite.get(cabinetNo);
            return siteNo == null ? null : siteToName.get(siteNo);
        }
    }
}
