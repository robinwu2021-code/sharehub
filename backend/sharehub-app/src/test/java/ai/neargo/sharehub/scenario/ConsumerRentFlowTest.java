package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：模拟真实 C 端消费者走「扫码借出→我的订单→归还结算」闭环，并验证**属主鉴权**（防 IDOR）与订单状态机。
 * 复用 neargo：业务键 {@code ORD…/PB…} 由 IdGenerator 生成；订单落 ord_rent（物理主键自增 Long）。
 */
class ConsumerRentFlowTest extends ApiTestSupport {

    private static final String PHONE_A = "+971500009001";
    private static final String PHONE_B = "+971500009002";
    private static final String CABINET = "CAB1005";

    /** C 端 phone_otp 登录（dev OTP 由 /mp/auth/otp 直接返回）。 */
    private String consumerToken(String phone) {
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    @Test
    void borrow_return_loop_with_owner_guard() {
        String tokenA = consumerToken(PHONE_A);

        // 1) 扫码借出 → 业务键由 IdGenerator 生成，订单置 IN_USE
        JsonNode rent = post("/mp/trade/orders/rent", Map.of("cabinetNo", CABINET), tokenA).okData();
        String orderNo = rent.path("orderNo").asText();
        assertThat(orderNo).startsWith("ORD");
        assertThat(rent.path("powerbankNo").asText()).startsWith("PB");
        assertThat(rent.path("commandId").asText()).startsWith("CMD");

        // 2) 我的订单（属主过滤）含该单，且详情为 IN_USE
        JsonNode mine = get("/mp/trade/orders?page=1&size=50", tokenA).okData();
        boolean found = false;
        for (JsonNode o : mine.path("list")) if (orderNo.equals(o.path("orderNo").asText())) found = true;
        assertThat(found).as("我的订单应含新单 %s", orderNo).isTrue();
        assertThat(get("/mp/trade/orders/" + orderNo, tokenA).okData().path("status").asText()).isEqualTo("IN_USE");

        // 3) 属主鉴权：另一消费者查看 A 的订单 → 403（防 IDOR）
        String tokenB = consumerToken(PHONE_B);
        assertThat(get("/mp/trade/orders/" + orderNo, tokenB).status).isEqualTo(403);

        // 4) 归还结单（内部端点，设备事件驱动；用 staff token 模拟网关）→ 状态机 IN_USE→RETURNED→SETTLED
        String admin = login("ADMIN");
        assertThat(post("/internal/trade/orders/" + orderNo + "/return",
                Map.of("returnCabinetNo", "CAB1006"), admin).okData().path("ok").asBoolean()).isTrue();

        JsonNode settled = get("/mp/trade/orders/" + orderNo, tokenA).okData();
        assertThat(settled.path("status").asText()).isEqualTo("SETTLED");
        assertThat(settled.path("returnCabinetNo").asText()).isEqualTo("CAB1006");
        assertThat(settled.path("feeAmount").asDouble()).isGreaterThanOrEqualTo(0.0);

        // 5) 重复归还 = 非法迁移（SETTLED --RETURN--> ?）→ 400
        assertThat(post("/internal/trade/orders/" + orderNo + "/return",
                Map.of("returnCabinetNo", "CAB1006"), admin).status).isEqualTo(400);
    }
}
