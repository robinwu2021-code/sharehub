package ai.neargo.opskit.commons.jdbc;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class PostgresDialect implements Dialect {

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String jsonParam() {
        return "CAST(? AS JSONB)";
    }

    @Override
    public String upsert(String table, List<String> keyColumns, List<String> columns, List<String> updateColumns, Set<String> jsonColumns) {
        Dialect.requireSubset(columns, keyColumns, "keyColumns");
        Dialect.requireSubset(columns, updateColumns, "updateColumns");
        String action = updateColumns.isEmpty()
                ? "DO NOTHING"
                : "DO UPDATE SET " + updateColumns.stream().map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + valuePlaceholders(columns, jsonColumns) + ") ON CONFLICT (" + String.join(", ", keyColumns) + ") " + action;
    }
}
