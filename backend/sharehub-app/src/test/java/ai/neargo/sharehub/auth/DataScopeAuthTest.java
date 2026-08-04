package ai.neargo.sharehub.auth;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeHandler;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限拦截器（neargo common-data.scope）+ 权限通配的纯单测（无 Spring/DB）。
 * 手动置 {@link DataScopeContext} → 断言 handler 生成的 SQL 片段。
 */
class DataScopeAuthTest {

    private final DataScopeTableRegistry registry = registry();
    private final DataScopeHandler handler = new DataScopeHandler(registry);

    private static DataScopeTableRegistry registry() {
        DataScopeTableRegistry r = new DataScopeTableRegistry();
        r.register("loc_site", Map.of("AGENT", "agent_no", "REGION", "region_id", "SITE", "site_no"));
        return r;
    }

    @AfterEach
    void clear() {
        DataScopeContext.clear();
    }

    @Test
    void agent_scope_injects_agent_no_filter_on_registered_table() {
        DataScopeContext.set(DataScopeSpec.of("AGENT", Set.of("AG001")));
        Expression e = handler.getSqlSegment(new Table("loc_site"), null, "x");
        assertThat(e).isNotNull();
        assertThat(e.toString()).contains("agent_no IN ('AG001')");
    }

    @Test
    void all_scope_does_not_rewrite() {
        DataScopeContext.set(DataScopeSpec.ALL);
        assertThat(handler.getSqlSegment(new Table("loc_site"), null, "x")).isNull();
    }

    @Test
    void unregistered_table_is_passthrough() {
        DataScopeContext.set(DataScopeSpec.of("AGENT", Set.of("AG001")));
        assertThat(handler.getSqlSegment(new Table("ord_rent"), null, "x")).isNull();
    }

    @Test
    void skip_context_bypasses_filter() {
        DataScopeContext.set(DataScopeSpec.of("AGENT", Set.of("AG001")));
        Expression e = DataScopeContext.executeWithoutScope(
                () -> handler.getSqlSegment(new Table("loc_site"), null, "x"));
        assertThat(e).isNull();
    }

    @Test
    void wildcard_permission_semantics() {
        LoginUser admin = new LoginUser(Realm.STAFF, "a", "a", "ADMIN", List.of("*"), "MAIN", "", DataScopeSpec.ALL);
        assertThat(admin.hasPerm("order:refund:audit")).isTrue();

        LoginUser ops = new LoginUser(Realm.STAFF, "o", "o", "OPS",
                List.of("device:cabinet:*", "location:poi:read"), "MAIN", "", DataScopeSpec.ALL);
        assertThat(ops.hasPerm("device:cabinet:read")).isTrue();   // 资源级通配
        assertThat(ops.hasPerm("location:poi:read")).isTrue();     // 精确
        assertThat(ops.hasPerm("order:refund:audit")).isFalse();   // 无
    }
}
