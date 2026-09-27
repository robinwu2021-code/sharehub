package ai.neargo.opskit.commons.jdbc;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** MySQL 8 与 MariaDB 10.6+ 共用。 */
public final class MySqlDialect implements Dialect {

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String jsonParam() {
        return "?";
    }

    @Override
    public String upsert(String table, List<String> keyColumns, List<String> columns, List<String> updateColumns, Set<String> jsonColumns) {
        return onDuplicateKey(table, keyColumns, columns, updateColumns, valuePlaceholders(columns, jsonColumns));
    }

    /** H2（MODE=MySQL）复用同一写法，只是占位不同。 */
    static String onDuplicateKey(String table, List<String> keyColumns, List<String> columns, List<String> updateColumns, String values) {
        Dialect.requireSubset(columns, keyColumns, "keyColumns");
        Dialect.requireSubset(columns, updateColumns, "updateColumns");
        // MariaDB 不认 MySQL 8.0.19 的行别名写法，统一用 VALUES(col)（两者都支持）
        String updates = updateColumns.isEmpty()
                ? keyColumns.get(0) + " = " + keyColumns.get(0)
                : updateColumns.stream().map(c -> c + " = VALUES(" + c + ")").collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + values + ") ON DUPLICATE KEY UPDATE " + updates;
    }
}
