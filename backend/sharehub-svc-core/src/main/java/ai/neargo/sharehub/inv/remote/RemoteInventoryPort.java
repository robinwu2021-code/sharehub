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

    /** 远端写入的恢复日志：调用前记意图与步骤，本地回滚后按同一键续做（对接明细 §9.3）。 */
    private ai.neargo.sharehub.inv.service.InventoryOperationJournal journal;

    @org.springframework.beans.factory.annotation.Autowired
    void setJournal(ai.neargo.sharehub.inv.service.InventoryOperationJournal journal) {
        this.journal = journal;
    }

    /** 入库意图（用 record 而不是 Map：日志按内容哈希判「原样重试」，字段顺序必须稳定）。 */
    record InboundIntent(String intakeNo, String itemType, String itemNo, String warehouseNo, String qcNo, String purpose) {
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
        String purpose = inboundPurpose(h == null ? null : h.getKind());
        remote(() -> {
            // 稳定键：一张入库单上的一台设备只入一次库（对接明细 A10）。本地回滚后原样重试按同一键续做 ——
            // 已完成的步骤不再发，结果不明的按原幂等键重放（库存服务同键同体返回原结果），不会多建一张入库单
            var op = journal.lock("INTAKE_INBOUND", intakeNo + ":" + itemNo,
                    journal.payload(new InboundIntent(intakeNo, itemType, itemNo, warehouseNo, qcNo, purpose)), "SERVICE", "II");
            if ("COMPLETED".equals(op.phase())) return null;
            String loc = journal.reference(() -> links.locationId(api, warehouseNo));
            String item = journal.reference(() -> links.itemId(api, itemType));
            // 期初带依据：库存服务的 basis 是 ≤ 32 字符的依据编号（不是说明文字），给入库单号，
            // 依据全文在本地入库单（opening_basis）。附件宿主暂无上传入口，生产档案要求附件时会被拒（开发方案 D5）
            String basis = "OPENING".equals(purpose) ? intakeNo : null;
            String doc = journal.step(op, "IN_CREATE", () -> api.createInbound(loc, purpose, intakeNo, basis, List.of(), "到仓待检 " + intakeNo,
                    List.of(new InventoryApi.InboundLine(item, null, null, List.of(new InventoryApi.Serial(itemNo, qcNo, null)))),
                    journal.stepKey(op, "IN_CREATE")));
            journal.step(op, "IN_POST", () -> {
                api.postInbound(doc, journal.stepKey(op, "IN_POST"));
                return doc;
            });
            // 回仓的设备撤机时已解绑、签收进仓时已离开出库池 —— 业务侧关联此刻已解除，当场确认解冻
            if ("RETURN".equals(purpose)) journal.step(op, "IN_CLEAR", () -> {
                api.businessClearance(doc, journal.stepKey(op, "IN_CLEAR"));
                return doc;
            });
            journal.complete(op);
            return doc;
        });
    }

    @Override
    @Transactional
    public List<Cabinet> dispatch(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos, String requestKey) {
        Map<String, String> fromWh = warehousesOf(cabinetNos);
        List<Cabinet> out = local.dispatch(agentNo, siteNo, receiverNo, cabinetNos, requestKey);
        String person = receiverNo != null && !receiverNo.isBlank() ? receiverNo.trim() : operator();
        String site = siteNo == null || siteNo.isBlank() ? null : siteNo.trim();
        remote(() -> {
            // 恢复日志（对接明细 C02）：按源仓一张 ISSUE 出库单，步骤键稳定；丢响应后原样重试续做，不会重复出库
            var op = op("DEVICE_ISSUE", requestKey, new DispatchIntent(agentNo, site, receiverNo, cabinetNos), "DI");
            if (COMPLETED.equals(op.phase())) return null;
            Map<String, String> targets = journal.reference(() -> links.targets(api, agentNo, site, person));
            Map<String, List<String>> byWh = new java.util.TreeMap<>();
            fromWh.forEach((no, wh) -> {
                if (wh == null) throw BizException.conflict("error.inventory.not_in_warehouse", no);
                byWh.computeIfAbsent(wh, x -> new ArrayList<>()).add(no);
            });
            int i = 0;
            for (var e : byWh.entrySet()) {
                String code = "OUT_" + (i++);
                String loc = journal.reference(() -> links.locationId(api, e.getKey()));
                String doc = journal.step(op, code, () -> api.createOutbound(loc, "ISSUE", targets, "出库给 " + agentNo, e.getValue(),
                        journal.stepKey(op, code)));
                journal.step(op, code + "_POST", () -> {
                    api.postOutbound(doc, journal.stepKey(op, code + "_POST"));
                    return doc;
                });
            }
            // 不指定接收人 = 经办人当面交付，当场领取（与本地「出库即签收」一致）
            if (receiverNo == null || receiverNo.isBlank()) claimStep(op, "CLAIM", cabinetNos);
            journal.complete(op);
            return null;
        });
        return out;
    }

    @Override
    @Transactional
    public List<Cabinet> claim(List<String> cabinetNos, String requestKey) {
        List<Cabinet> out = local.claim(cabinetNos, requestKey);
        remote(() -> {
            var op = op("DEVICE_CLAIM", requestKey, new ClaimIntent(cabinetNos), "DC");
            if (COMPLETED.equals(op.phase())) return null;
            claimStep(op, "CLAIM", cabinetNos);
            journal.complete(op);
            return null;
        });
        return out;
    }

    @Override
    @Transactional
    public List<Cabinet> redirect(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason, String requestKey) {
        List<Cabinet> out = local.redirect(cabinetNos, agentNo, siteNo, receiverNo, reason, requestKey);
        String person = receiverNo != null && !receiverNo.isBlank() ? receiverNo.trim() : operator();
        String site = siteNo == null || siteNo.isBlank() ? null : siteNo.trim();
        remote(() -> {
            var op = op("DEVICE_REDIRECT", requestKey, new RedirectIntent(cabinetNos, agentNo, site, receiverNo, reason), "DR");
            if (COMPLETED.equals(op.phase())) return null;
            Map<String, String> targets = journal.reference(() -> links.targets(api, agentNo, site, person));
            for (int i = 0; i < out.size(); i++) {
                Cabinet c = out.get(i);
                String n = String.valueOf(i);
                // 只能改「待领取」的：已领取的先释放（同单其余设备补领回来），改完按本地签收状态再领取
                if (CLAIMED.equals(head(c.cabinetNo()).status())) releaseOne(op, n, c.cabinetNo());
                InventoryApi.DispatchView d = head(c.cabinetNo());
                journal.step(op, "REDIRECT_" + n, () -> {
                    api.redirect(d.dispatchId(), targets, reason, journal.stepKey(op, "REDIRECT_" + n));
                    return d.dispatchId();
                });
                if (c.issueClaimedAt() != null) claimStep(op, "CLAIM_" + n, List.of(c.cabinetNo()));
            }
            journal.complete(op);
            return null;
        });
        return out;
    }

    /**
     * 出库撤销（对接明细 C13）：库存服务撤回只会冲回原出库仓，所以本地也只回原出库仓 ——
     * 选了别的仓直接拒绝（原先本地回所选仓、库存服务回原仓，只记一条 WARN，两边从此对不上）。实物退到别的仓走退仓。
     */
    @Override
    @Transactional
    public List<Cabinet> returnToWarehouse(List<String> cabinetNos, String warehouseNo, String reason, String requestKey) {
        if (cabinetNos == null || cabinetNos.isEmpty()) return local.returnToWarehouse(cabinetNos, warehouseNo, reason, requestKey);
        // 原出库仓 = 库存服务出库池记录的库位；按原仓分组交给本地（本地状态校验仍由它做）
        Map<String, List<String>> byOrigin = new java.util.TreeMap<>();
        for (String raw : cabinetNos) {
            if (raw == null || raw.isBlank()) continue;
            String no = raw.trim();
            String origin = remote(() -> links.warehouseNoOf(head(no).locationId()));
            if (warehouseNo != null && !warehouseNo.isBlank() && !warehouseNo.trim().equals(origin))
                throw BizException.conflict("error.inventory.revoke_other_warehouse", no, origin);
            byOrigin.computeIfAbsent(origin, x -> new ArrayList<>()).add(no);
        }
        List<Cabinet> out = new ArrayList<>();
        byOrigin.forEach((wh, nos) -> out.addAll(local.returnToWarehouse(nos, wh, reason, requestKey)));
        remote(() -> {
            var op = op("DEVICE_REVOKE", requestKey, new RevokeIntent(cabinetNos, reason), "DV");
            if (COMPLETED.equals(op.phase())) return null;
            for (int i = 0; i < out.size(); i++) {
                Cabinet c = out.get(i);
                String n = String.valueOf(i);
                if (CLAIMED.equals(head(c.cabinetNo()).status())) releaseOne(op, n, c.cabinetNo());
                InventoryApi.DispatchView d = head(c.cabinetNo());
                journal.step(op, "REVOKE_" + n, () -> {
                    api.revoke(d.dispatchId(), reason, journal.stepKey(op, "REVOKE_" + n));
                    return d.dispatchId();
                });
            }
            journal.complete(op);
            return null;
        });
        return out;
    }

    @Override
    @Transactional
    public void transferShip(String transferNo, String itemType, String fromWarehouseNo, List<String> itemNos) {
        if (fromWarehouseNo == null || fromWarehouseNo.isBlank() || itemNos.isEmpty()) return;
        remote(() -> {
            // 恢复日志（对接明细 B08）：键 = 调拨单号（一张单只发一次），丢响应原样重试续做、不会重复调出
            var op = journal.lock("TRANSFER_SHIP", transferNo, journal.payload(new TransferIntent(transferNo, itemType, fromWarehouseNo, itemNos)),
                    "SERVICE", "TS");
            if (COMPLETED.equals(op.phase())) return null;
            String loc = journal.reference(() -> links.locationId(api, fromWarehouseNo));
            String doc = journal.step(op, "OUT", () -> api.createOutbound(loc, "TRANSFER_OUT", Map.of(), "调拨发货 " + transferNo,
                    itemNos, journal.stepKey(op, "OUT")));
            journal.step(op, "OUT_POST", () -> {
                api.postOutbound(doc, journal.stepKey(op, "OUT_POST"));
                return doc;
            });
            journal.complete(op);
            return doc;
        });
    }

    @Override
    @Transactional
    public void transferReceive(String transferNo, String itemType, String fromWarehouseNo, String toWarehouseNo, List<String> itemNos) {
        if (toWarehouseNo == null || toWarehouseNo.isBlank() || itemNos.isEmpty()) return;
        boolean fromWarehouse = fromWarehouseNo != null && !fromWarehouseNo.isBlank();
        // 发货时有没有在库存服务调出：看发货的日志是否完成（对接明细 B09：不能从「没有映射」推断没执行）；
        // xfer: 映射是日志之前发出的单留下的，兼容
        boolean shippedRemotely = COMPLETED.equals(journal.phaseOf("TRANSFER_SHIP", transferNo)) || links.get("xfer:" + transferNo) != null;
        if (fromWarehouse && !shippedRemotely) {
            // 切引擎前发出的单：库存服务里这批还在调出仓，再调入会撞「已在库」。不拦签收，账由盘点纠正
            log.warn("调拨发货时没有在库存服务调出 transferNo={} from={} to={} items={}：本次签收不同步，请对两仓盘点纠正",
                    transferNo, fromWarehouseNo, toWarehouseNo, itemNos);
            return;
        }
        remote(() -> {
            var op = journal.lock("TRANSFER_RECEIVE", transferNo, journal.payload(new TransferIntent(transferNo, itemType, toWarehouseNo, itemNos)),
                    "SERVICE", "TR");
            if (COMPLETED.equals(op.phase())) return null;
            List<InventoryApi.Serial> serials = itemNos.stream()
                    .map(no -> new InventoryApi.Serial(no, "XFER-" + transferNo + "-" + no, null)).toList();
            String loc = journal.reference(() -> links.locationId(api, toWarehouseNo));
            String item = journal.reference(() -> links.itemId(api, itemType));
            String doc = journal.step(op, "IN", () -> api.createInbound(loc, "TRANSFER_IN", transferNo, "调拨签收 " + transferNo,
                    List.of(new InventoryApi.InboundLine(item, null, null, serials)), journal.stepKey(op, "IN")));
            journal.step(op, "IN_POST", () -> {
                api.postInbound(doc, journal.stepKey(op, "IN_POST"));
                return doc;
            });
            journal.complete(op);
            return doc;
        });
    }

    // —— 内部 ——

    /** 入库单类型 → 库存服务入库用途：期初建账是 OPENING（对接明细 A07），撤机回仓是 RETURN，其余到货是 PURCHASE。 */
    static String inboundPurpose(String intakeKind) {
        if ("OPENING".equals(intakeKind)) return "OPENING";
        if ("RETURN".equals(intakeKind)) return "RETURN";
        return "PURCHASE";
    }

    private static final String COMPLETED = "COMPLETED";

    /** 出库池动作的意图（record：日志按内容哈希判「原样重试」，字段顺序须稳定）。 */
    record DispatchIntent(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos) {
    }

    record ClaimIntent(List<String> cabinetNos) {
    }

    record RedirectIntent(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason) {
    }

    record RevokeIntent(List<String> cabinetNos, String reason) {
    }

    /** 调拨一侧：发货记调出仓、签收记目的仓与实收的件。 */
    record TransferIntent(String transferNo, String itemType, String warehouseNo, List<String> itemNos) {
    }

    /** 登记一次库存写操作。requestKey 空 = 生成一次性的：同一请求内的步骤仍记录，但跨请求不能续做。 */
    private ai.neargo.sharehub.inv.service.InventoryOperationJournal.Operation op(String kind, String requestKey, Object intent, String bizPrefix) {
        String key = requestKey == null || requestKey.isBlank() ? kind + "-" + UUID.randomUUID() : requestKey.trim();
        if (key.length() > 128) throw BizException.badRequest("error.common.invalid_value", "requestKey");
        return journal.lock(kind, key, journal.payload(intent), "SERVICE", bizPrefix);
    }

    /**
     * 领取一步：这些设备里还「待领取」的，一次领取。步骤之前发过（结果不明）就先按原请求号查回 ——
     * 库存服务可能已经领走了，此时按当前状态重算待领取清单会是空的，等于把这次领取丢了。
     */
    private void claimStep(ai.neargo.sharehub.inv.service.InventoryOperationJournal.Operation op, String code, List<String> cabinetNos) {
        boolean sentBefore = journal.attempted(op, code);
        String key = journal.stepKey(op, code);
        String claimNo = journal.step(op, code, () -> {
            if (sentBefore) {
                Optional<InventoryApi.Claim> found = api.claimByRequest(key);
                if (found.isPresent()) return found.get().claimNo();
            }
            List<String> ids = new ArrayList<>();
            for (String no : cabinetNos) {
                InventoryApi.DispatchView d = head(no);
                if (AVAILABLE.equals(d.status())) ids.add(d.dispatchId());
            }
            return ids.isEmpty() ? "" : api.claim(ids, String.join(",", cabinetNos), key).claimNo();
        });
        if (claimNo != null && !claimNo.isEmpty()) for (String no : cabinetNos) links.put("claim:" + no, claimNo);
    }

    /**
     * 释放一台的领取。库存服务的释放是整单的（部分释放已列入宿主接口需求 #6）：
     * 同一领取单里的其余设备释放后立刻补领回来，不然它们在库存服务里变回「待领取」，本地却仍是已签收。
     */
    private void releaseOne(ai.neargo.sharehub.inv.service.InventoryOperationJournal.Operation op, String n, String cabinetNo) {
        String claimNo = links.get("claim:" + cabinetNo);
        if (claimNo == null || claimNo.isEmpty()) throw BizException.conflict("error.inventory.claim_unknown", cabinetNo);
        List<String> others = links.listByPrefix("claim:").stream().filter(x -> claimNo.equals(x.getV()))
                .map(x -> x.getK().substring("claim:".length())).filter(x -> !x.equals(cabinetNo)).sorted().toList();
        journal.step(op, "REL_" + n, () -> {
            api.releaseClaim(claimNo, journal.stepKey(op, "REL_" + n));
            return claimNo;
        });
        links.put("claim:" + cabinetNo, "");
        if (!others.isEmpty()) claimStep(op, "RECLAIM_" + n, others);
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

}
