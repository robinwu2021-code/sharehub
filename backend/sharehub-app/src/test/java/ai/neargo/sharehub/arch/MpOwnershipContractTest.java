package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 端 {@code /mp/**} 的属主收口卡口。
 *
 * <h2>这类缺陷为什么在界面上完全看不出来</h2>
 * C 端<b>没有 RBAC</b> —— 一个消费者能不能看某条数据，全靠服务端自己拿当前登录人的
 * {@code c_user_no} 收口。这个动作<b>没有任何框架会替你做</b>：
 * 忘了写，接口照样 200，只是返回的是别人的订单、别人的发票、别人的报障。
 * 每一页都正常渲染，没有报错，没有告警 —— 这就是 IDOR。
 *
 * <p>今天 38 个 {@code /mp} 端点全部收口正确（逐个读过服务层实现：
 * {@code markRead} / {@code getForUser} 都把 {@code cUserNo} 下推进了查询）。
 * 这个测试的作用不是发现存量问题，是<b>让下一个新端点漏写时当场红</b> ——
 * 靠人记得是靠不住的，而漏写的代价是数据泄露。
 *
 * <h2>两条断言，强度不同</h2>
 * <ol>
 *   <li><b>带 {@code @PathVariable} 的一律不许有例外。</b>路径里有标识 =
 *       调用方在指定「取哪一条」，这正是 IDOR 的形状。今天 8 条全部合规，
 *       所以这条没有台账 —— 真需要例外时再建，那样它会出现在 review 里。</li>
 *   <li><b>其余的走台账，只准变短。</b>登录接口、全体一份的目录本来就没有
 *       「当前登录人」可言，硬要求它们收口只会逼人写无意义的代码。</li>
 * </ol>
 *
 * <p><b>出现 ConsumerContext ≠ 用对了</b> —— 取了 userNo 却没下推到查询里同样会泄露。
 * 这道卡口只保证「没忘」，正确性仍要靠 review。但「忘了」是这里唯一能自动查的，
 * 而它恰好是最常见的那种。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class MpOwnershipContractTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path LEDGER = Path.of("..", "known-mp-unscoped.txt");

    private static final Pattern CLASS_BASE = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"");
    private static final Pattern MAPPING =
            Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*(\\{[^}]*}|\"[^\"]*\")?\\s*\\)");
    /** 下一个映射注解 = 本方法体的结束。缩进四格是本项目控制器的一致写法。 */
    private static final Pattern NEXT_MAPPING = Pattern.compile("\\n {4}@(Get|Post|Put|Delete|Patch)Mapping");

    private record Endpoint(String path, boolean hasPathVariable, boolean usesConsumerContext) {
    }

    @Test
    @DisplayName("路径里带标识的 C 端接口，一律要经过 ConsumerContext —— 没有例外")
    void every_mp_endpoint_with_a_path_variable_scopes_by_the_current_consumer() throws IOException {
        Set<String> offenders = scan().stream()
                .filter(e -> e.hasPathVariable() && !e.usesConsumerContext())
                .map(Endpoint::path)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(offenders).as("""
                这些接口从路径里取标识，却没有拿当前登录人收口 —— 换个人的单号就能读到别人的数据，
                而接口会返回 200。修法二选一（前者更好）：
                  · 把 c_user_no 下推到查询条件：.eq(XxxEntity::getCUserNo, ConsumerContext.userNo())
                    他人单号查不到 = 不存在，连「这个单号存在」都不泄露；
                  · 取出来再断言：ConsumerContext.assertOwner(entity.getCUserNo())。
                确属公开资源（不因登录人不同而不同）才考虑例外 —— 那需要新建台账，在 review 里显形。""")
                .isEmpty();
    }

    @Test
    @DisplayName("不经 ConsumerContext 的 C 端接口台账只准变短")
    void the_unscoped_ledger_only_shrinks() throws IOException {
        Set<String> actual = scan().stream()
                .filter(e -> !e.usesConsumerContext())
                .map(Endpoint::path)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> allowed = ledger();

        Set<String> added = new TreeSet<>(actual);
        added.removeAll(allowed);
        assertThat(added).as("""
                新增了不经 ConsumerContext 的 /mp 接口。先回答一个问题：
                **这个接口的返回值会因为换一个人登录而不同吗？**
                会 → 必须用 ConsumerContext 收口，别加台账；
                不会 → 才能加进 known-mp-unscoped.txt，并把理由写在那里。""")
                .isEmpty();

        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(actual);
        assertThat(stale).as("""
                台账里这些条目已经不成立了（接口被删掉，或者已经改成收口的写法）。
                从 known-mp-unscoped.txt 里删掉它们 —— 留着的话台账会慢慢变成一张
                没人敢动的名单，而那时它就不再表示「这些是经过判断的例外」了。""")
                .isEmpty();
    }

    // ——————————————————————— 扫描 ———————————————————————

    private static List<Endpoint> scan() throws IOException {
        List<Endpoint> out = new ArrayList<>();
        try (var files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher cb = CLASS_BASE.matcher(src);
                String base = cb.find() ? cb.group(1) : "";

                Matcher m = MAPPING.matcher(src);
                while (m.find()) {
                    String body = bodyAfter(src, m.end());
                    for (String sub : paths(m.group(2))) {
                        // 类上有 @RequestMapping 时方法注解可能仍写全路径（本项目两种都有）
                        String full = sub.startsWith("/mp") ? sub : base + sub;
                        if (!full.startsWith("/mp")) continue;
                        out.add(new Endpoint(full,
                                body.contains("@PathVariable"),
                                body.contains("ConsumerContext.")));
                    }
                }
            }
        }
        return out;
    }

    /** {@code @PostMapping({"", "/{no}"})} 有多个路径 —— 只取第一个会漏掉带标识的那个。 */
    private static List<String> paths(String annotationArg) {
        if (annotationArg == null) return List.of("");
        List<String> out = new ArrayList<>();
        Matcher q = Pattern.compile("\"([^\"]*)\"").matcher(annotationArg);
        while (q.find()) out.add(q.group(1));
        return out.isEmpty() ? List.of("") : out;
    }

    private static String bodyAfter(String src, int from) {
        String tail = src.substring(from);
        Matcher n = NEXT_MAPPING.matcher(tail);
        return n.find() ? tail.substring(0, n.start()) : tail;
    }

    private static Set<String> ledger() throws IOException {
        return Files.readAllLines(LEDGER, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
