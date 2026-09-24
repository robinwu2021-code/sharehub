package ai.neargo.sharehub.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计记「成没成」与「哪一次请求」（T3-1）。
 *
 * <h2>为什么失败的也要记</h2>
 * 此前拦截器遇到 {@code >=400} 直接跳过。顾虑是对的（没做成的混进来会让
 * 「谁改了什么」失真），但结论反了 —— <b>被拒绝的操作恰恰最该留痕</b>：
 * 有人拿没权限的账号反复点某个危险操作是安全信号，
 * 而不记的话，事后查「谁试过改分润规则」得到的答案是<b>「没有人」</b>，
 * 且这个答案和「真的没人试过」长得一模一样。
 *
 * <h2>为什么要 traceId</h2>
 * 审计回答「谁改了什么」，运行日志回答「那次请求到底发生了什么」，两边各有一半。
 * 没有这根线，从一条可疑的审计记录追到它的日志只能拿时间戳去猜 ——
 * 而可疑操作往往就发生在请求最密集的时候。
 *
 * <p>每条用例都按自带的 traceparent 回找那一行，理由见 {@link AuditTestSupport}。
 */
class AuditOutcomeAndTraceTest extends AuditTestSupport {

    /** 代理没有 {@code location:venue:create}，拿它造一个真实的 403。 */
    private static final String AGENT_FORBIDDEN_WRITE = "/api/ops/venues";

    @Test
    @DisplayName("★★ 被拒绝的写操作要能查得到——否则「谁试过」的答案永远是「没有人」")
    void a_denied_write_is_recorded_so_you_can_see_who_tried() {
        String agent = loginAgent("AG002");
        String tp = newTraceparent();

        assertThat(postWithHeaders(AGENT_FORBIDDEN_WRITE, venueBody(), agent, "traceparent", tp).status)
                .as("前置：代理对这个端点应当是 403").isEqualTo(403);

        JsonNode row = auditOfThisRequest("agent.AG002", traceIdOf(tp));
        assertThat(row.path("outcome").asText())
                .as("403 要记成 DENIED，而不是压根不记").isEqualTo("DENIED");
        assertThat(row.path("action").asText())
                .as("记的应当是他试图做的那件事").contains(AGENT_FORBIDDEN_WRITE);
    }

    @Test
    @DisplayName("成功的写操作记为 SUCCESS —— 查询要分得开这两件事")
    void a_successful_write_is_recorded_as_success() {
        String admin = login("ADMIN");
        String tp = newTraceparent();

        postWithHeaders("/api/ops/venues", venueBody(), admin, "traceparent", tp).okData();

        assertThat(auditOfThisRequest("admin.user", traceIdOf(tp)).path("outcome").asText())
                .isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("★ 审计行带的是调用方传来的 traceId —— 不是服务端自己现编的")
    void the_audit_row_carries_the_inbound_trace_id() {
        String admin = login("ADMIN");
        String tp = newTraceparent();

        postWithHeaders("/api/ops/venues", venueBody(), admin, "traceparent", tp).okData();

        // 断言「采纳了调用方的链路」而不只是「服务端生成了点什么」——
        // 后者用随机值也能通过，而跨进程排障要的正是同一个 id 在两边都出现。
        assertThat(auditOfThisRequest("admin.user", traceIdOf(tp)).path("traceId").asText())
                .isEqualTo(traceIdOf(tp));
    }

    private static Map<String, Object> venueBody() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "[审计结果测试] 场地方 " + System.nanoTime());
        m.put("industry", "购物中心");
        return m;
    }
}
