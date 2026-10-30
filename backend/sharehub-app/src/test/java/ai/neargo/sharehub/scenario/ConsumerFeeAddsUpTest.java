package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单详情的费用明细**逐项相加必须等于实付**。
 *
 * <h3>为什么单钉这一条</h3>
 * {@code ConsumerOrderAssembler.fees} 的注释把这条不变量写得很清楚
 * （「否则这张表看着就不像账」），但**没有任何东西守着它** ——
 * 2026-09-27 H5 对接实测所见：押金被当成一条费用返回，于是屏幕上是
 * <pre>
 *   租借费   3.00
 *   押金    50.00
 *   ────────────
 *   合计     3.00
 * </pre>
 * 端上合计取的是 {@code feeAmount}（实付），明细里却多一笔 50 ——
 * <b>账在屏幕上加不起来</b>，而接口一路 200。
 *
 * <p>押金不是费用：它可退、也不构成本单应付，而且本来就有自己的出参字段
 * {@code depositAmount}。放进 fees 等于同一笔钱在契约里出现两次。
 *
 * <p>这条用例走**真实借还闭环**（借出 → 归还结算 → 读详情），不是构造 VO ——
 * 它要守的是「端上看到的那张账单」，而不是某个方法的返回值。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerFeeAddsUpTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("借还一单：明细逐项相加 = 合计，且押金不在明细里")
    void feeLinesSumToPayable() {
        String cabinetNo = ai.neargo.sharehub.support.RentFixture.rentable(jdbc);
        assertThat(cabinetNo).as("测试库里得有一台在线、非故障、有宝的柜机").isNotBlank();

        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        String orderNo = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cabinetNo, "useFreeDeposit", false), token)
                .okData().path("orderNo").asText();

        String staff = login("admin");
        post("/internal/trade/orders/" + orderNo + "/return",
                Map.of("returnCabinetNo", cabinetNo), staff).okData();

        JsonNode d = get("/mp/trade/orders/" + orderNo, token).okData();

        BigDecimal payable = new BigDecimal(d.path("feeAmount").asText("0"));
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode f : d.path("fees")) {
            assertThat(f.path("type").asText())
                    .as("押金不是费用：它可退、不构成应付，且已有独立字段 depositAmount")
                    .isNotEqualTo("DEPOSIT");
            sum = sum.add(new BigDecimal(f.path("amount").asText("0")));
        }
        assertThat(sum.compareTo(payable))
                .as("""
                        明细逐项相加必须等于合计。不等于的话，用户看到的那张账单
                        自己加不起来 —— 而接口一路 200，没有任何地方会报错。
                        明细=%s 合计=%s""", d.path("fees"), payable)
                .isZero();

        assertThat(d.path("depositAmount").asDouble())
                .as("押金仍然要出得来，只是在合计之外单独一笔").isGreaterThan(0);
    }
}
