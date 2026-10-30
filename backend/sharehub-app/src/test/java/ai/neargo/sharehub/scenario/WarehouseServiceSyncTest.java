package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.inv.remote.HttpInventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryLinks;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.InventoryFake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仓库主数据以库存服务为准（对接明细 A01，SERVICE 引擎）：新建即在库存服务建仓并固定映射；改名同步；
 * 归档要问库存服务的余额并停用那边的仓；那边停用后（暂无启用接口）不许取消归档；同名与认领歧义一律拒绝。
 */
class WarehouseServiceSyncTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RemoteInventoryPort remote;
    @Autowired
    HttpInventoryApi http;
    @Autowired
    InventoryLinks links;

    private final String tag = "WS" + Long.toString(System.nanoTime(), 36).toUpperCase();
    private InventoryFake fake;
    private String token;

    @BeforeEach
    void serviceEngine() {
        fake = new InventoryFake();
        remote.useApi(fake);
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");
        token = login("ADMIN");
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state WHERE k IN (SELECT CONCAT('wh:', warehouse_no) FROM inv_warehouse WHERE name LIKE ?)", tag + "%");
        jdbc.update("DELETE FROM inv_warehouse WHERE name LIKE ?", tag + "%");
        remote.useApi(http);
    }

    private String create(String name) {
        return post("/api/ops/inventory/warehouses", Map.of("name", name), token).okData().path("warehouseNo").asText();
    }

    private InventoryApi.Warehouse remoteOf(String warehouseNo) {
        return fake.warehouses.get(links.get("wh:" + warehouseNo));
    }

    @Test
    @DisplayName("★ 新建即在库存服务建仓、映射固定；改名同步过去")
    void createAndRenameSync() {
        String no = create(tag + "迪拜二仓");
        assertThat(remoteOf(no)).isNotNull();
        assertThat(remoteOf(no).name()).isEqualTo(tag + "迪拜二仓");
        post("/api/ops/inventory/warehouses/" + no, Map.of("name", tag + "迪拜南仓"), token).okData();
        assertThat(remoteOf(no).name()).isEqualTo(tag + "迪拜南仓");
    }

    @Test
    @DisplayName("★ 归档：库存服务里还有物料就拒；清空后归档并停用那边的仓；那边停用了不许取消归档")
    void archiveChecksRemoteStock() {
        String no = create(tag + "沙迦二仓");
        String loc = links.get("wh:" + no);
        fake.qty.put(loc + "|ITM-X", BigDecimal.valueOf(3));
        Resp r = post("/api/ops/inventory/warehouses/" + no + "/archive", Map.of(), token);
        assertThat(r.status).isEqualTo(409);
        assertThat(r.body.toString()).contains("inventory service stock: 3");
        assertThat(remoteOf(no).status()).isEqualTo("ACTIVE");

        fake.qty.remove(loc + "|ITM-X");
        post("/api/ops/inventory/warehouses/" + no + "/archive", Map.of(), token).okData();
        assertThat(remoteOf(no).status()).isEqualTo("DISABLED");

        Resp back = post("/api/ops/inventory/warehouses/" + no + "/unarchive", Map.of(), token);
        assertThat(back.status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM inv_warehouse WHERE warehouse_no=?", Boolean.class, no)).isTrue();
    }

    @Test
    @DisplayName("供应商新建、改名即同步成库存服务外部去向 SUPPLIER（A03）")
    void supplierSyncsAsTarget() {
        String sp = post("/api/ops/inventory/suppliers", Map.of("name", tag + "供应商"), token).okData().path("supplierNo").asText();
        assertThat(fake.targets).containsEntry("SUPPLIER:" + sp, tag + "供应商");
        post("/api/ops/inventory/suppliers/" + sp, Map.of("name", tag + "供应商改名"), token).okData();
        assertThat(fake.targets).containsEntry("SUPPLIER:" + sp, tag + "供应商改名");
        jdbc.update("DELETE FROM inv_supplier WHERE supplier_no=?", sp);
    }

    @Test
    @DisplayName("★ 建仓带本系统编码；认领先按编码（名称不同也认），不按名称猜")
    void adoptsByExternalRef() {
        String no = create(tag + "编码仓");
        assertThat(remoteOf(no).externalRef()).as("建仓带仓号").isEqualTo(no);

        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        String old = create(tag + "旧编码仓");
        fake.warehouses.put("LOC-REF", new InventoryApi.Warehouse("LOC-REF", "库存服务那边改过的名字", null, "ACTIVE", old));
        fake.warehouses.put("LOC-NAME", new InventoryApi.Warehouse("LOC-NAME", tag + "旧编码仓", null, "ACTIVE"));
        assertThat(links.locationId(fake, old)).isEqualTo("LOC-REF");
    }

    @Test
    @DisplayName("启用中的仓不许同名")
    void rejectsDuplicateActiveName() {
        create(tag + "重名仓");
        assertThat(post("/api/ops/inventory/warehouses", Map.of("name", tag + "重名仓"), token).status).isEqualTo(409);
    }

    @Test
    @DisplayName("★ 认领只认唯一候选：库存服务里两个同名未占用的仓 → 拒绝，不猜")
    void ambiguousAdoptionIsRejected() {
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        String no = create(tag + "旧仓");   // LOCAL 下建：库存服务里还没有它
        assertThat(links.get("wh:" + no)).isNull();
        fake.warehouses.put("LOC-A", new InventoryApi.Warehouse("LOC-A", tag + "旧仓", null, "ACTIVE"));
        fake.warehouses.put("LOC-B", new InventoryApi.Warehouse("LOC-B", tag + "旧仓", null, "ACTIVE"));
        assertThatThrownBy(() -> links.locationId(fake, no)).isInstanceOf(BizException.class);

        fake.warehouses.put("LOC-B", new InventoryApi.Warehouse("LOC-B", tag + "旧仓", null, "DISABLED"));
        assertThat(links.locationId(fake, no)).as("停用的不算候选，剩一个就认领").isEqualTo("LOC-A");
    }
}
