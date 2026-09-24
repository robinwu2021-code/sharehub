package ai.neargo.sharehub.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /internal/**} 的写操作也要留痕（T3-3）。
 *
 * <h2>此前整个被排除，而那个理由只对一半成立</h2>
 * 拦截器原来只审 {@code /api/**}，注释写的是「{@code /internal/**} 不审 ——
 * 服务间调用没有操作人，记下来只会是一串 system」。
 *
 * <p>但 {@code /internal/} 这个前缀今天混着两类东西（见 {@code InternalTokenFilter}）：
 * <ul>
 *   <li>{@code /internal/events/**} 确实是服务之间的；</li>
 *   <li>{@code /internal/trade/**} · {@code /internal/user/**} 是<b>历史遗留的业务端点，
 *       ops-web 今天就在用员工令牌调</b> —— 结算生成、信用拉黑都在里面。</li>
 * </ul>
 * 第二类有明确的操作人，却因为路径前缀而<b>一条审计都没有</b>。
 * 「谁触发了这个账期的结算」在库里查不到答案。
 *
 * <h2>没有人的那一半记什么</h2>
 * 记 {@code SYSTEM:<服务名>}。服务名是调用方自报的 —— 内部凭证是一把共享密钥，
 * 持有它的任何一方都能声称自己是任何服务，所以那一段是<b>排障线索而非身份证明</b>。
 * 可信的部分是 {@code SYSTEM}：确实有人拿着内部密钥做了这件事。
 */
class InternalCallAuditTest extends AuditTestSupport {

    @Test
    @DisplayName("★★ 员工令牌调的 /internal 写操作，要记成那个人——结算是谁触发的必须查得到")
    void an_internal_write_made_with_a_staff_token_records_the_real_person() {
        String admin = login("ADMIN");
        String tp = newTraceparent();

        // 结算生成：幂等（重跑已出账的账期返回空列表），所以用例重复跑不会脏数据
        postWithHeaders("/internal/trade/settlements/generate", Map.of("period", "2000-01"),
                admin, "traceparent", tp).okData();

        JsonNode row = auditOfThisRequest("admin.user", traceIdOf(tp));
        assertThat(row.path("actor").asText())
                .as("记的应当是触发的那个人，而不是 SYSTEM").isEqualTo("admin.user");
        assertThat(row.path("action").asText()).contains("/internal/trade/settlements/generate");
        assertThat(row.path("outcome").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("没有登录人的内部调用记成 SYSTEM:<服务名>")
    void an_internal_write_without_a_person_is_recorded_as_system() {
        String tp = newTraceparent();

        // 调价 tick：允许匿名（只接受回环调用），代表「定时器/别的服务触发」那一类
        postWithHeaders("/internal/trade/price-adjustments/tick", Map.of(), null,
                "traceparent", tp, "X-Internal-Caller", "sharehub-scheduler").okData();

        JsonNode row = auditOfThisRequest("SYSTEM", traceIdOf(tp));
        assertThat(row.path("actor").asText())
                .as("SYSTEM 是可信的那半截（确实是内部触发），冒号后面是调用方自报的")
                .isEqualTo("SYSTEM:sharehub-scheduler");
        assertThat(row.path("clientCode").isNull() || row.path("clientCode").asText().isEmpty())
                .as("没有人就没有「从哪个端」——留空比编一个 OPS 诚实")
                .isTrue();
    }
}
