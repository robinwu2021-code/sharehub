package ai.neargo.sharehub.arch;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据范围的两种漏法，都不报错。
 *
 * <p>{@code DataScopeRegistration} 的类注释第一句就写着「注册是建表的一部分，不是可选项」，
 * 而在本卡口之前<b>没有任何东西在执行这句话</b>。两个方向各有各的症状：
 *
 * <h2>① 表根本没注册 → 越权看到别人的</h2>
 * 未注册 = 全局放行。2026-09-24 实测：{@code dev_alarm} 三列归属俱全、一个没注册，
 * 代理的工作台于是列出全平台最近 10 条告警 —— AG002 看到了 AG006 的柜机号。
 * 列表页都没事（那些表注册了），<b>偏偏是聚合首屏漏的</b>，而页面照常渲染。
 * 存量 51 张进台账冻结，只拦新增。
 *
 * <h2>② 注册了、但漏掉某个维度 → 那类主体一行都看不到</h2>
 * handler 是 <b>fail-closed</b>：REGION 主体查一张「登记了 AGENT/SITE 却没登记 REGION」的表，
 * 拿到的是 {@code 1=0}。不是少几行，是一条都没有。
 * 实测 {@code dev_cabinet}/{@code ord_order}/{@code wo_order} 都有 {@code region_id}
 * 而只有 {@code loc_site} 登记了 REGION —— 按区域收敛的员工能看到站点，
 * 却在机柜/订单/工单三个页面一条都看不到。今天库里只配了 ALL 与 SITE，所以还没人踩到。
 * <b>这一半零容忍</b>：它的修法是明确的（表上有那一列就登记），没有「有意不登记」的情形。
 *
 * <h2>档位取自前端，不写死</h2>
 * 「提供了哪些档位」的真源是 {@code ops-web/lib/types/org.ts} 的 {@code DataScope}
 * （{@code DataScopeOptionsTest} 也读它）。写死的话，哪天档位增减了，这条卡口会继续按旧清单判。
 * {@code ALL} 不需要锚点（它就是不过滤）。
 */
class DataScopeCoverageRatchetTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    /** 维度 → 该维度在表上用哪一列过滤。与 DataScopeRegistration 里的写法同源。 */
    private static final Map<String, String> ANCHOR = Map.of(
            "AGENT", "agent_no", "SITE", "site_no", "REGION", "region_id", "SELF", "c_user_no");

    private static final Path REGISTRATION = Path.of("src", "main", "java", "ai", "neargo",
            "sharehub", "config", "DataScopeRegistration.java");
    private static final Path LEDGER = Path.of("..", "known-unscoped-owned-tables.txt");
    private static final Path FRONT_SCOPE = Path.of("..", "..", "ops-web", "lib", "types", "org.ts");

    @Test
    @DisplayName("★★ 带归属列的表都要登记数据范围——漏注册不报错，只是静默放行别人的数据")
    void every_table_with_an_ownership_column_is_registered() throws IOException {
        Set<String> offered = offeredDimensions();
        Map<String, Set<String>> owned = ownedTables(offered);
        Map<String, Set<String>> registered = registrations();

        Set<String> unregistered = new TreeSet<>(owned.keySet());
        unregistered.removeAll(registered.keySet());
        Set<String> allowed = ledger();

        Set<String> added = new TreeSet<>(unregistered);
        added.removeAll(allowed);
        org.assertj.core.api.Assertions.assertThat(added).as("""
                这些表有归属列却没登记数据范围。未注册 = 全局放行，而且**不报错、不告警** ——
                表现是某个角色看到了本不该看到的行，且页面一切正常。

                二选一，都要在 review 里显形：
                  · 该管 → 在 DataScopeRegistration 里登记（把该表有列可用的**所有已提供维度**
                    一起登记：handler 是 fail-closed，漏一个维度 = 那类主体一行都看不到）；
                  · 本就该全局可见（码表/字典/平台级配置）→ 加进
                    known-unscoped-owned-tables.txt 并写明理由。
                「谁都能看」和「没人想起来要管」在代码里长得一模一样，所以必须写下来。""")
                .isEmpty();

        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(unregistered);
        org.assertj.core.api.Assertions.assertThat(stale).as("""
                台账里这些表已经登记过了（或表没了），从 known-unscoped-owned-tables.txt 删掉 ——
                留着会让台账看起来比实际长，下次没人敢信它。""")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 已注册的表不许漏掉有列可用的维度——漏一个，那类主体一行都看不到")
    void a_registered_table_covers_every_dimension_it_has_a_column_for() throws IOException {
        Set<String> offered = offeredDimensions();
        Map<String, Set<String>> owned = ownedTables(offered);
        Map<String, Set<String>> registered = registrations();

        Map<String, Set<String>> blind = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : registered.entrySet()) {
            Set<String> cols = owned.get(e.getKey());
            if (cols == null) continue;
            Set<String> missing = new TreeSet<>();
            for (String dim : offered) {
                if (cols.contains(ANCHOR.get(dim)) && !e.getValue().contains(dim)) missing.add(dim);
            }
            if (!missing.isEmpty()) blind.put(e.getKey(), missing);
        }
        org.assertj.core.api.Assertions.assertThat(blind).as("""
                这些表登记了数据范围，却漏掉了它**本来就有列可用**的维度。
                handler 是 fail-closed —— 该维度的主体查这张表拿到的是 1=0：
                不是少几行，是一条都没有，而且不报错。

                把缺的维度补上即可（列就在表上）。本条零容忍、没有台账：
                它没有「有意不登记」的情形 —— 列都有了还不登记，只会让那类人白屏。""")
                .isEmpty();
    }

    // ——————————————————————— 读三方 ———————————————————————

    /** 提供了哪些档位（前端 DataScope 是真源）。ALL 不需要锚点。 */
    private static Set<String> offeredDimensions() throws IOException {
        Matcher m = Pattern.compile("export type DataScope\\s*=\\s*([^;]+);")
                .matcher(Files.readString(FRONT_SCOPE, StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThat(m.find()).as("前提：读得到前端的 DataScope 定义").isTrue();
        Set<String> out = new TreeSet<>();
        Matcher lit = Pattern.compile("\"(\\w+)\"").matcher(m.group(1));
        while (lit.find()) {
            String d = lit.group(1);
            if (!"ALL".equals(d) && ANCHOR.containsKey(d)) out.add(d);
        }
        return out;
    }

    /** 库里带归属列的表 → 它有哪些归属列。**按库判，不看实体**：列在库里而实体没映射，数据一样会流出去。 */
    private Map<String, Set<String>> ownedTables(Set<String> offered) {
        Set<String> cols = offered.stream().map(ANCHOR::get).collect(Collectors.toSet());
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT table_name AS t, column_name AS c FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name NOT LIKE 'flyway%'""")) {
            String c = String.valueOf(r.get("c"));
            if (cols.contains(c)) {
                out.computeIfAbsent(String.valueOf(r.get("t")), k -> new TreeSet<>()).add(c);
            }
        }
        return out;
    }

    /** 注册表：表 → 登记了哪些维度。 */
    private static Map<String, Set<String>> registrations() throws IOException {
        String src = Files.readString(REGISTRATION, StandardCharsets.UTF_8);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("registry\\.register\\(\"(\\w+)\",\\s*Map\\.of\\((.*?)\\)\\);",
                Pattern.DOTALL).matcher(src);
        while (m.find()) {
            Set<String> dims = new TreeSet<>();
            Matcher d = Pattern.compile("\"(\\w+)\"\\s*,\\s*\"\\w+\"").matcher(m.group(2));
            while (d.find()) dims.add(d.group(1));
            out.put(m.group(1), dims);
        }
        return out;
    }

    private static Set<String> ledger() throws IOException {
        if (!Files.exists(LEDGER)) return Set.of();
        return Files.readAllLines(LEDGER, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))   // 注释行先滤掉：单个 "#" 会 split 成空数组
                .map(l -> l.contains("#") ? l.substring(0, l.indexOf('#')).trim() : l)
                .filter(l -> !l.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
