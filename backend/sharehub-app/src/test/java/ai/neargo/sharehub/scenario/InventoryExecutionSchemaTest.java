package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实数据库验证恢复、采购实收和设备交接的持久化边界，不只检查SQL文本。 */
@Transactional
class InventoryExecutionSchemaTest extends ApiTestSupport {
    @Autowired
    JdbcTemplate jdbc;

    private void requireTables(String... names) {
        Set<String> actual = Set.copyOf(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()", String.class));
        assertThat(actual).contains(names);
    }

    @Test
    void operationRequestIsUniqueWithinOwnerAndKind() {
        requireTables("inv_operation", "inv_operation_step");
        String tag = Long.toUnsignedString(System.nanoTime(), 36);
        String sql = "INSERT INTO inv_operation (operation_no, tenant_id, inventory_owner_ref, request_key, payload_hash, engine, kind) VALUES (?, 'MAIN', ?, ?, ?, 'SERVICE', 'MATERIAL_TRANSFER')";
        jdbc.update(sql, "OP" + tag, "OWNER_A", tag, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(sql, "OP2" + tag, "OWNER_A", tag, "b".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
        // 相同外部请求号在不同业主下不是同一个业务意图。
        jdbc.update(sql, "OP3" + tag, "OWNER_B", tag, "a".repeat(64));
        jdbc.update("INSERT INTO inv_operation_step (operation_no, step_code) VALUES (?, 'OUTBOUND')", "OP" + tag);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inv_operation_step (operation_no, step_code) VALUES (?, 'OUTBOUND')", "OP" + tag))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void repeatedSourceReceiptCannotCreateAnotherIntake() {
        requireTables("inv_receipt_source");
        String tag = Long.toUnsignedString(System.nanoTime(), 36);
        String sql = "INSERT INTO inv_receipt_source (tenant_id, source_system, external_receipt_ref, source_line_ref, intake_no, received_qty, payload_hash) VALUES ('MAIN', 'OPS', ?, '1', ?, 80, ?)";
        jdbc.update(sql, tag, "IK" + tag, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(sql, tag, "IK2" + tag, "a".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deviceCanBeDeliveredAgainButNotDuplicatedInTheSameDelivery() {
        requireTables("dev_delivery", "dev_delivery_item");
        String tag = Long.toUnsignedString(System.nanoTime(), 36);
        String sql = "INSERT INTO dev_delivery_item (delivery_no, device_no) VALUES (?, ?)";
        jdbc.update(sql, "DL" + tag, "CAB" + tag);
        assertThatThrownBy(() -> jdbc.update(sql, "DL" + tag, "CAB" + tag))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update(sql, "DL2" + tag, "CAB" + tag);
    }

    @Test
    void purchasingDoesNotRequirePrematureDeviceSerialsOrClaimReceivedStock() {
        requireTables("inv_purchase", "inv_purchase_line");
        String no = "PO" + Long.toUnsignedString(System.nanoTime(), 36);
        jdbc.update("INSERT INTO inv_purchase (purchase_no, tenant_id, supplier_no, purchase_date, currency) VALUES (?, 'MAIN', 'SUP_TEST', CURRENT_DATE, 'AED')", no);
        jdbc.update("INSERT INTO inv_purchase_line (purchase_no, line_no, item_type, ordered_qty, unit_cost) VALUES (?, 1, 'CABINET', 100, 12.50)", no);
        assertThat(jdbc.queryForObject("SELECT ordered_qty FROM inv_purchase_line WHERE purchase_no=?", Integer.class, no)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_receipt_source WHERE purchase_no=?", Integer.class, no)).isZero();
    }

    @Test
    void existingIntakesAndDeviceOperationsRemainCompatibleWithMissingHistoricalLinks() {
        requireTables("dev_repair_detail", "sys_export_task");
        Set<String> columns = Set.copyOf(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='inv_intake'", String.class));
        assertThat(columns).contains("purchase_no", "opening_basis", "invoice_missing_reason", "operation_no");
        Integer nullable = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='inv_intake' AND column_name IN ('purchase_no','opening_basis','invoice_missing_reason','operation_no') AND is_nullable='YES'", Integer.class);
        assertThat(nullable).isEqualTo(4);
    }

    @Test
    void databaseNowAndJvmUseTheSameUtcClock() {
        java.time.LocalDateTime database = jdbc.queryForObject("SELECT NOW(3)", java.time.LocalDateTime.class);
        assertThat(java.time.Duration.between(database, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).abs().toSeconds())
                .as("JVM测试固定UTC，SQL NOW()的会话时区也必须UTC，否则心跳、告警和流水时间相差8小时")
                .isLessThan(5);
    }

    static java.util.stream.Stream<String> invalidVocabularyWrites() {
        return java.util.stream.Stream.of(
                "INSERT INTO inv_operation(operation_no,request_key,payload_hash,engine,kind) VALUES ('VOCAB','VOCAB',REPEAT('a',64),'INVALID','MATERIAL_DOC')",
                "INSERT INTO inv_operation(operation_no,request_key,payload_hash,engine,kind,phase) VALUES ('VOCAB','VOCAB',REPEAT('a',64),'SERVICE','MATERIAL_DOC','INVALID')",
                "INSERT INTO inv_operation_step(operation_no,step_code,status) VALUES ('VOCAB','IN_POST','INVALID')",
                "INSERT INTO inv_purchase(purchase_no,supplier_no,purchase_date,currency,status) VALUES ('VOCAB','SUP_TEST',CURRENT_DATE,'AED','INVALID')",
                "INSERT INTO inv_purchase_line(purchase_no,line_no,item_type,ordered_qty,unit_cost) VALUES ('VOCAB',1,'INVALID',1,0)",
                "INSERT INTO dev_delivery(delivery_no,agent_no,status) VALUES ('VOCAB','AG_TEST','INVALID')",
                "INSERT INTO dev_delivery(delivery_no,agent_no,handover_mode) VALUES ('VOCAB','AG_TEST','INVALID')",
                "INSERT INTO dev_delivery_item(delivery_no,device_no,item_type) VALUES ('VOCAB','CAB_TEST','INVALID')",
                "INSERT INTO dev_repair_detail(wo_no,cabinet_no,repair_mode) VALUES ('VOCAB','CAB_TEST','INVALID')",
                "INSERT INTO dev_repair_detail(wo_no,cabinet_no,repair_mode,test_result) VALUES ('VOCAB','CAB_TEST','ONSITE','INVALID')",
                "INSERT INTO sys_export_task(task_no,request_key,kind,filter_snapshot,scope_snapshot,format) VALUES ('VOCAB','VOCAB','INVENTORY','{}','{}','INVALID')",
                "INSERT INTO sys_export_task(task_no,request_key,kind,filter_snapshot,scope_snapshot,format,status) VALUES ('VOCAB','VOCAB','INVENTORY','{}','{}','XLSX','INVALID')");
    }

    @ParameterizedTest
    @MethodSource("invalidVocabularyWrites")
    void newExecutionTablesRejectValuesOutsideTheirDeclaredVocabulary(String sql) {
        // MySQL 报 3819（CHECK 违反），Spring 归不进 DataIntegrityViolation —— 按约束名断言
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_");
    }
}
