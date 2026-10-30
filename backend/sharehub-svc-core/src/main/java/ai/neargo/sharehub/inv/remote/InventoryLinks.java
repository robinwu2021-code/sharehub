package ai.neargo.sharehub.inv.remote;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.api.platform.dto.AgentBrief;
import ai.neargo.sharehub.api.platform.dto.EmployeeBrief;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.inv.WarehouseDirectory;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 本系统 ↔ 独立进销存的主数据映射（S3.3）：物料、仓库按需建立并记进 inventory_sync_state；
 * 商家 / 站点 / 内部人员在每次写之前 upsert 成外部目标（父先于子）。按需同步保证「写的时候对方一定认得」，
 * 不依赖定时全量同步先跑过。
 */
@Component
public class InventoryLinks {

    public static final String CABINET = "CABINET";
    public static final String POWERBANK = "POWERBANK";

    private final InventorySyncStateMapper state;
    private final WarehouseDirectory warehouses;
    private final AgentDirectoryPort agents;
    private final SiteQueryPort sites;
    private final EmployeeDirectoryPort employees;

    public InventoryLinks(InventorySyncStateMapper state, WarehouseDirectory warehouses, AgentDirectoryPort agents,
                          SiteQueryPort sites, EmployeeDirectoryPort employees) {
        this.state = state;
        this.warehouses = warehouses;
        this.agents = agents;
        this.sites = sites;
        this.employees = employees;
    }

    public String get(String k) {
        InventorySyncState s = state.selectById(k);
        return s == null ? null : s.getV();
    }

    public void put(String k, String v) {
        state.put(k, v);
    }

    /** 反查：库存服务 locationId → 本地仓号（没有映射返回原值，界面上至少看得到）。 */
    public String warehouseNoOf(String locationId) {
        if (locationId == null) return null;
        return state.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InventorySyncState>()
                        .likeRight(InventorySyncState::getK, "wh:").eq(InventorySyncState::getV, locationId))
                .stream().findFirst().map(x -> x.getK().substring(3)).orElse(locationId);
    }

    /** 反查：itemId → 设备类型（CABINET / POWERBANK）。 */
    public String itemTypeOf(String itemId) {
        if (itemId == null) return null;
        return state.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InventorySyncState>()
                        .likeRight(InventorySyncState::getK, "item:").eq(InventorySyncState::getV, itemId))
                .stream().findFirst().map(x -> x.getK().substring(5)).orElse(itemId);
    }

    /** 某前缀下的全部键值（盘点单登记等）。 */
    public java.util.List<InventorySyncState> listByPrefix(String prefix) {
        return state.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InventorySyncState>()
                .likeRight(InventorySyncState::getK, prefix).orderByDesc(InventorySyncState::getUpdatedAt));
    }

    /** 设备类型 → itemId：序列号管理、入库须质检凭证（gateRef = 质检记录号）。 */
    public String itemId(InventoryApi api, String itemType) {
        String k = "item:" + itemType;
        String id = get(k);
        if (id != null) return id;
        id = api.upsertItem(itemType, new InventoryApi.ItemSpec(CABINET.equals(itemType) ? "机柜" : "充电宝", "PCS", "SERIAL",
                "REF_REQUIRED", "QC", null));
        put(k, id);
        return id;
    }

    /**
     * 本地仓号 → 库存服务 locationId。映射一旦记下就固定（仓库改名不影响）。
     *
     * <p>还没映射时才按名称认领，且只认<b>唯一</b>一个「同名、启用、没被别的本地仓占用」的库位 ——
     * 库存服务的仓没有本系统编码（已列入宿主接口需求），按名称认领一旦认错就是两个仓串一本账。
     * 有歧义就拒绝，由人在库存服务侧处理；一个都没有才新建（幂等键按仓号，重试不重复建）。
     */
    public String locationId(InventoryApi api, String warehouseNo) {
        String id = get("wh:" + warehouseNo);
        if (id != null) return id;
        return locationId(api, warehouseNo, warehouses.list().stream().filter(w -> w.warehouseNo().equals(warehouseNo))
                .map(WarehouseItem::name).findFirst().orElse(warehouseNo));
    }

    /** 同上，名称由调用方给 —— 新建仓时本地行还没提交，独立事务里按仓号查不到名称。 */
    public String locationId(InventoryApi api, String warehouseNo, String name) {
        String k = "wh:" + warehouseNo;
        String id = get(k);
        if (id != null) return id;
        java.util.Set<String> taken = new java.util.HashSet<>();
        listByPrefix("wh:").forEach(x -> taken.add(x.getV()));
        List<InventoryApi.Warehouse> all = api.warehouses();
        // 先按本系统编码认领（库存服务 60a614575 起有 externalRef）：编码是稳定的，名称可改、可重名
        java.util.Optional<String> byRef = all.stream().filter(w -> warehouseNo.equals(w.externalRef()) && !taken.contains(w.locationId()))
                .map(InventoryApi.Warehouse::locationId).findFirst();
        if (byRef.isPresent()) {
            put(k, byRef.get());
            return byRef.get();
        }
        List<String> candidates = all.stream()
                .filter(w -> w.externalRef() == null || w.externalRef().isBlank())
                .filter(w -> name.equals(w.name()) && !"DISABLED".equals(w.status()) && !taken.contains(w.locationId()))
                .map(InventoryApi.Warehouse::locationId).toList();
        if (candidates.size() > 1) throw BizException.conflict("error.inventory.warehouse_ambiguous", name, candidates.size());
        id = candidates.isEmpty() ? api.createWarehouse(name, warehouseNo, "wh-" + warehouseNo) : candidates.getFirst();
        put(k, id);
        return id;
    }

    /** 出库目标：MERCHANT（必）/ SITE（可）/ PERSON（必，接收人）。先 upsert 再返回 targets。 */
    public Map<String, String> targets(InventoryApi api, String agentNo, String siteNo, String personNo) {
        AgentBrief a = agents.briefsOf(List.of(agentNo)).get(agentNo);
        api.upsertTarget("MERCHANT", agentNo, a == null ? agentNo : a.name(), null);
        if (siteNo != null) {
            SiteBrief s = sites.briefsByNos(List.of(siteNo)).stream().findFirst().orElse(null);
            api.upsertTarget("SITE", siteNo, s == null ? siteNo : s.name(), agentNo);
        }
        EmployeeBrief e = employees.briefsOf(List.of(personNo)).get(personNo);
        api.upsertTarget("PERSON", personNo, e == null ? personNo : e.name(), null);
        return siteNo == null ? Map.of("MERCHANT", agentNo, "PERSON", personNo)
                : Map.of("MERCHANT", agentNo, "SITE", siteNo, "PERSON", personNo);
    }
}
