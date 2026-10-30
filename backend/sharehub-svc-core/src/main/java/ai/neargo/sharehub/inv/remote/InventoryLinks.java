package ai.neargo.sharehub.inv.remote;

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
import java.util.UUID;

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

    /** 本地仓号 → 库存服务 locationId：先按名称认领已有仓，没有再建。 */
    public String locationId(InventoryApi api, String warehouseNo) {
        String k = "wh:" + warehouseNo;
        String id = get(k);
        if (id != null) return id;
        String name = warehouses.list().stream().filter(w -> w.warehouseNo().equals(warehouseNo)).map(WarehouseItem::name)
                .findFirst().orElse(warehouseNo);
        id = api.warehouses().stream().filter(w -> name.equals(w.name())).map(InventoryApi.Warehouse::locationId).findFirst()
                .orElseGet(() -> api.createWarehouse(name, "wh-" + warehouseNo + "-" + UUID.randomUUID()));
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
