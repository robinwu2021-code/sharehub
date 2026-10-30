package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.inv.port.InventoryEngineRouter;
import ai.neargo.sharehub.inv.remote.HttpInventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryRemoteException;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存引擎默认 SERVICE、切换受控（对接明细 F09）：库存服务不可达不许切到 SERVICE，
 * 有未完成的库存操作不许切（任一方向）；引擎状态报未完成操作数。没有自动回落 LOCAL。
 */
class InventoryEngineSwitchTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RemoteInventoryPort remote;
    @Autowired
    HttpInventoryApi http;

    private final String opNo = "IO-SWITCH-" + Long.toString(System.nanoTime(), 36).toUpperCase();
    /** 共享测试库里别的场景留下的未完成操作：本类期间暂挂到别的租户，结束放回，免得互相影响。 */
    private List<String> parked = List.of();

    @BeforeEach
    void localEngine() {
        parked = jdbc.queryForList("SELECT operation_no FROM inv_operation WHERE tenant_id='MAIN' AND phase NOT IN ('COMPLETED','REJECTED')", String.class);
        if (!parked.isEmpty()) jdbc.update("UPDATE inv_operation SET tenant_id='PARKED-SWITCH' WHERE tenant_id='MAIN' AND phase NOT IN ('COMPLETED','REJECTED')");
        remote.useApi(new InventoryFake());
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
    }

    @AfterEach
    void restore() {
        jdbc.update("DELETE FROM inv_operation WHERE operation_no=?", opNo);
        if (!parked.isEmpty()) jdbc.update("UPDATE inv_operation SET tenant_id='MAIN' WHERE tenant_id='PARKED-SWITCH'");
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        remote.useApi(http);
    }

    private Resp switchTo(String engine) {
        return post("/api/platform/sys-params/inv.engine", Map.of("paramKey", "inv.engine", "value", engine), login("ADMIN"));
    }

    private String engine() {
        return jdbc.queryForObject("SELECT value FROM sys_param WHERE param_key='inv.engine'", String.class);
    }

    @Test
    @DisplayName("库存服务可达、没有在途操作：可以切到 SERVICE，也可以切回")
    void switchesWhenReachableAndIdle() {
        assertThat(switchTo("SERVICE").status).isEqualTo(200);
        assertThat(engine()).isEqualTo("SERVICE");
        assertThat(switchTo("LOCAL").status).isEqualTo(200);
        assertThat(engine()).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("库存服务不可达：不许切到 SERVICE，参数不变")
    void refusesServiceWhenUnreachable() {
        remote.useApi(new InventoryFake() {
            @Override
            public List<Warehouse> warehouses() {
                throw new InventoryRemoteException(503, "inventory.unreachable", List.of(), "down", null);
            }
        });
        Resp r = switchTo("SERVICE");
        assertThat(r.status).isEqualTo(409);
        assertThat(r.body.toString()).contains("inventory.unreachable");
        assertThat(engine()).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("有未完成的库存操作：任一方向都不许切；引擎状态报未完成数")
    void refusesWhilePendingOperations() {
        jdbc.update("INSERT INTO inv_operation (operation_no,tenant_id,inventory_owner_ref,request_key,payload_hash,payload,engine,kind,phase) "
                        + "VALUES (?,'MAIN','',?,?,'{}','LOCAL','MATERIAL_DOC','NEEDS_REVIEW')",
                opNo, opNo, "0".repeat(64));
        Resp r = switchTo("SERVICE");
        assertThat(r.status).isEqualTo(409);
        assertThat(engine()).isEqualTo("LOCAL");
        assertThat(get("/api/ops/inventory/engine", login("ADMIN")).okData().path("pendingOperations").asLong()).isPositive();
    }

    @Test
    @DisplayName("参数缺失或为空时默认 SERVICE")
    void defaultsToService() {
        SysParamPort missing = new SysParamPort() {
            @Override public int intOf(String key, int d) { return d; }
            @Override public BigDecimal decimalOf(String key, BigDecimal d) { return d; }
            @Override public String textOf(String key, String d) { return d; }
        };
        assertThat(InventoryEngineRouter.isService(missing)).isTrue();
    }
}
