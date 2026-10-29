package ai.neargo.sharehub.inv.remote;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.QcStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.entity.DevQcRecord;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.QcRecordMapper;
import ai.neargo.sharehub.inv.WarehouseDirectory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 存量迁移（S7，D §2.8）：把本地设备表里的库存账推到独立进销存，切换前跑完。
 *
 * <ul>
 *   <li><b>在仓</b>的柜与宝：在所在仓做 PURCHASE 入库，gateRef = 最近一次通过的质检记录号（存量免检的用 MIG-{设备号}）；</li>
 *   <li><b>仓外</b>的柜（出库池 / 已绑定 / 投放中 / 故障）：先入默认回仓仓，再 ISSUE 出库给其商家（有站点带站点），
 *       本地已签收的当场领取 —— 进销存里就成了「已交付」，绑定与投放仍是业务侧的事；</li>
 *   <li>跳过并列明原因：直营站点的柜（没有商家可出库）、在途、报废、不在柜也不在仓的宝。</li>
 * </ul>
 * 每台迁完在 inventory_sync_state 记 {@code mig:{类型}:{设备号}}（IN = 已入库、OUT = 已出库），重跑只做没做完的那一步。
 * 回滚 = 把 inv.engine 改回 LOCAL：本地投影始终完整，进销存里已迁的数据留作对账。
 */
@Component
public class InventoryMigration {

    private static final Logger log = LoggerFactory.getLogger(InventoryMigration.class);
    private static final String CAB = InventoryLinks.CABINET;
    private static final String PB = InventoryLinks.POWERBANK;

    private final RemoteInventoryPort remote;
    private final InventoryLinks links;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final QcRecordMapper qcRecords;
    private final WarehouseDirectory warehouses;

    public InventoryMigration(RemoteInventoryPort remote, InventoryLinks links, CabinetMapper cabinets, PowerbankMapper powerbanks,
                              QcRecordMapper qcRecords, WarehouseDirectory warehouses) {
        this.remote = remote;
        this.links = links;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.qcRecords = qcRecords;
        this.warehouses = warehouses;
    }

    /** 一台待迁设备：kind = IN（只入库）/ OUT（入库后出库给商家）/ SKIP（不迁，reason 说明）。 */
    public record MigrationCandidate(String itemType, String itemNo, String kind, String warehouseNo, String agentNo, String siteNo,
                            boolean claimed, String reason) {
    }

    public record InventoryMigrationPlan(boolean configured, int toInbound, int toIssue, int skipped, int done,
                                         List<MigrationCandidate> skippedItems) {
    }

    public record MigrationFailure(String itemType, String itemNo, String code, String detail) {
    }

    public record InventoryMigrationResult(int processed, int failed, int remaining, List<MigrationFailure> failures) {
    }

    public InventoryMigrationPlan plan() {
        List<MigrationCandidate> all = candidates();
        int in = 0, out = 0, done = 0;
        List<MigrationCandidate> skipped = new ArrayList<>();
        for (MigrationCandidate c : all) {
            String st = links.get(markKey(c));
            if ("SKIP".equals(c.kind())) skipped.add(c);
            else if (finished(c, st)) done++;
            else if ("IN".equals(c.kind())) in++;
            else out++;
        }
        return new InventoryMigrationPlan(remote.api().configured(), in, out, skipped.size(), done, skipped.size() > 200 ? skipped.subList(0, 200) : skipped);
    }

    /** 迁移最多 limit 台（每台独立：一台失败不影响别的，失败的下次重跑接着做）。 */
    public InventoryMigrationResult run(int limit) {
        if (!remote.api().configured()) throw BizException.conflict("error.inventory.not_configured");
        InventoryApi api = remote.api();
        int processed = 0, remaining = 0;
        List<MigrationFailure> failures = new ArrayList<>();
        for (MigrationCandidate c : candidates()) {
            if ("SKIP".equals(c.kind())) continue;
            String st = links.get(markKey(c));
            boolean finished = finished(c, st);
            if (finished) continue;
            if (processed + failures.size() >= limit) {
                remaining++;
                continue;
            }
            try {
                if (!"IN".equals(st) && !"OUT".equals(st)) {
                    String wh = c.warehouseNo() != null ? c.warehouseNo() : warehouses.defaultReturnWarehouse();
                    if (wh == null) throw BizException.conflict("error.issue.no_warehouse");
                    String doc = api.createInbound(links.locationId(api, wh), "PURCHASE", "MIGRATION", "存量迁移",
                            List.of(new InventoryApi.InboundLine(links.itemId(api, c.itemType()), null, null,
                                    List.of(new InventoryApi.Serial(c.itemNo(), gateRef(c.itemNo()), null)))), key());
                    api.postInbound(doc, key());
                    links.put(markKey(c), "IN");
                }
                if ("OUT".equals(c.kind())) {
                    Map<String, String> t = links.targets(api, c.agentNo(), c.siteNo(), operator());
                    String wh = c.warehouseNo() != null ? c.warehouseNo() : warehouses.defaultReturnWarehouse();
                    String doc = api.createOutbound(links.locationId(api, wh), "ISSUE", t, "存量迁移", List.of(c.itemNo()), key());
                    api.postOutbound(doc, key());
                    if (c.claimed()) {
                        String id = api.dispatchesBySerial(c.itemNo()).stream()
                                .filter(v -> v.parentDispatchId() == null && "AVAILABLE".equals(v.status()))
                                .map(InventoryApi.DispatchView::dispatchId).findFirst().orElseThrow();
                        links.put("claim:" + c.itemNo(), api.claim(List.of(id), c.itemNo(), key()).claimNo());
                    }
                    links.put(markKey(c), "OUT");
                }
                processed++;
            } catch (InventoryRemoteException e) {
                failures.add(new MigrationFailure(c.itemType(), c.itemNo(), e.code, String.join("；", e.args)));
            } catch (RuntimeException e) {
                failures.add(new MigrationFailure(c.itemType(), c.itemNo(), e.getClass().getSimpleName(), String.valueOf(e.getMessage())));
            }
        }
        if (!failures.isEmpty()) {
            log.warn("存量迁移本批失败 {} 台（首台 {} {}）：按 code 处理后重跑，已迁的不会重复", failures.size(),
                    failures.get(0).itemNo(), failures.get(0).code());
        }
        log.info("存量迁移本批 processed={} failed={} remaining={}", processed, failures.size(), remaining);
        return new InventoryMigrationResult(processed, failures.size(), remaining, failures.size() > 200 ? failures.subList(0, 200) : failures);
    }

    // —— 内部 ——

    private List<MigrationCandidate> candidates() {
        return DataScopeContext.executeWithoutScope(() -> {
            List<MigrationCandidate> out = new ArrayList<>();
            for (DevCabinet c : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().isNull(DevCabinet::getArchivedAt)
                    .orderByAsc(DevCabinet::getId))) {
                String st = c.getStatus();
                if (CabinetStatus.RETIRED.name().equals(st)) continue;
                if (CabinetStatus.IN_TRANSIT.name().equals(st)) {
                    out.add(skip(CAB, c.getCabinetNo(), "调拨在途：签收后再迁"));
                } else if (c.getWarehouseNo() != null) {
                    out.add(new MigrationCandidate(CAB, c.getCabinetNo(), "IN", c.getWarehouseNo(), null, null, false, null));
                } else if (c.getIssuedAgentNo() != null) {
                    out.add(new MigrationCandidate(CAB, c.getCabinetNo(), "OUT", null, c.getIssuedAgentNo(), c.getIssuedSiteNo(),
                            c.getIssueClaimedAt() != null || c.getIssueReceiverNo() == null, null));
                } else if (c.getLocationNo() != null) {
                    out.add(skip(CAB, c.getCabinetNo(), "直营站点的柜：没有商家可出库，暂不迁"));
                } else {
                    out.add(skip(CAB, c.getCabinetNo(), "不在仓也不在出库池（多半在到仓待检）：入库后再迁"));
                }
            }
            for (DevPowerbank p : powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>().isNull(DevPowerbank::getArchivedAt)
                    .eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).isNotNull(DevPowerbank::getWarehouseNo)
                    .orderByAsc(DevPowerbank::getId))) {
                out.add(new MigrationCandidate(PB, p.getPowerbankNo(), "IN", p.getWarehouseNo(), null, null, false, null));
            }
            return out;
        });
    }

    /** 只需入库的：IN / OUT 都算完（OUT 是比 IN 更靠后的进度）；要出库的：只有 OUT 算完。 */
    private static boolean finished(MigrationCandidate c, String st) {
        return "IN".equals(c.kind()) ? ("IN".equals(st) || "OUT".equals(st)) : "OUT".equals(st);
    }

    private static MigrationCandidate skip(String type, String no, String reason) {
        return new MigrationCandidate(type, no, "SKIP", null, null, null, false, reason);
    }

    private String gateRef(String itemNo) {
        DevQcRecord r = qcRecords.selectOne(new LambdaQueryWrapper<DevQcRecord>().eq(DevQcRecord::getItemNo, itemNo)
                .eq(DevQcRecord::getResult, QcStatus.PASSED.name()).isNull(DevQcRecord::getConsumedBy)
                .orderByDesc(DevQcRecord::getId).last("limit 1"));
        return r != null ? r.getQcNo() : "MIG-" + itemNo;
    }

    private static String markKey(MigrationCandidate c) {
        return "mig:" + c.itemType() + ":" + c.itemNo();
    }

    private static String key() {
        return "mig-" + UUID.randomUUID();
    }

    private static String operator() {
        String me = SecurityUtils.userNo();
        return me == null ? "SYSTEM" : me;
    }
}
