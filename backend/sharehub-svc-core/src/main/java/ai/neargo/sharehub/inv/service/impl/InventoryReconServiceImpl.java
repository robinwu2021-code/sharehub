package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.inv.WarehouseDirectory;
import ai.neargo.sharehub.inv.dto.ReconDtos.Diff;
import ai.neargo.sharehub.inv.dto.ReconDtos.Report;
import ai.neargo.sharehub.inv.remote.InventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryCalls;
import ai.neargo.sharehub.inv.remote.InventoryLinks;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.inv.service.InventoryReconService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存对账（对接明细 F07）。两类核对：
 * <ul>
 *   <li>在仓：本地「在库、有仓、未出库、未绑定」的柜与宝 ↔ 库存服务各仓在库序列号；</li>
 *   <li>出库池：本地已出库给商家的柜 ↔ 库存服务出库池里待领取 / 已领取的顶层记录（商家、签收状态）。</li>
 * </ul>
 * 只报差异与处置建议。哪边对要人判断（可能是本地漏同步，也可能是库存服务被直接改过），不自动覆盖。
 */
@Service
public class InventoryReconServiceImpl implements InventoryReconService {

    private static final int MAX_DIFFS = 500;
    private static final String CABINET = InventoryLinks.CABINET;
    private static final String POWERBANK = InventoryLinks.POWERBANK;

    private final RemoteInventoryPort remote;
    private final InventoryLinks links;
    private final WarehouseDirectory warehouses;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;

    public InventoryReconServiceImpl(RemoteInventoryPort remote, InventoryLinks links, WarehouseDirectory warehouses, CabinetMapper cabinets,
                                     PowerbankMapper powerbanks) {
        this.remote = remote;
        this.links = links;
        this.warehouses = warehouses;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
    }

    @Override
    public Report run(String warehouseNo, String agentNo) {
        InventoryApi api = remote.api();
        InventoryCalls.requireConfigured(api);
        String onlyWh = warehouseNo == null || warehouseNo.isBlank() ? null : warehouseNo.trim();
        String onlyAgent = agentNo == null || agentNo.isBlank() ? null : agentNo.trim();
        if (onlyWh != null) warehouses.requireVisible(onlyWh);
        // 给了仓库只核在仓、给了商家只核出库池；都没给两部分都核
        boolean checkWarehouse = onlyAgent == null || onlyWh != null;
        boolean checkPool = onlyWh == null || onlyAgent != null;
        List<String> visible = warehouses.visibleWarehouseNos();
        List<Diff> diffs = new ArrayList<>();
        Map<String, String> localWh = new LinkedHashMap<>();
        Map<String, DevCabinet> issued = new LinkedHashMap<>();
        if (checkWarehouse) inWarehouse(api, onlyWh, visible, localWh, diffs);
        if (checkPool) pool(api, onlyAgent, issued, diffs);
        int total = diffs.size();
        return new Report(LocalDateTime.now(), localWh.size(), issued.size(), total, total > MAX_DIFFS ? diffs.subList(0, MAX_DIFFS) : diffs);
    }

    /** 在仓：本地在库有仓的柜与宝 ↔ 库存服务各仓在库序列号。 */
    private void inWarehouse(InventoryApi api, String onlyWh, List<String> visible, Map<String, String> localWh, List<Diff> diffs) {

        // —— 在仓 ——
        // 本地：设备号 → 所在仓（只看可见仓）
        Map<String, String> typeOf = new HashMap<>();
        for (DevCabinet c : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name())
                .isNotNull(DevCabinet::getWarehouseNo).isNull(DevCabinet::getIssuedAgentNo).isNull(DevCabinet::getLocationNo)
                .isNull(DevCabinet::getArchivedAt))) {
            if ((visible == null || visible.contains(c.getWarehouseNo())) && (onlyWh == null || onlyWh.equals(c.getWarehouseNo()))) {
                localWh.put(c.getCabinetNo(), c.getWarehouseNo());
                typeOf.put(c.getCabinetNo(), CABINET);
            }
        }
        for (DevPowerbank p : powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>().eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name())
                .isNotNull(DevPowerbank::getWarehouseNo).isNull(DevPowerbank::getCabinetNo).isNull(DevPowerbank::getArchivedAt))) {
            if ((visible == null || visible.contains(p.getWarehouseNo())) && (onlyWh == null || onlyWh.equals(p.getWarehouseNo()))) {
                localWh.put(p.getPowerbankNo(), p.getWarehouseNo());
                typeOf.put(p.getPowerbankNo(), POWERBANK);
            }
        }
        // 库存服务：序列号 → 本地仓号（只看已映射的可见仓）
        Map<String, String> remoteWh = new LinkedHashMap<>();
        for (var w : warehouses.list()) {
            if ((visible != null && !visible.contains(w.warehouseNo())) || (onlyWh != null && !onlyWh.equals(w.warehouseNo()))) continue;
            String loc = links.get("wh:" + w.warehouseNo());
            if (loc == null) continue;
            for (String type : List.of(CABINET, POWERBANK)) {
                String item = links.get("item:" + type);
                if (item == null) continue;
                for (InventoryApi.InStock x : InventoryCalls.call(() -> api.serials(item, loc))) {
                    remoteWh.put(x.serialNo(), w.warehouseNo());
                    typeOf.putIfAbsent(x.serialNo(), type);
                }
            }
        }
        localWh.forEach((no, wh) -> {
            String r = remoteWh.get(no);
            if (r == null) diffs.add(new Diff("MISSING_REMOTE", typeOf.get(no), no, "在仓 " + wh, "不在库",
                    "没迁移过的走「迁移到独立进销存」；迁过的查库存服务流水，确认是否被出库后补本地或在库存服务补入库"));
            else if (!r.equals(wh)) diffs.add(new Diff("WRONG_LOCATION", typeOf.get(no), no, "在仓 " + wh, "在仓 " + r,
                    "查最近的调拨：发货或签收有一边没同步；以实物所在仓为准做调拨或盘点纠正"));
        });
        remoteWh.forEach((no, wh) -> {
            if (!localWh.containsKey(no)) diffs.add(new Diff("EXTRA_REMOTE", typeOf.get(no), no, "不在仓（或已出库 / 已绑定）", "在仓 " + wh,
                    "查本地这台的状态：已出库 / 已报废而库存服务没跟上的，在执行异常里查回；否则以盘点纠正库存服务"));
        });

    }

    /** 出库池：本地已出库给商家的柜 ↔ 库存服务出库池待领取 / 已领取的顶层记录。 */
    private void pool(InventoryApi api, String onlyAgent, Map<String, DevCabinet> issued, List<Diff> diffs) {
        for (DevCabinet c : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name())
                .isNotNull(DevCabinet::getIssuedAgentNo).isNull(DevCabinet::getArchivedAt))) {
            if (onlyAgent == null || onlyAgent.equals(c.getIssuedAgentNo())) issued.put(c.getCabinetNo(), c);
        }
        String cabItem = links.get("item:" + CABINET);
        Map<String, InventoryApi.DispatchView> pool = new HashMap<>();
        if (cabItem != null) {
            for (InventoryApi.DispatchView v : InventoryCalls.call(() -> api.dispatchPool(new InventoryApi.DispatchFilter(List.of("AVAILABLE", "CLAIMED"),
                    onlyAgent == null ? null : List.of(onlyAgent), null, null, null, null, cabItem)))) {
                if (v.parentDispatchId() == null && v.serialNo() != null) pool.put(v.serialNo(), v);
            }
        }
        issued.forEach((no, c) -> {
            InventoryApi.DispatchView v = pool.get(no);
            if (v == null) {
                diffs.add(new Diff("POOL_MISSING", CABINET, no, "已出库给 " + c.getIssuedAgentNo(), "出库池没有",
                        "没迁移过的走迁移；迁过的查执行异常里有没有没完成的出库"));
            } else if (!c.getIssuedAgentNo().equals(v.rootTargetRef())) {
                diffs.add(new Diff("POOL_MERCHANT", CABINET, no, "商家 " + c.getIssuedAgentNo(), "商家 " + v.rootTargetRef(),
                        "查最近的出库调整：有一边没同步；确认实际商家后在出库单里调整"));
            } else if ((c.getIssueClaimedAt() != null) != "CLAIMED".equals(v.status())) {
                diffs.add(new Diff("CLAIM_STATE", CABINET, no, c.getIssueClaimedAt() != null ? "已签收" : "待签收", "AVAILABLE".equals(v.status()) ? "待领取" : "已领取",
                        "查最近的签收 / 调整：在执行异常里查回没完成的领取"));
            }
        });
        pool.forEach((no, v) -> {
            if (!issued.containsKey(no)) diffs.add(new Diff("POOL_EXTRA", CABINET, no, "未出库", "出库池 " + v.status() + "（商家 " + v.rootTargetRef() + "）",
                    "本地已撤销 / 退回而库存服务没跟上：在执行异常里查回；确认后在库存服务撤回"));
        });
    }
}
