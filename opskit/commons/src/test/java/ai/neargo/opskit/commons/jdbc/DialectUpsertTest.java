package ai.neargo.opskit.commons.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.testing.TestDatabase;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** O0-5 验收：方言 upsert 与 JSON 参数在四个库上行为一致。 */
class DialectUpsertTest {

    private static final String DDL_MYSQL = "CREATE TABLE kv (ws VARCHAR(32) NOT NULL, k VARCHAR(64) NOT NULL, v INT NOT NULL, doc JSON NULL, UNIQUE (ws, k))";
    private static final String DDL_PG = "CREATE TABLE kv (ws VARCHAR(32) NOT NULL, k VARCHAR(64) NOT NULL, v INT NOT NULL, doc JSONB NULL, UNIQUE (ws, k))";

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void upsertInsertsThenUpdatesTheSameRow(TestDatabase db) {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql(db.dialect() instanceof PostgresDialect ? DDL_PG : DDL_MYSQL).update();
        String sql = db.dialect().upsert("kv", List.of("ws", "k"), List.of("ws", "k", "v"), List.of("v"));

        jdbc.sql(sql).params("ws_a", "x", 1).update();
        jdbc.sql(sql).params("ws_a", "x", 2).update();
        jdbc.sql(sql).params("ws_b", "x", 9).update();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM kv").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT v FROM kv WHERE ws = ? AND k = ?").params("ws_a", "x").query(Integer.class).single())
                .isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void upsertWithoutUpdateColumnsKeepsTheFirstRow(TestDatabase db) {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql(db.dialect() instanceof PostgresDialect ? DDL_PG : DDL_MYSQL).update();
        String sql = db.dialect().upsert("kv", List.of("ws", "k"), List.of("ws", "k", "v"), List.of());

        jdbc.sql(sql).params("ws_a", "x", 1).update();
        jdbc.sql(sql).params("ws_a", "x", 2).update();

        assertThat(jdbc.sql("SELECT v FROM kv").query(Integer.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void upsertWithAJsonColumnUpdatesTheDocument(TestDatabase db) {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql(db.dialect() instanceof PostgresDialect ? DDL_PG : DDL_MYSQL).update();
        String sql = db.dialect().upsert("kv", List.of("ws", "k"), List.of("ws", "k", "v", "doc"), List.of("v", "doc"), java.util.Set.of("doc"));

        jdbc.sql(sql).params("ws_a", "x", 1, "{\"zh\":\"旧\"}").update();
        jdbc.sql(sql).params("ws_a", "x", 2, "{\"zh\":\"新\"}").update();

        assertThat(jdbc.sql("SELECT doc FROM kv").query(String.class).single().replace(" ", "")).contains("\"zh\":\"新\"");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void jsonParamIsAcceptedAndReadBack(TestDatabase db) {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql(db.dialect() instanceof PostgresDialect ? DDL_PG : DDL_MYSQL).update();

        jdbc.sql("INSERT INTO kv (ws, k, v, doc) VALUES (?, ?, ?, " + db.dialect().jsonParam() + ")")
                .params("ws_a", "x", 1, "{\"zh\":\"维修\"}").update();

        String doc = jdbc.sql("SELECT doc FROM kv").query(String.class).single();
        assertThat(doc.replace(" ", "")).contains("\"zh\":\"维修\"");
    }
}
