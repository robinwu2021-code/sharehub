package ai.neargo.sharehub.agent;

/**
 * {@code region_scope} 列的读写转换。
 *
 * <p><b>为什么要有这个类</b>：{@code agt_agent.region_scope} 与 {@code agt_apply.region_scope}
 * 都是 <b>JSON</b> 列（DDL 注释：辖域(区域数组)），而调用方传进来的是一个区域名。
 * 直接写会触发 MariaDB 的 {@code json_valid} CHECK 约束、接口返回 <b>500</b>。
 *
 * <p>这个坑踩过两次：第一次在运营新建/编辑代理商时，修在了 {@code AgentServiceImpl} 的
 * 一个 private 方法里；2026-09-23 入驻审核的激活派生又踩了一遍 ——
 * <b>同一列、同一个约束、同一种 500</b>，只因为那次的修复没有共用出口。
 * 所以这次提出来：凡是写这一列的地方都走这里。
 *
 * <p>只做最小转换，不造语法：这一列目前只承载「一个或几个区域名」，
 * 提前设计一套小语法只会让下一个人猜不到该传什么。
 */
public final class RegionScopeJson {

    private RegionScopeJson() {
    }

    /** 入库：已经是 JSON（{@code [...]} 或 {@code "..."}）就原样放行，否则包成单元素数组。 */
    public static String toJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        if (t.startsWith("[") || t.startsWith("\"")) return t;
        return "[\"" + t.replace("\"", "\\\"") + "\"]";
    }

    /** 出参：把 JSON 数组还原成人读的文本 —— 界面上要显示的是区域名，不是一段 JSON。 */
    public static String toPlain(String json) {
        if (json == null || json.isBlank()) return json;
        String t = json.trim();
        if (!t.startsWith("[")) return t.replaceAll("^\"|\"$", "");
        return t.substring(1, Math.max(1, t.length() - 1)).replaceAll("\"", "").trim();
    }
}
