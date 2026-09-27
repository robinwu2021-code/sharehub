package ai.neargo.opskit.platform.core.auth;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 已校验的令牌声明。 */
record TokenClaims(Map<String, Object> claims) {

    String string(String name) {
        Object v = name == null ? null : claims.get(name);
        return v == null ? null : v.toString();
    }

    /** 数组或空格分隔字符串 → 集合；声明名为空或声明缺失 → 空集（fail-closed）。 */
    Set<String> set(String name) {
        Object v = name == null ? null : claims.get(name);
        Set<String> out = new TreeSet<>();
        if (v instanceof Collection<?> c) {
            c.forEach(x -> {
                if (x != null && !x.toString().isBlank()) {
                    out.add(x.toString());
                }
            });
        } else if (v instanceof String s) {
            for (String part : s.trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    out.add(part);
                }
            }
        }
        return out;
    }
}
