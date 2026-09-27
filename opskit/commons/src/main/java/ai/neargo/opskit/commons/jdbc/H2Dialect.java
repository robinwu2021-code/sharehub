package ai.neargo.opskit.commons.jdbc;

import java.util.List;
import java.util.Set;

/**
 * H2 只用于快速测试档，且固定以 {@code MODE=MySQL} 打开（见测试基座 TestDatabases）。
 * upsert 因此直接复用 MySQL 的 {@code ON DUPLICATE KEY UPDATE}：H2 的标准 {@code MERGE … USING (SELECT ? AS c)}
 * 推断不出参数类型（实测报 Unknown data type），不值得为测试档另维护一套写法。
 */
public final class H2Dialect implements Dialect {

    @Override
    public String name() {
        return "h2";
    }

    @Override
    public String jsonParam() {
        return "? FORMAT JSON";
    }

    @Override
    public String upsert(String table, List<String> keyColumns, List<String> columns, List<String> updateColumns, Set<String> jsonColumns) {
        return MySqlDialect.onDuplicateKey(table, keyColumns, columns, updateColumns, valuePlaceholders(columns, jsonColumns));
    }
}
