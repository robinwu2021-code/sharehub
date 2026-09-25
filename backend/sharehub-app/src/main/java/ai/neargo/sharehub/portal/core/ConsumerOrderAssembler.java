package ai.neargo.sharehub.portal.core;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.loc.LocService;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.portal.core.dto.MpTradeDtos.ConsumerOrderVO;
import ai.neargo.sharehub.portal.core.dto.MpTradeDtos.FeeItemVO;
import ai.neargo.sharehub.portal.core.dto.MpTradeDtos.OrderStepVO;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderEvent;
import ai.neargo.sharehub.trade.order.service.OrderEventLogService;
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
    private static final String FEE_WAIVE = "WAIVE";
    private static final String FEE_COMPENSATE = "COMPENSATE";
    private static final String FEE_DEPOSIT = "DEPOSIT";

    private final CabinetService cabinets;
    private final LocService loc;
    private final OrderEventLogService eventLog;

    public ConsumerOrderAssembler(CabinetService cabinets, LocService loc, OrderEventLogService eventLog) {
        this.cabinets = cabinets;
        this.loc = loc;
        this.eventLog = eventLog;
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

    /** 详情投影：带费用明细与状态时间线。 */
    public ConsumerOrderVO detail(RentOrder o) {
        if (o == null) return null;
        Names names = namesOf(List.of(o));
        return of(o, names, fees(o), timeline(o.orderNo()));
    }

    /** 单条但不带时间线（进行中订单的顶部条，只要能渲染一行就够）。 */
    public ConsumerOrderVO one(RentOrder o) {
        if (o == null) return null;
        return of(o, namesOf(List.of(o)), null, null);
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
        add(out, FEE_RENT, BigDecimal.valueOf(o.feeAmount()));
        // 减免记成负数：明细逐项相加要等于实付，否则这张表看着就不像账
        if (o.waivedAmount() != null && o.waivedAmount().signum() != 0) {
            add(out, FEE_WAIVE, o.waivedAmount().negate());
        }
        add(out, FEE_COMPENSATE, o.compensateAmount());
        add(out, FEE_DEPOSIT, BigDecimal.valueOf(o.depositAmount()));
        return out;
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
            Map<String, String> cabinetToSite = new HashMap<>();
            // 注：按当前规模（百级机柜）一次拉全量即可；真长起来要换成按 cabinetNo 批量查
            for (Cabinet c : cabinets.page(1, 500, null, null, null).getList()) {
                if (c.cabinetNo() != null) cabinetToSite.put(c.cabinetNo(), c.siteNo());
            }
            Map<String, String> siteToName = new HashMap<>();
            for (Site s : loc.pageSites(1, 200, null, false).getList()) {
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
