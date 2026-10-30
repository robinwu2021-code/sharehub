package ai.neargo.sharehub.inv.remote;

import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.inv.entity.InvIntake;
import ai.neargo.sharehub.inv.mapper.InvIntakeMapper;
import ai.neargo.sharehub.inv.port.InventoryPort;
import ai.neargo.sharehub.inv.port.LocalInventoryPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 独立进销存引擎（S3.1，D §2.4）：先在本地做校验与投影（同事务），再调库存服务记账；库存服务拒绝或不可达 →
 * 整笔回滚，本地也不动。本地投影仍由 {@link LocalInventoryPort} 写，界面与绑定约束不用改。
 *
 * <ul>
 *   <li>出库 = ISSUE 出库单（目标 MERCHANT / SITE / PERSON）→ 出库池；不指定接收人时以经办人为 PERSON 并当场领取（A5：出库即签收）；</li>
 *   <li>签收 = 领取（claim），领取单号按设备记下，退回时先释放；</li>
 *   <li>出库调整 = 重定向（已领取的先释放、改完再领取）；</li>
 *   <li>出库池退回 = 释放 + 撤回（库存服务自动冲回原仓）；</li>
 *   <li>待检入库 = PURCHASE / RETURN 入库，质检记录号即 gateRef；RETURN 过账后即做业务确认（撤机时已解绑）。</li>
 *   <li>仓到仓调拨 = 发货 TRANSFER_OUT、签收 TRANSFER_IN（实收的件，gateRef = XFER-调拨单号-设备号）。</li>
 * </ul>
 */
@Component
public class RemoteInventoryPort implements InventoryPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteInventoryPort.class);
    private static final String AVAILABLE = "AVAILABLE";
    private static final String CLAIMED = "CLAIMED";

    private final LocalInventoryPort local;
    private final InventoryLinks links;
    private final CabinetMapper cabinets;
    private final InvIntakeMapper intakes;
    private InventoryApi api;

    public RemoteInventoryPort(LocalInventoryPort local, InventoryLinks links, CabinetMapper cabinets, InvIntakeMapper intakes,
                               HttpInventoryApi api) {
        this.local = local;
        this.links = links;
        this.cabinets = cabinets;
        this.intakes = intakes;
        this.api = api;
    }

    /** 场景测试换成内存 Fake（S1.2）。 */
    public void useApi(InventoryApi api) {
        this.api = api;
    }

    public InventoryApi api() {
        return api;
    }

    @Override
    public String engine() {
        return "SERVICE";
    }

    @Override
    @Transactional
    public void inbound(String itemType, String itemNo, String warehouseNo, String qcNo, String intakeNo) {
        local.inbound(itemType, itemNo, warehouseNo, qcNo, intakeNo);
        InvIntake h = intakes.selectOne(new LambdaQueryWrapper<InvIntake>().eq(InvIntake::getIntakeNo, intakeNo).last("limit 1"));
        String purpose = h != null && "RETURN".equals(h.getKind()) ? "RETURN" : "PURCHASE";
        remote(() -> {
            String doc = api.createInbound(links.locationId(api, warehouseNo), purpose, intakeNo, "到仓待检 " + intakeNo,
                    List.of(new InventoryApi.InboundLine(links.itemId(api, itemType), null, null,
                            List.of(new InventoryApi.Serial(itemNo, qcNo, null)))), key("in", intakeNo + ":" + itemNo));
            api.postInbound(doc, key("in-post", doc));
            // 回仓的设备撤机时已解绑、签收进仓时已离开出库池 —— 业务侧关联此刻已解除，当场确认解冻
            if ("RETURN".equals(purpose)) api.businessClearance(doc, key("in-clear", doc));
            return doc;
        });
    }

    @Override
    @Transactional
    public List<Cabinet> dispatch(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos) {
        Map<String, String> fromWh = warehousesOf(cabinetNos);
        List<Cabinet> out = local.dispatch(agentNo, siteNo, receiverNo, cabinetNos);
        String person = receiverNo != null && !receiverNo.isBlank() ? receiverNo.trim() : operator();
        remote(() -> {
            Map<String, String> targets = links.targets(api, agentNo, siteNo == null || siteNo.isBlank() ? null : siteNo.trim(), person);
            Map<String, List<String>> byWh = new LinkedHashMap<>();
            fromWh.forEach((no, wh) -> byWh.computeIfAbsent(wh, x -> new ArrayList<>()).add(no));
            for (var e : byWh.entrySet()) {
                if (e.getKey() == null) throw BizException.conflict("error.inventory.not_in_warehouse", String.join("、", e.getValue()));
                String doc = api.createOutbound(links.locationId(api, e.getKey()), "ISSUE", targets, "出库给 " + agentNo,
                        e.getValue(), key("out", agentNo));
                api.postOutbound(doc, key("out-post", doc));
            }
            // 不指定接收人 = 经办人当面交付，当场领取（与本地「出库即签收」一致）
            if (receiverNo == null || receiverNo.isBlank()) claimRemote(cabinetNos);
            return null;
        });
        return out;
    }

    @Override
    @Transactional
    public List<Cabinet> claim(List<String> cabinetNos) {
        List<Cabinet> out = local.claim(cabinetNos);
        remote(() -> {
            claimRemote(cabinetNos);
            return null;
        });
        return out;
    }

    @Override
    @Transactional
    public List<Cabinet> redirect(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason) {
        Map<String, Boolean> wasClaimed = new LinkedHashMap<>();
        cabinetNos.forEach(no -> wasClaimed.put(no, links.get("claim:" + no) != null));
        List<Cabinet> out = local.redirect(cabinetNos, agentNo, siteNo, receiverNo, reason);
        String person = receiverNo != null && !receiverNo.isBlank() ? receiverNo.trim() : operator();
        remote(() -> {
            Map<String, String> targets = links.targets(api, agentNo, siteNo == null || siteNo.isBlank() ? null : siteNo.trim(), person);
            for (Cabinet c : out) {
                InventoryApi.DispatchView d = head(c.cabinetNo());
                // 只能改「待领取」的：已领取的先释放，改完按本地签收状态再领取
                if (CLAIMED.equals(d.status())) release(c.cabinetNo());
                api.redirect(d.dispatchId(), targets, reason, key("redirect", d.dispatchId()));
                if (c.issueClaimedAt() != null) claimRemote(List.of(c.cabinetNo()));
            }
            return null;
        });
        return out;
    }

    @Override
    @Transactional
    public List<Cabinet> returnToWarehouse(List<String> cabinetNos, String warehouseNo, String reason) {
        List<Cabinet> out = local.returnToWarehouse(cabinetNos, warehouseNo, reason);
        remote(() -> {
            for (Cabinet c : out) {
                InventoryApi.DispatchView d = head(c.cabinetNo());
                if (CLAIMED.equals(d.status())) release(c.cabinetNo());
                // 撤回由库存服务冲回原出库仓；与本地所选回仓仓不同时，以盘点 / 调拨纠正
                api.revoke(d.dispatchId(), reason, key("revoke", d.dispatchId()));
                if (!java.util.Objects.equals(links.locationId(api, c.warehouseNo()), d.locationId())) {
                    log.warn("出库池退回仓与原出库仓不同 cabinetNo={} 本地回仓={} 库存服务冲回={}：请在库存服务做调拨纠正",
                            c.cabinetNo(), c.warehouseNo(), d.locationId());
                }
            }
            return null;
        });
        return out;
    }

    @Override
    @Transactional
    public void transferShip(String transferNo, String itemType, String fromWarehouseNo, List<String> itemNos) {
        if (fromWarehouseNo == null || fromWarehouseNo.isBlank() || itemNos.isEmpty()) return;
        remote(() -> {
            String doc = api.createOutbound(links.locationId(api, fromWarehouseNo), "TRANSFER_OUT", Map.of(), "调拨发货 " + transferNo,
                    itemNos, key("xfer-out", transferNo));
            api.postOutbound(doc, key("xfer-out-post", doc));
            // 签收时据此判断「发货时库存服务有没有调出」
            links.put("xfer:" + transferNo, doc);
            return doc;
        });
    }

    @Override
    @Transactional
    public void transferReceive(String transferNo, String itemType, String fromWarehouseNo, String toWarehouseNo, List<String> itemNos) {
        if (toWarehouseNo == null || toWarehouseNo.isBlank() || itemNos.isEmpty()) return;
        boolean fromWarehouse = fromWarehouseNo != null && !fromWarehouseNo.isBlank();
        if (fromWarehouse && links.get("xfer:" + transferNo) == null) {
            // 切引擎前发出的单：库存服务里这批还在调出仓，再调入会撞「已在库」。不拦签收，账由盘点纠正
            log.warn("调拨发货时引擎还是 LOCAL，库存服务没有调出 transferNo={} from={} to={} items={}：本次签收不同步，请对两仓盘点纠正",
                    transferNo, fromWarehouseNo, toWarehouseNo, itemNos);
            return;
        }
        remote(() -> {
            List<InventoryApi.Serial> serials = itemNos.stream()
                    .map(no -> new InventoryApi.Serial(no, "XFER-" + transferNo + "-" + no, null)).toList();
            String doc = api.createInbound(links.locationId(api, toWarehouseNo), "TRANSFER_IN", transferNo, "调拨签收 " + transferNo,
                    List.of(new InventoryApi.InboundLine(links.itemId(api, itemType), null, null, serials)), key("xfer-in", transferNo));
            api.postInbound(doc, key("xfer-in-post", doc));
            return doc;
        });
    }

    // —— 内部 ——

    private void claimRemote(List<String> cabinetNos) {
        List<String> ids = new ArrayList<>();
        for (String no : cabinetNos) {
            InventoryApi.DispatchView d = head(no);
            if (AVAILABLE.equals(d.status())) ids.add(d.dispatchId());
        }
        if (ids.isEmpty()) return;
        InventoryApi.Claim claim = api.claim(ids, String.join(",", cabinetNos), key("claim", String.join(",", ids)));
        for (String no : cabinetNos) links.put("claim:" + no, claim.claimNo());
    }

    private void release(String cabinetNo) {
        String claimNo = links.get("claim:" + cabinetNo);
        if (claimNo == null) throw BizException.conflict("error.inventory.claim_unknown", cabinetNo);
        api.releaseClaim(claimNo, key("release", claimNo + ":" + cabinetNo));
        links.put("claim:" + cabinetNo, "");
    }

    /** 该设备当前在出库池里的顶层记录（待领取或已领取）。 */
    private InventoryApi.DispatchView head(String cabinetNo) {
        Optional<InventoryApi.DispatchView> d = api.dispatchesBySerial(cabinetNo).stream()
                .filter(v -> v.parentDispatchId() == null && (AVAILABLE.equals(v.status()) || CLAIMED.equals(v.status())))
                .findFirst();
        return d.orElseThrow(() -> BizException.conflict("error.inventory.dispatch_missing", cabinetNo));
    }

    private Map<String, String> warehousesOf(List<String> cabinetNos) {
        Map<String, String> m = new LinkedHashMap<>();
        if (cabinetNos == null) return m;
        for (String raw : cabinetNos) {
            if (raw == null || raw.isBlank()) continue;
            String no = raw.trim();
            DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, no).last("limit 1"));
            m.put(no, c == null ? null : c.getWarehouseNo());
        }
        return m;
    }

    /** 库存服务拒绝 / 不可达 → 业务异常（整笔回滚），带对方错误码与参数，日志能直接定位。 */
    private <T> T remote(Supplier<T> call) {
        InventoryCalls.requireConfigured(api);
        return InventoryCalls.call(call);
    }

    private static String operator() {
        String me = SecurityUtils.userNo();
        return me == null ? "SYSTEM" : me;
    }

    /** 幂等键：一次业务动作一个键（同请求内重试安全；跨请求的重复操作本地状态机已拦）。≤ 64 字符。 */
    private static String key(String kind, String ref) {
        return kind + "-" + UUID.randomUUID();
    }
}
