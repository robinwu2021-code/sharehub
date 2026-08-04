package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款申请的幂等键防线（资金动作，钱退两次是不可回滚的事故）。
 *
 * <p>钉三件事：
 * <ol>
 *   <li><b>缺键 / 空白键 → 400</b>。原实现缺键时服务端 {@code randomUUID()} 自补 ——
 *       那等于把幂等彻底关掉：同一次点击重试三下就是三个不同的键、三条退款单、
 *       审批队列里三笔同额待批。服务端派生的键在语义上不可能重复，
 *       所以「自补」永远只是让防线看起来存在。</li>
 *   <li><b>同键重复提交 → 返回同一张单，不新建、也不报错</b>。
 *       「重复提交」的正确语义是「你要的那笔已经在了」。</li>
 *   <li><b>不同键 → 两张不同的单</b>。否则会把两次真实的退款诉求误并成一次，
 *       是反方向的错（该退的没退）。</li>
 * </ol>
 *
 * <p>用 CS 登录：{@code order:refund:apply} 只给客服（[功能权限清单 §5]）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundIdempotencyTest extends ApiTestSupport {

    private String csToken;

    @BeforeAll
    void signIn() {
        csToken = login("CS", "cs.user", null);
    }

    /** 缺失 / 空串 / 纯空白的幂等键一律 400，且**一条退款单都不许落**。 */
    @Test
    void missing_or_blank_idempotency_key_is_400() {
        for (String key : new String[]{null, "", "   "}) {
            Resp r = post("/api/trade/refunds", body(key), csToken);
            assertThat(r.status).as("idempotencyKey=%s 应被拒", key == null ? "<缺失>" : "\"" + key + "\"")
                    .isEqualTo(400);
            assertThat(r.code()).isEqualTo(400);
            assertThat(r.msg()).contains("idempotencyKey");
        }
    }

    /** 同一个幂等键提交三次 → 同一个 refundNo，不产生第二张单（重复退款的正解）。 */
    @Test
    void same_key_returns_the_original_refund() {
        String key = "IT-REFUND-" + UUID.randomUUID();

        JsonNode first = post("/api/trade/refunds", body(key), csToken).okData();
        String refundNo = first.path("refundNo").asText();
        assertThat(refundNo).isNotBlank();
        assertThat(first.path("idempotencyKey").asText()).isEqualTo(key);
        assertThat(first.path("status").asText()).isEqualTo("PENDING");

        for (int i = 0; i < 2; i++) {
            JsonNode again = post("/api/trade/refunds", body(key), csToken).okData();
            assertThat(again.path("refundNo").asText())
                    .as("同键重复提交必须返回首单，而不是新开一张").isEqualTo(refundNo);
        }
    }

    /** 不同幂等键 → 两张不同的退款单（幂等不能过度收敛，该退的两笔都要在）。 */
    @Test
    void different_keys_yield_different_refunds() {
        String a = post("/api/trade/refunds", body("IT-REFUND-" + UUID.randomUUID()), csToken)
                .okData().path("refundNo").asText();
        String b = post("/api/trade/refunds", body("IT-REFUND-" + UUID.randomUUID()), csToken)
                .okData().path("refundNo").asText();
        assertThat(a).isNotBlank().isNotEqualTo(b);
    }

    /** 退款申请体；{@code key} 为 null 时**不放该字段**（模拟调用方压根没传，而不是传了 null）。 */
    private static Map<String, Object> body(String key) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderNo", "IT-ORD-IDEM");
        m.put("userNo", "IT-USER-IDEM");
        m.put("amount", 12.5);
        m.put("currency", "AED");
        m.put("reason", "[幂等测试] 借出后立即故障");
        if (key != null) m.put("idempotencyKey", key);
        return m;
    }
}
