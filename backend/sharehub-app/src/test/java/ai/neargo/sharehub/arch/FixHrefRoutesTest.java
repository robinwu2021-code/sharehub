package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门禁清单里的 **`fixHref` 必须指向运营端真实存在的路由**。
 *
 * <h3>为什么要有这一条</h3>
 * `Checklist.Item.fixHref` 的契约是「未通过时给出**去哪儿处理**」——
 * 门禁不给去处就成了拦路虎而不是向导。而 2026-09-25 接入时实测：
 * 设备、站点、代理清退三处给的全是**运营端不存在的路径**
 * （`/devices/CAB1000?tab=qc`、`/sites/ST300?tab=survey`、`/agents/AG031?tab=assign`），
 * 点「去处理」一律 404。三个前端各自写了一层改写兜住，
 * 于是**同一份路由知识散在三处前端**，而真源在后端。
 *
 * <p>本条把真源钉回后端：扫所有 `fixHref` 字面量，逐条比对运营端的页面与页签。
 * 前端那几层改写因此降级为兜底（对已经正确的路由原样返回）。
 *
 * <h3>为什么用静态扫描而不是跑接口</h3>
 * 跑接口只能覆盖「当前数据恰好触发的那几条」——门禁大多数分支要特定数据才走到，
 * 而写错路由的恰恰是那些少见分支。扫字面量能把每一条都看到。
 *
 * <h3>红了怎么办</h3>
 * 失败信息会列出不认识的路径。要么改成下面 {@link #ROUTES} 里已有的形态，
 * 要么**先在运营端把那个页面 / 页签做出来**再把它加进白名单 —— 别反过来。
 */
class FixHrefRoutesTest {

    private static final Path BACKEND = Path.of("..");

    /** 运营端真实路由：路径 → 该页允许的 `tab`/`view` 取值（空集 = 该页不吃页签参数）。 */
    private static final java.util.Map<String, Set<String>> ROUTES = java.util.Map.of(
            "/devices", Set.of("cabinets", "powerbanks", "monitor", "commands", "logs", "inventory", "ota", "codes", "diffs"),
            "/devices/detail", Set.of("overview", "slots", "trial", "protections", "qc", "signals"),
            "/operation/sites", Set.of("basic", "survey", "points", "cabinets", "contracts", "partners", "pricing", "sharing", "stats", "audit"),
            "/work-orders", Set.of("list", "board", "inspection", "sla"),
            "/venues", Set.of("venues", "contracts", "crm", "onboarding", "lifecycle"),
            "/agents", Set.of("applies", "profiles", "commission", "assign", "performance", "accounts"),
            "/finance", Set.of("rules", "records", "settlements", "withdrawals", "payout", "adjustments"),
            "/pricing", Set.of(),
            "/alarms", Set.of("notices", "codes", "rules"),
            "/orders", Set.of("list", "reservations", "exceptions", "complaints", "refunds", "free", "deposit"));

    /** 形如 {@code "/devices/detail?no=" + no + "&tab=qc"} —— 取开头那段字面量即可判路径。 */
    private static final Pattern LITERAL = Pattern.compile("\"(/[a-z][a-z0-9/-]*)(\\?[^\"]*)?\"");

    @Test
    @DisplayName("★ 每个 fixHref 都指向运营端存在的页面与页签（点「去处理」不能 404）")
    void every_fix_href_points_at_a_real_route() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path f : sourcesWithChecklist()) {
            String src = Files.readString(f);
            for (String raw : hrefLiterals(src)) {
                String path = raw.split("\\?")[0];
                Set<String> tabs = ROUTES.get(path);
                if (tabs == null) {
                    problems.add(f.getFileName() + ": 「" + raw + "」不是运营端的页面 —— 点「去处理」会 404");
                    continue;
                }
                String tab = paramOf(raw, "tab");
                if (tab == null) tab = paramOf(raw, "view");
                if (tab != null && !tabs.contains(tab)) {
                    problems.add(f.getFileName() + ": 「" + raw + "」的页签「" + tab + "」在该页不存在，可选：" + tabs);
                }
            }
        }
        assertThat(problems).as("%s", String.join("\n", problems)).isEmpty();
    }

    @Test
    @DisplayName("扫到了东西（一条都没扫到 = 正则失效，本卡口静默失效）")
    void the_scan_actually_finds_hrefs() throws IOException {
        int n = 0;
        for (Path f : sourcesWithChecklist()) n += hrefLiterals(Files.readString(f)).size();
        assertThat(n).as("门禁源文件里应当扫得到 fixHref 字面量").isGreaterThanOrEqualTo(10);
    }

    /** 产出 `Checklist` 的那几个服务实现。 */
    private static List<Path> sourcesWithChecklist() throws IOException {
        try (Stream<Path> w = Files.walk(BACKEND)) {
            return w.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(FixHrefRoutesTest::mentionsChecklistItem)
                    .toList();
        }
    }

    private static boolean mentionsChecklistItem(Path p) {
        try {
            return Files.readString(p).contains("Checklist.Item(");
        } catch (IOException e) {
            return false;
        }
    }

    /** 取该文件里所有「看起来是运营端链接」的字面量（以 / 开头且带路径段）。 */
    private static List<String> hrefLiterals(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = LITERAL.matcher(src);
        while (m.find()) {
            String path = m.group(1);
            // 只看页面链接：接口路径（/api/**、/internal/**）与单段的根路径不是 fixHref
            if (path.startsWith("/api") || path.startsWith("/internal") || path.startsWith("/mp")) continue;
            if (path.length() < 2 || !path.substring(1).matches("[a-z][a-z0-9/-]*")) continue;
            out.add(path + (m.group(2) == null ? "" : m.group(2)));
        }
        return new ArrayList<>(out);
    }

    private static String paramOf(String href, String key) {
        Matcher m = Pattern.compile("[?&]" + key + "=([a-zA-Z0-9_-]*)").matcher(href);
        return m.find() ? m.group(1) : null;
    }
}
