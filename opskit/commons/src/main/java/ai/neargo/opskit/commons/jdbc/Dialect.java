package ai.neargo.opskit.commons.jdbc;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 三种数据库在 SQL 上的全部差异都收敛在这里（04 §四）。仓储代码只写两库交集语法，
 * 碰到下面几件事时向方言要片段。
 */
public interface Dialect {

    String name();

    /** JSON 列的参数占位：MySQL 直接收字符串；PostgreSQL 要转 JSONB；H2 要声明为 JSON 格式。 */
    String jsonParam();

    /**
     * 「有则更新、无则插入」。
     *
     * @param table      表名
     * @param keyColumns 构成唯一键的列（冲突判定依据）
     * @param columns    要写入的全部列（含键列），参数按此顺序绑定
     * @param updateColumns 冲突时要更新的列（须是 columns 的子集）
     * @param jsonColumns   其中的 JSON 列（占位要按方言转换）
     */
    String upsert(String table, List<String> keyColumns, List<String> columns, List<String> updateColumns, Set<String> jsonColumns);

    /** 不含 JSON 列的 upsert。 */
    default String upsert(String table, List<String> keyColumns, List<String> columns, List<String> updateColumns) {
        return upsert(table, keyColumns, columns, updateColumns, Set.of());
    }

    /** VALUES 里的占位：JSON 列用 {@link #jsonParam()}，其余用 {@code ?}。 */
    default String valuePlaceholders(List<String> columns, Set<String> jsonColumns) {
        requireSubset(columns, List.copyOf(jsonColumns), "jsonColumns");
        return columns.stream().map(c -> jsonColumns.contains(c) ? jsonParam() : "?").collect(Collectors.joining(", "));
    }

    /** 分页后缀。三库都支持 LIMIT/OFFSET，集中在这里只为将来换库时有一处可改。 */
    default String limitOffset(int limit, long offset) {
        if (limit <= 0 || offset < 0) {
            throw new IllegalArgumentException("limit must be > 0 and offset >= 0");
        }
        return " LIMIT " + limit + " OFFSET " + offset;
    }

    static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    static void requireSubset(List<String> columns, List<String> sub, String what) {
        if (!columns.containsAll(sub)) {
            throw new IllegalArgumentException(what + " must be a subset of columns: " + sub);
        }
    }
}
