package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.inv.remote.HttpInventoryApi;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.InventoryFake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 外部已过账、本地回滚及响应丢失时，重试必须恢复同一业务操作。 */
class MaterialRecoveryTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired RemoteInventoryPort remote;
    @Autowired HttpInventoryApi http;
    final String tag = "REC" + Long.toUnsignedString(System.nanoTime(), 36);
    String token;
    String material;
    InventoryFake fake;

    @BeforeEach
    void setup() {
        fake = new InventoryFake();
        remote.useApi(fake);
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
        token = login("ADMIN");
        material = post("/api/ops/inventory/materials", Map.of("name", tag, "category", "CONSUMABLE", "trackingMode", "QUANTITY", "uom", "个"), token)
                .okData().path("materialNo").asText();
    }

    @AfterEach
    void cleanup() {
        remote.useApi(http);
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
        jdbc.update("DELETE FROM inv_material_doc_line WHERE material_no=?", material);
        jdbc.update("DELETE FROM inv_material_doc WHERE note=?", tag);
        jdbc.update("DELETE FROM inv_material WHERE material_no=?", material);
        jdbc.update("DELETE FROM inv_operation_step WHERE operation_no IN (SELECT operation_no FROM inv_operation WHERE request_key LIKE ?)", tag + "%");
        jdbc.update("DELETE FROM inv_operation WHERE request_key LIKE ?", tag + "%");
    }

    Map<String, Object> opening(int qty) {
        return Map.of("requestKey", tag + "-opening", "purpose", "OPENING", "warehouseNo", "WH002", "note", tag,
                "lines", List.of(Map.of("materialNo", material, "qty", qty)));
    }

    int balance(String warehouse) {
        var rows = get("/api/ops/inventory/material-balances?warehouseNo=" + warehouse + "&materialNo=" + material, token).okData();
        return rows.isEmpty() ? 0 : rows.get(0).path("onHand").asInt();
    }

    @Test
    void replayDoesNotPostAgainAndChangedPayloadIsRejected() {
        String no = post("/api/ops/inventory/material-docs", opening(10), token).okData().path("docNo").asText();
        assertThat(post("/api/ops/inventory/material-docs", opening(10), token).okData().path("docNo").asText()).isEqualTo(no);
        assertThat(balance("WH002")).isEqualTo(10);
        assertThat(post("/api/ops/inventory/material-docs", opening(11), token).status).isEqualTo(409);
        assertThat(balance("WH002")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT phase FROM inv_operation WHERE request_key=?", String.class, tag + "-opening")).isEqualTo("COMPLETED");
    }

    @Test
    void rejectedBeforeAnyRemoteRequestIsTerminalNotPending() {
        // 在手为 0 还要领用：本地预检就拒，一个远端请求都没发 —— 没有可查回的，不能算「未完成」挡住切引擎
        Map<String, Object> body = Map.of("requestKey", tag + "-over", "purpose", "REQUISITION", "warehouseNo", "WH002", "woNo", "WO-" + tag,
                "note", tag, "lines", List.of(Map.of("materialNo", material, "qty", 5)));
        assertThat(post("/api/ops/inventory/material-docs", body, token).status).isEqualTo(409);
        String op = jdbc.queryForObject("SELECT operation_no FROM inv_operation WHERE request_key=?", String.class, tag + "-over");
        assertThat(jdbc.queryForObject("SELECT phase FROM inv_operation WHERE operation_no=?", String.class, op)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_operation_step WHERE operation_no=?", Integer.class, op)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_operation WHERE operation_no=? AND phase NOT IN ('COMPLETED','REJECTED')",
                Integer.class, op)).as("不计入未完成").isZero();
        // 补足库存后，同键原样重提照常执行
        post("/api/ops/inventory/material-docs", opening(10), token).okData();
        assertThat(post("/api/ops/inventory/material-docs", body, token).status).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT phase FROM inv_operation WHERE operation_no=?", String.class, op)).isEqualTo("COMPLETED");
    }

    @Test
    void failedTransferInboundResumesOriginalOperationDespiteEngineSwitch() {
        post("/api/ops/inventory/material-docs", opening(10), token).okData();
        final InventoryFake original = fake;
        // 用同一个远程账本，准确在出库成功后的调入步骤失败。
        fake.failInboundPurpose = "TRANSFER_IN";
        Map<String, Object> body = Map.of("requestKey", tag + "-transfer", "purpose", "TRANSFER", "warehouseNo", "WH002", "toWarehouseNo", "WH003",
                "note", tag, "lines", List.of(Map.of("materialNo", material, "qty", 10)));
        assertThat(post("/api/ops/inventory/material-docs", body, token).status).isEqualTo(409);
        assertThat(balance("WH002")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_material_doc WHERE note=? AND purpose='TRANSFER'", Integer.class, tag)).isZero();
        assertThat(jdbc.queryForObject("SELECT phase FROM inv_operation WHERE request_key=?", String.class, tag + "-transfer")).isEqualTo("NEEDS_REVIEW");
        assertThat(post("/api/ops/inventory/materials/" + material, Map.of("name", tag, "category", "CONSUMABLE", "trackingMode", "SERIAL", "uom", "个"), token).status).isEqualTo(409);
        assertThat(post("/api/ops/inventory/materials/" + material + "/archive", Map.of(), token).status).isEqualTo(409);
        for (String warehouse : List.of("WH002", "WH003")) {
            var archived = post("/api/ops/inventory/warehouses/" + warehouse + "/archive", Map.of(), token);
            assertThat(archived.status).isEqualTo(409);
            assertThat(archived.msg()).contains("待恢复");
        }
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        String no = post("/api/ops/inventory/material-docs", body, token).okData().path("docNo").asText();
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");
        assertThat(balance("WH002")).isZero();
        assertThat(balance("WH003")).isEqualTo(10);
        assertThat(original.docs.values().stream().filter(d -> "TRANSFER_OUT".equals(d.purpose()))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT operation_no FROM inv_material_doc WHERE doc_no=?", String.class, no)).isNotBlank();
    }

    @Test
    void lostInboundPostResponseDoesNotDuplicateStock() {
        fake.loseNextInboundPostResponse = true;
        assertThat(post("/api/ops/inventory/material-docs", opening(10), token).status).isEqualTo(409);
        assertThat(balance("WH002")).isEqualTo(10);
        post("/api/ops/inventory/material-docs", opening(10), token).okData();
        assertThat(balance("WH002")).isEqualTo(10);
        assertThat(fake.docs).hasSize(1);
    }

    @Test
    void unknownOutboundPostIsRetriedWithOriginalKeyWithoutAnotherDeduction() {
        post("/api/ops/inventory/material-docs", opening(10), token).okData();
        fake.loseNextOutboundPostResponse = true;
        Map<String, Object> body = Map.of("requestKey", tag + "-transfer", "purpose", "TRANSFER", "warehouseNo", "WH002", "toWarehouseNo", "WH003",
                "note", tag, "lines", List.of(Map.of("materialNo", material, "qty", 10)));
        assertThat(post("/api/ops/inventory/material-docs", body, token).status).isEqualTo(409);
        assertThat(balance("WH002")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM inv_operation_step s JOIN inv_operation o ON o.operation_no=s.operation_no WHERE o.request_key=? AND s.step_code='OUT_POST'", String.class,
                tag + "-transfer")).isEqualTo("UNKNOWN");
        post("/api/ops/inventory/material-docs", body, token).okData();
        assertThat(balance("WH002")).isZero();
        assertThat(balance("WH003")).isEqualTo(10);
    }

    @Test
    void simultaneousRequestsProduceOneDocumentAndOneStockMovement() {
        var first = CompletableFuture.supplyAsync(() -> post("/api/ops/inventory/material-docs", opening(10), token).okData());
        var second = CompletableFuture.supplyAsync(() -> post("/api/ops/inventory/material-docs", opening(10), token).okData());
        assertThat(first.join().path("docNo").asText()).isEqualTo(second.join().path("docNo").asText());
        assertThat(balance("WH002")).isEqualTo(10);
        assertThat(fake.docs).hasSize(1);
    }

    @Test
    void anotherAuthorizedOperatorCannotReplaySomebodyElsesRequestKey() {
        post("/api/ops/inventory/material-docs", opening(10), token).okData();
        assertThat(post("/api/ops/inventory/material-docs", opening(10), login("WAREHOUSE")).status).isEqualTo(403);
        assertThat(balance("WH002")).isEqualTo(10);
    }

    @Test
    void moreConcurrentOperationsThanPoolConnectionsDoNotStarveDurableCheckpoints() {
        List<CompletableFuture<Resp>> requests = java.util.stream.IntStream.range(0, 6).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            Map<String, Object> body = new java.util.HashMap<>(opening(10));
            body.put("requestKey", tag + "-concurrent-" + i);
            return post("/api/ops/inventory/material-docs", body, token);
        })).toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        for (var request : requests) assertThat(request.join().status).as("独立事务检查点不能被业务连接耗尽阻塞").isEqualTo(200);
        assertThat(balance("WH002")).isEqualTo(60);
    }

    @Test void coldWarehouseMappingDoesNotLockAgainstItsOwnCheckpoint() {
        post("/api/ops/inventory/material-docs", opening(10), token).okData();
        jdbc.update("DELETE FROM inventory_sync_state WHERE k='wh:WH002'");
        post("/api/ops/inventory/material-docs", Map.of("requestKey", tag + "-out", "purpose", "REQUISITION", "warehouseNo", "WH002", "receiverNo", "E1", "note", tag,
                "lines", List.of(Map.of("materialNo", material, "qty", 1))), token).okData();
        assertThat(balance("WH002")).isEqualTo(9);
    }
}
