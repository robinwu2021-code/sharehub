package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一个对象的状态机，两端的**边**（从哪能到哪）必须对得上。
 *
 * <h2>与 {@code StatusVocabularyAcrossEndsTest} 的分工</h2>
 * 那一条比的是**节点集**（这个对象有哪些状态），两端已经一字不差。
 * 但「有哪些状态」和「能怎么走」是两件事 —— <b>此前没有任何检查比较边</b>。
 *
 * <h2>分叉方向的代价不对称</h2>
 * <table>
 *   <tr><th>方向</th><th>症状</th><th>严重度</th></tr>
 *   <tr><td>前端边比后端**宽**</td><td>按钮亮着，点了报错</td><td>吵，但安全</td></tr>
 *   <tr><td>前端边比后端**窄**</td><td><b>按钮灰着，而接口放行</b></td><td>静默失能</td></tr>
 * </table>
 *
 * <p>后者已经发生过：前端把 {@code accept} 写成 {@code →PROCESSING}，而后端落
 * {@code ACCEPTED}，于是没有任何动作的 {@code from} 含 {@code ACCEPTED} ——
 * <b>那张工单在界面上一个按钮都没有</b>，处理/完工/驳回全部消失，且不报错。
 * 是人肉发现的。本卡口就是为了下一次不靠人。
 *
 * <h2>⚠️ 那个历史缺陷落在<b>方向二</b>，不是方向一</h2>
 * 立本卡口时拿它做了反向对照，结果红的是 {@code backend_only_edges_shrink_only}：
 * 把 {@code accept} 改成 {@code DISPATCHED→PROCESSING} 之后，方向一<b>照样绿</b> ——
 * 因为后端 {@code DISPATCHED→ACCEPTED→PROCESSING} 的<b>传递闭包</b>里就有
 * {@code DISPATCHED→PROCESSING}，它确实"可达"。红的是后端那条
 * {@code DISPATCHED→ACCEPTED} 在运营端再也走不出来了。
 *
 * <p>这件事有个反直觉的推论，写在这里免得下一个人搞反：
 * <b>方向一宽容（用闭包），所以更严重的那一类失效落在方向二的台账上。</b>
 * 台账不是"软的那一条" —— 往 {@code known-missing-ui-transitions.txt} 里加行之前，
 * 先确认它真属于文件头写的 A/B 两类，而不是又一次「按钮灰着接口放行」。
 *
 * <h2>三个设计决定（都有具体理由，别随手改）</h2>
 * <ol>
 *   <li><b>配对靠状态类型名，不靠状态机名。</b>{@code WoStateMachine} 与
 *       {@code WO_TRANSITIONS} 机械对不上；而前端表的类型注解里写着
 *       {@code to: WorkOrderStatus}，后端迁移表声明的是
 *       {@code Map<String, Map<WorkOrderStatus, WorkOrderStatus>>} ——
 *       这两个同名词表 {@code StatusVocabularyAcrossEndsTest} 已经保证一字不差，
 *       <b>拿它当主键是安全的</b>。</li>
 *   <li><b>不比事件名。</b>后端 {@code "ACCEPT"}/{@code "DONE"}，前端
 *       {@code accept}/{@code complete} —— 事件名是各端的词汇，不是契约。只比 (from, to)。</li>
 *   <li><b>方向一比的是「可达」，不是「存在」。</b>工单的 {@code close} 前端是
 *       {@code DONE→CLOSED}，后端是 {@code DONE→AUDITED→CLOSED} 两跳 ——
 *       {@code AUDITED} 是过程态、不单独出按钮，这是<b>合法的复合边</b>。
 *       要求边集相等会把它误报成缺陷，所以用后端边集的**传递闭包**判定。</li>
 * </ol>
 *
 * <h2>自环不参与方向一</h2>
 * 前端 {@code process} 含 {@code PROCESSING→PROCESSING}（处理中可多次提交进展，
 * <b>只留痕不改状态</b>），后端 {@code handle()} 对 PROCESSING 显式跳过状态机。
 * 自环不改变状态，因此它不可能是「后端会拒绝的迁移」—— 状态机的拒绝面是**改状态**这件事。
 * 把自环算进去只会逼着状态机为了对齐而加一条没有语义的边。
 *
 * <h2>为什么方向二要台账而方向一零容忍</h2>
 * 方向一（前端有、后端无）是**会当场报错的真缺陷**，没有理由留。
 * 方向二（后端有、前端无）里有大量合法情况：过程态不出按钮、某些迁移只由
 * 设备回调/定时任务推进、运营端还没建那张表。一刀切零容忍会逼人写假豁免，
 * 所以登记进 {@code known-missing-ui-transitions.txt}，**只准变短**。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class StateMachineEdgeAcrossEndsTest {

    private static final Path FRONTEND_TYPES = Path.of("..", "..", "ops-web", "lib", "types");

    /** 一条边：{@code WorkOrderStatus: CREATED->DISPATCHED}。 */
    private record Edge(String vocab, String from, String to) {
        @Override
        public String toString() {
            return vocab + ": " + from + "->" + to;
        }
    }

    // ───────────────────────── 方向一 ─────────────────────────

    @Test
    @DisplayName("★★ 前端声明的每条迁移，后端必须可达——否则按钮亮着、点了报错")
    void every_frontend_edge_is_reachable_in_the_backend() throws IOException {
        Map<String, Set<Edge>> backend = backendEdges();
        Map<String, Set<Edge>> frontend = frontendEdges();

        Set<String> shared = new TreeSet<>(backend.keySet());
        shared.retainAll(frontend.keySet());
        assertThat(shared)
                .as("前提：两端应当有同名状态词表的迁移表可比（一个都没有说明解析坏了）")
                .isNotEmpty();

        Map<String, String> unreachable = new LinkedHashMap<>();
        for (String vocab : shared) {
            Set<Edge> closure = transitiveClosure(backend.get(vocab));
            Set<String> bad = new TreeSet<>();
            for (Edge e : frontend.get(vocab)) {
                if (e.from().equals(e.to())) continue;          // 自环：见类注释
                if (!closure.contains(e)) bad.add(e.from() + "->" + e.to());
            }
            if (!bad.isEmpty()) unreachable.put(vocab, String.join(" · ", bad));
        }

        assertThat(unreachable).as("""
                运营端声明了这些迁移，而后端状态机走不到（直达与多跳都算过了）。

                症状是**按钮亮着，点下去报 400**。以后端状态机为准改前端的
                `*_TRANSITIONS`；若确实该放行，往后端状态机加边，**不要**在 service 里
                另写一个 if —— 两套规则并存必然分叉。""")
                .isEmpty();
    }

    // ───────────────────────── 方向二 ─────────────────────────

    @Test
    @DisplayName("后端有而运营端没有的迁移，只准变少（台账）")
    void backend_only_edges_shrink_only() throws IOException {
        Set<String> actual = backendOnlyEdges();
        Path ledger = backendRoot().resolve("known-missing-ui-transitions.txt");
        Set<String> known = readLedger(ledger);

        assertThat(actual)
                .as("应当扫描到后端边；一条都没有更可能是扫描坏了")
                .isNotEmpty();

        Set<String> added = new TreeSet<>(actual);
        added.removeAll(known);
        assertThat(added).as("""
                后端状态机能走这些迁移，而运营端没有对应动作 —— 症状是
                **接口放行、界面上没有入口**（或更糟：界面自己手写了一份 if，
                后端加边时没有任何东西会提醒）。

                要么在 ops-web/lib/types 里补进对应的 `*_TRANSITIONS`，
                要么说明理由后加进 known-missing-ui-transitions.txt。""")
                .isEmpty();

        Set<String> stale = new TreeSet<>(known);
        stale.removeAll(actual);
        assertThat(stale).as("""
                这些条目在代码里已经不存在了（前端补上了，或后端删了边），
                从台账删掉 —— 留着会让台账看起来比实际长，下次没人敢信它。""")
                .isEmpty();
    }

    private static Set<String> backendOnlyEdges() throws IOException {
        Map<String, Set<Edge>> backend = backendEdges();
        Map<String, Set<Edge>> frontend = frontendEdges();
        Set<String> out = new TreeSet<>();
        for (var e : backend.entrySet()) {
            Set<Edge> ui = frontend.getOrDefault(e.getKey(), Set.of());
            Set<Edge> uiClosure = transitiveClosure(ui);
            for (Edge edge : e.getValue()) {
                if (!uiClosure.contains(edge)) out.add(edge.toString());
            }
        }
        return out;
    }

    // ───────────────────────── 后端：扫状态机 ─────────────────────────

    /**
     * 迁移表的词表声明。三种形状：
     * {@code Map<String, Map<XxxStatus, XxxStatus>>}（多数状态机）·
     * {@code List<XxxStatus>} / {@code Set<XxxStatus>}（record 形状的 from 集合，
     * 如 {@code CampaignStateMachine.Transition} 与 {@code InterventionDtos.Rule}）。
     */
    private static final Pattern BACKEND_VOCAB = Pattern.compile(
            "Map\\s*<\\s*String\\s*,\\s*Map\\s*<\\s*(\\w+Status)\\s*,\\s*\\1\\s*>\\s*>"
                    + "|List\\s*<\\s*(\\w+Status)\\s*>"
                    + "|Set\\s*<\\s*(\\w+Status)\\s*>");

    /** {@code XxxStatus.CONSTANT} */
    private static final Pattern ENUM_REF = Pattern.compile("\\b(\\w+Status)\\s*\\.\\s*([A-Z][A-Z0-9_]*)\\b");

    private static Map<String, Set<Edge>> backendEdges() throws IOException {
        Map<String, Set<Edge>> out = new LinkedHashMap<>();
        List<Path> all = mainJavaFiles();
        assertThat(all).as("应当扫描到后端源码").isNotEmpty();

        for (Path f : all) {
            String name = f.getFileName().toString();
            boolean mustParse = name.endsWith("StateMachine.java");
            String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));

            Matcher v = BACKEND_VOCAB.matcher(src);
            if (!v.find()) {
                // 名字叫 StateMachine 却解析不出词表 = 出现了本解析器不认识的写法。
                // **宁可红，也不要静默跳过** —— arch-guard 正是这么假绿过一次：
                // 绿得完美，什么都没在守。
                if (mustParse) {
                    throw new AssertionError(name + " 里找不到 Map<String, Map<XxxStatus, XxxStatus>> "
                            + "/ List<XxxStatus> / Set<XxxStatus> —— 出现了新写法，"
                            + "请同步本解析器（不要给它开豁免）");
                }
                continue;
            }
            String vocab = firstNonNull(v);

            Set<Edge> edges = new LinkedHashSet<>();
            edges.addAll(mapOfEdges(src, vocab));
            edges.addAll(collectionToEdges(src, vocab));
            if (edges.isEmpty()) {
                if (mustParse) {
                    throw new AssertionError(name + " 解析出 0 条边 —— 写法变了，请同步本解析器");
                }
                continue;
            }
            out.computeIfAbsent(vocab, k -> new LinkedHashSet<>()).addAll(edges);
        }
        return out;
    }

    private static String firstNonNull(Matcher v) {
        for (int i = 1; i <= v.groupCount(); i++) {
            if (v.group(i) != null) return v.group(i);
        }
        throw new IllegalStateException("词表正则匹配了但没有捕获组");
    }

    /** {@code Map.of(X.A, X.B, X.C, X.D)} —— 按出现顺序两两成对。 */
    private static Set<Edge> mapOfEdges(String src, String vocab) {
        Set<Edge> out = new LinkedHashSet<>();
        for (String inner : innerArgsOf(src, "Map.of(")) {
            List<String> consts = enumConstants(inner, vocab);
            if (consts.size() < 2 || consts.size() % 2 != 0) continue;   // 外层 Map.of（键是事件名）
            for (int i = 0; i + 1 < consts.size(); i += 2) {
                out.add(new Edge(vocab, consts.get(i), consts.get(i + 1)));
            }
        }
        return out;
    }

    /**
     * {@code new Transition(List.of(X.A, X.B), X.C, "…")} / {@code new Rule(Set.of(X.A), X.B)}
     * —— from 是集合，to 紧随其后。
     *
     * <p>{@code to} 为 {@code null} 的条目（干预里的「只留痕不改状态」）不产生边，
     * 正则要求 {@code to} 是枚举常量，天然跳过。
     */
    private static Set<Edge> collectionToEdges(String src, String vocab) {
        Set<Edge> out = new LinkedHashSet<>();
        // 必须出现在 record 构造器里（`new Transition(…)` / `new Rule(…)`）。
        // 不加这个前缀会把 `Set<String> TERMINAL = Set.of(P.SOLD.name(), P.SCRAP.name())`
        // 这类**终态集合**当成一条 SOLD→SCRAP 的边 —— 它不是迁移表，
        // 而凭空多一条边会让台账里出现一条谁也解释不了的记录。
        Matcher m = Pattern.compile(
                        "new\\s+\\w+\\s*\\(\\s*(?:List|Set)\\.of\\(([\\s\\S]*?)\\)\\s*,\\s*"
                                + vocab + "\\.([A-Z][A-Z0-9_]*)")
                .matcher(src);
        while (m.find()) {
            String to = m.group(2);
            for (String from : enumConstants(m.group(1), vocab)) {
                out.add(new Edge(vocab, from, to));
            }
        }
        return out;
    }

    private static List<String> enumConstants(String s, String vocab) {
        List<String> out = new java.util.ArrayList<>();
        for (Matcher m = ENUM_REF.matcher(s); m.find(); ) {
            if (m.group(1).equals(vocab)) out.add(m.group(2));
        }
        return out;
    }

    /** 取每个 {@code token} 调用的实参文本（按括号配平截取，支持嵌套）。 */
    private static List<String> innerArgsOf(String src, String token) {
        List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while ((i = src.indexOf(token, i)) >= 0) {
            int open = i + token.length() - 1;
            int depth = 0, j = open;
            for (; j < src.length(); j++) {
                char c = src.charAt(j);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
            }
            if (j < src.length()) out.add(src.substring(open + 1, j));
            i = open + 1;
        }
        return out;
    }

    // ───────────────────────── 前端：扫迁移表 ─────────────────────────

    /**
     * {@code export const X: Record<Action, { from: …; to: … }> = { … }}。
     *
     * <p><b>不要求名字叫 {@code *_TRANSITIONS}</b>：{@code ORDER_INTERVENTIONS}
     * 就是一张迁移表（订单人工干预），名字不带 TRANSITIONS。按**形状**认
     * （类型里同时有 {@code from} 与 {@code to}），与后端侧同一个原则 ——
     * 靠名字就要靠人记得起名，那等于又一张手工清单。
     */
    private static final Pattern FRONTEND_TABLE =
            Pattern.compile("export const (\\w+)\\s*:\\s*Record\\s*<([\\s\\S]*?)>\\s*=\\s*\\{");

    /**
     * 从类型注解里取词表名。
     *
     * <p>后面必须跟 {@code ; , } |} —— 这样 {@code to: OrderStatus | null}（干预表）能取到，
     * 而 {@code to: AdCampaign["status"]} 这类索引访问类型取不到、整张表跳过（它没有
     * 对应的后端状态机，漏掉不掩盖任何东西）。
     */
    private static final Pattern TO_TYPE = Pattern.compile("to\\s*:\\s*(\\w+)\\s*(?=[;,}|])");

    private static Map<String, Set<Edge>> frontendEdges() throws IOException {
        Map<String, Set<Edge>> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(FRONTEND_TYPES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".ts")).sorted().toList()) {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                Matcher m = FRONTEND_TABLE.matcher(src);
                while (m.find()) {
                    String typeArgs = m.group(2);
                    if (!typeArgs.contains("from") || !typeArgs.contains("to")) continue;  // 不是迁移表
                    Matcher t = TO_TYPE.matcher(typeArgs);
                    // 取不到具名词表（如 AdCampaign["status"] 这类索引访问类型）→ 无从配对，跳过。
                    // 这类表对应的后端也没有状态机，漏掉它不会掩盖任何东西。
                    if (!t.find()) continue;
                    String vocab = t.group(1);
                    String body = braceBody(src, m.end() - 1);
                    out.computeIfAbsent(vocab, k -> new LinkedHashSet<>()).addAll(entryEdges(body, vocab));
                }
            }
        }
        return out;
    }

    /**
     * 表体里每个 {@code action: { from: ["A","B"], to: "C" }}。
     *
     * <p><b>逐条按花括号切开再取 from/to</b>，不是在整个表体上跑一条跨条目的正则 ——
     * 后者碰到 {@code to: null} 的条目（干预里的「只留痕不改状态」）会一路匹配到
     * <b>下一条</b>的 {@code to}，凭空造出一条谁也没声明过的边。
     */
    private static Set<Edge> entryEdges(String body, String vocab) {
        Set<Edge> out = new LinkedHashSet<>();
        Matcher e = Pattern.compile("(\\w+)\\s*:\\s*\\{").matcher(body);
        while (e.find()) {
            String entry = braceBody(body, e.end() - 1);
            Matcher f = Pattern.compile("from\\s*:\\s*\\[([^\\]]*)\\]").matcher(entry);
            Matcher t = Pattern.compile("to\\s*:\\s*\"(\\w+)\"").matcher(entry);
            if (!f.find() || !t.find()) continue;        // to: null → 不改状态，不是边
            for (Matcher v = Pattern.compile("\"(\\w+)\"").matcher(f.group(1)); v.find(); ) {
                out.add(new Edge(vocab, v.group(1), t.group(1)));
            }
        }
        return out;
    }

    /** 从 {@code {} 的位置按花括号配平取出表体。 */
    private static String braceBody(String src, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(openBrace + 1, i);
        }
        return "";
    }

    // ───────────────────────── 图与文件 ─────────────────────────

    /** 传递闭包：多跳也算可达（见类注释「方向一比的是可达」）。 */
    private static Set<Edge> transitiveClosure(Set<Edge> edges) {
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        String vocab = edges.isEmpty() ? "" : edges.iterator().next().vocab();
        for (Edge e : edges) adj.computeIfAbsent(e.from(), k -> new LinkedHashSet<>()).add(e.to());

        Set<Edge> out = new LinkedHashSet<>(edges);
        for (String start : new LinkedHashSet<>(adj.keySet())) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> q = new ArrayDeque<>(adj.get(start));
            while (!q.isEmpty()) {
                String n = q.poll();
                if (!seen.add(n)) continue;
                out.add(new Edge(vocab, start, n));
                q.addAll(adj.getOrDefault(n, Set.of()));
            }
        }
        return out;
    }

    /** 去掉注释：注释里的示例迁移（类注释画的那张图）不能被当成真边。 */
    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * 全部主源码。
     *
     * <p><b>为什么扫全仓而不是只扫 {@code *StateMachine.java}</b>：迁移表不一定叫这个名字。
     * {@code InterventionDtos.RULES} 就是一台状态机（订单人工干预：
     * {@code DISPENSING → SETTLED} 强制结算），它不走 {@code OrdStateMachine}、
     * 名字里也没有 StateMachine —— 只扫名字就会漏掉它，而运营端恰恰有一张
     * {@code ORDER_INTERVENTIONS} 与它对应，两端各写一份、互不相认。
     *
     * <p>用手工清单登记这类表是不行的 —— {@code StatusVocabularyAcrossEndsTest} 的
     * 类注释已经把这个教训写明：<b>一张需要人记得更新的清单，早晚会停在某个版本上</b>。
     * 所以靠形状自动发现：{@code Map<String, Map<XxxStatus, XxxStatus>>} /
     * {@code List<XxxStatus>} / {@code Set<XxxStatus>}。A1 之后状态都是枚举，
     * 这三种形状基本只出现在迁移表里。
     */
    private static List<Path> mainJavaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(backendRoot())) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .sorted().toList();
        }
    }

    private static Set<String> readLedger(Path p) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-bare-status-literals.txt"))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("找不到 backend 根目录");
        return p;
    }
}
