package ai.neargo.opskit.platform.store;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.testing.TestDatabase;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class PlatformMigrationsTest {

    /** 21/04 §二 的平台 18 张表（含 O1-12 的 plat_member_attr）。 */
    static final List<String> TABLES = List.of(
            "plat_account", "plat_workspace", "plat_app", "plat_app_workspace", "plat_idp",
            "plat_member", "plat_group", "plat_group_member", "plat_role", "plat_role_binding",
            "plat_calendar", "plat_webhook_subscription", "plat_outbox", "plat_webhook_delivery",
            "plat_idempotency", "plat_audit_log", "plat_scheduled_tasks", "plat_member_attr");

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void createsEveryPlatformTableAndIsRerunnable(TestDatabase db) {
        assertThat(PlatformMigrations.migrate(db.dataSource()).migrationsExecuted).isEqualTo(3);
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        for (String table : TABLES) {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single()).as(table).isZero();
        }
        assertThat(PlatformMigrations.migrate(db.dataSource()).migrationsExecuted).isZero();
    }

    /** 模板空间用空串而非 NULL：同账户同代号的模板角色只能有一个（唯一键里 NULL 不参与比较）。 */
    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void accountLevelTemplatesAreUniqueBecauseWorkspaceIsEmptyStringNotNull(TestDatabase db) {
        PlatformMigrations.migrate(db.dataSource());
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        String insert = "INSERT INTO plat_role (account_id, workspace_id, role_code, name, permissions, updated_at_ms) VALUES (?, '', ?, "
                + db.dialect().jsonParam() + ", " + db.dialect().jsonParam() + ", 0)";
        jdbc.sql(insert).params("ac_1", "approver", "{\"zh\":\"审批\"}", "[]").update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql(insert).params("ac_1", "approver", "{\"zh\":\"审批\"}", "[]").update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
