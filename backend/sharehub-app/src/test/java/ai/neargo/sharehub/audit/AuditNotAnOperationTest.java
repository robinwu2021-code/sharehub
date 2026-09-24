package ai.neargo.sharehub.audit;

import ai.neargo.sharehub.config.AuditTrailInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写方法 ≠ 操作：空转的定时请求不该进审计，干了活的那一次必须进。
 *
 * <h2>不挡的代价是「审计表被自己人灌满」</h2>
 * 调价 tick 挂在 {@code * * * * *} 的 cron 上
 * （{@code deploy/tencent/cron/powerbank-price-adjustment}，已在部署里）。
 * 全记下来是<b>一天 1440 行、一个月 4.3 万行</b>，而真实的运营写操作一天只有几百条。
 *
 * <p>这些行还什么都不说：{@code SYSTEM POST /internal/trade/price-adjustments/tick}，
 * 没有对象、没有改动、九成九的执行什么也没做。于是查「谁改了分润规则」时，
 * 真正那一行躺在几万行同样的噪音里 —— <b>审计表变大不等于覆盖变好</b>，
 * 大到没人愿意翻的时候，它等于不存在。
 *
 * <h2>为什么不整条豁免这个端点</h2>
 * 因为<b>干了活的那一次是审计里唯一能看到「系统自己改了价格」的地方</b>，
 * 而 traceId 会把它接到运行日志里那几行 {@code 调价 X 已生效：方案 Y {…} → {…}}。
 * 整条豁免会连那一次一起丢掉。省噪音的正确做法是<b>只省掉没有内容的那些</b>。
 *
 * <p>顺带：这也是 {@code SYSTEM:<服务名>} 那条审计路径今天<b>唯一的活口</b> ——
 * {@code /internal} 上别的写端点都要求登录身份（见 {@code SecurityConfig}），
 * {@code /internal/events/**} 的控制器还没落地。整条豁免掉 tick，
 * 那段代码就没有任何用例覆盖得到了。
 *
 * <h2>为什么「查不到」这个断言必须配一个对照</h2>
 * 「这条请求没留痕」在审计<b>整个坏掉</b>时同样成立。所以下面第一个用例
 * 在同一次运行里查两条：一条不该有、一条该有，<b>同一个端点、同一个调用方</b>，
 * 只有做没做事不同。两条一起才证明是「空转被挡住了」而不是「审计没在工作」。
 */
class AuditNotAnOperationTest extends AuditTestSupport {

    @Test
    @DisplayName("★★ 空转的 tick 不留痕，干了活的同一个 tick 必须留痕")
    void an_idle_tick_leaves_no_trace_while_a_tick_that_did_something_must() {
        String admin = login("ADMIN");

        // 先空跑一次：别的用例可能留下到点未执行的单子，清掉它们，
        // 下面那次才真的是「什么都没做」。这一次不做断言。
        tick(newTraceparent());

        // —— 空转：不该留痕 ——
        String tpIdle = newTraceparent();
        assertThat(tick(tpIdle).path("count").asInt())
                .as("前置：这一次 tick 应当什么都没做").isZero();
        assertThat(auditOfTick(traceIdOf(tpIdle)))
                .as("""
                        空转的 tick 被记进了审计。cron 一分钟一次 ——
                        一天 1440 行、一个月 4.3 万行，且每行都不带对象、不带改动。
                        真正的操作会被埋在里面，而审计表大到没人愿意翻的时候，它等于不存在。""")
                .isNull();

        // —— 干了活：必须留痕 ——
        JsonNode plan = activePlan(admin);
        String planNo = plan.path("planNo").asText();
        double before = plan.path("unitPrice").asDouble();
        String adjustNo = scheduleDueAdjustment(admin, planNo, before + 1);

        String tpWork = newTraceparent();
        try {
            assertThat(tick(tpWork).path("touched").toString())
                    .as("前置：这一次 tick 应当执行到点的那张单子").contains(adjustNo);

            JsonNode row = auditOfTick(traceIdOf(tpWork));
            assertThat(row).as("""
                    系统自己改了价格，审计里却查不到。这是审计表上唯一能看到
                    「这次改价不是人做的」的地方，而 traceId 是它与运行日志
                    （调价 X 已生效：方案 Y …）之间唯一的那根线。
                    省噪音只该省掉空转的那些，不能把这一类整条关掉。""").isNotNull();
            assertThat(row.path("actor").asText())
                    .as("SYSTEM 是可信的那半截（确实是内部触发），冒号后面是调用方自报的")
                    .isEqualTo("SYSTEM:sharehub-scheduler");
            assertThat(row.path("clientCode").isNull() || row.path("clientCode").asText().isEmpty())
                    .as("没有人就没有「从哪个端」——留空比编一个 OPS 诚实")
                    .isTrue();
        } finally {
            // 收拾现场：把方案价格恢复原值，免得污染其它用例
            post("/api/trade/price-adjustments/" + adjustNo + "/revert", Map.of(), admin);
        }
    }

    @Test
    @DisplayName("★ 豁免的每一条都要对应一个真实端点——路径改了而这里没改，噪音会悄悄回来")
    void every_exemption_still_points_at_a_real_endpoint() throws IOException {
        String allSources = mainSources();
        var stale = new TreeSet<String>();
        for (String path : AuditTrailInterceptor.NOT_AN_OPERATION) {
            // **必须是「出现在写方法的映射注解里」**，不能只是「这个串在主代码里出现过」——
            // 声明这批豁免的拦截器自己就是主代码，按后者查等于拿它跟自己比，永远绿。
            // （这条不是推想：第一版就是这么写的，反向验证时把路径改错，测试照样通过。）
            if (!Pattern.compile("@(?:Post|Put|Patch|Delete)Mapping\\(\\s*\"" + Pattern.quote(path) + "\"")
                    .matcher(allSources).find()) {
                stale.add(path);
            }
        }
        assertThat(stale).as("""
                这些豁免找不到对应的写端点映射，多半是端点改了路径而豁免没跟上。
                豁免失效是**静默**的：噪音重新开始灌进审计表，而没有任何报错。

                要么把豁免改成新路径，要么（端点没了的话）删掉这一条。
                注：本检查认的是「@PostMapping("完整路径")」这个形状 ——
                若把路径拆成类上的 @RequestMapping 前缀 + 方法后缀，这里会误红，
                那时改成两段拼接再查，别直接把这条检查去掉。""")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 审计豁免不许悄悄变长——加一条必须改这两个数字，好让它出现在 review 里")
    void the_two_ways_to_skip_an_audit_row_do_not_grow_quietly() throws IOException {
        assertThat(AuditTrailInterceptor.NOT_AN_OPERATION).as("""
                整条路径豁免多了一条。判据见 AuditTrailInterceptor.NOT_AN_OPERATION 的注释：
                只有「另有更好的留痕」才算理由，「这个不重要」不算。""")
                .hasSize(3);

        Matcher m = Pattern.compile("AuditNoop\\.mark\\(").matcher(mainSources());
        int callSites = 0;
        while (m.find()) callSites++;
        assertThat(callSites).as("""
                AuditNoop.mark 的调用点变多了。它声明的是「这次请求确实什么都没发生」，
                判据必须是端点自己算出来的事实（如 touched.isEmpty()），
                不能是路径、角色或调用方。

                用错的后果是**静默**的：事后查「谁做的」，答案会是「没有人」。
                确实该加就改这个数字，并在调用处写明凭什么断定这次是空转。""")
                .isEqualTo(1);
    }

    // ——————————————————————— 脚手架 ———————————————————————

    /** 以「定时器触发」的姿态调一次 tick（匿名 + 自报服务名），返回响应 data。 */
    private JsonNode tick(String traceparent) {
        return postWithHeaders("/internal/trade/price-adjustments/tick", Map.of(), null,
                "traceparent", traceparent, "X-Internal-Caller", "sharehub-scheduler").okData();
    }

    /** 按 action 缩小范围，再用本次请求的 traceId 精确定位 —— 不用「最新一条」。 */
    private JsonNode auditOfTick(String traceId) {
        return findInPages("/api/platform/audit-logs?keyword=price-adjustments/tick",
                "traceId", traceId, login("ADMIN"));
    }

    /** 启用中的方案。调价只对启用方案生效（停用的方案改了也没人用）。 */
    private JsonNode activePlan(String admin) {
        for (JsonNode p : get("/api/trade/price-plans?page=1&size=50", admin).okData().path("list")) {
            if ("ACTIVE".equals(p.path("status").asText())) return p;
        }
        throw new AssertionError("前置：至少要有一个启用中的收费方案");
    }

    /**
     * 造一张「已经到点、但还没执行」的调价单。
     *
     * <p>不能顺手 GET 列表去确认 —— 列表接口会<b>惰性 tick 一次</b>
     * （{@code PriceAdjustmentServiceImpl.page}），那样单子就在 HTTP tick 之前被执行掉了，
     * 本用例测的那次 tick 会变成空转。
     */
    private String scheduleDueAdjustment(String admin, String planNo, double target) {
        Map<String, Object> body = new HashMap<>();
        body.put("planNo", planNo);
        body.put("name", "[审计] 定时执行留痕");
        body.put("patch", Map.of("unitPrice", target));
        body.put("effectiveAt", LocalDate.now().minusDays(1) + "T00:00:00Z");
        body.put("reason", "自动化测试");
        String adjustNo = post("/api/trade/price-adjustments", body, admin).okData().path("adjustNo").asText();
        assertThat(adjustNo).as("前置：调价单应当创建成功").isNotBlank();
        return adjustNo;
    }

    /**
     * 全部主代码，拼成一个串。
     *
     * <p><b>从 backend 根自动发现，不写模块清单</b> —— 清单会在模块结构变动时过期，
     * 而扫描根过期的表现是「卡口照常跑，只是什么都没扫」。
     * 同 {@code scripts/_modules.py}（那边是被同一类失误咬了五次之后改的）。
     */
    private static String mainSources() throws IOException {
        StringBuilder all = new StringBuilder();
        try (var files = Files.walk(Path.of(".."))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/target/")).toList()) {
                all.append(Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }
}
