package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进行中订单要能答出「现在已经多少钱了」，而且那个数**必须与结算口径一致**。
 *
 * <h3>为什么需要这条用例</h3>
 * {@code ord_order.duration_min} / {@code fee_amount} <b>只在归还那一刻写入</b>，
 * 进行中的单这两列恒为 0。C 端照着渲染，「实时计时计费」在界面上就是一个不动的 0 ——
 * 不报错、不空白、没有任何异常，用户唯一的判断方式是退出重进。
 * 这种缺陷没有任何现成的断言会红，所以得专门钉一条。
 *
 * <h3>钉的是两件事</h3>
 * <ol>
 *   <li><b>试算存在</b>：进行中详情带 estimatedFee / estimatedDurationMin，
 *       而 durationMin / feeAmount 仍然是 0（后者正是这个功能存在的理由，一并钉住）；</li>
 *   <li><b>口径一致</b>：把这一单立刻归还，结算出来的 feeAmount 必须等于刚才那个试算值。
 *       不等于的后果不是报错，是用户盯了一路的数与最终账单不一样 ——
 *       而那正是他会去投诉的那一种「对不上」。</li>
 * </ol>
 *
 * <p>起租时间由本用例**改库写死成 90 分钟前**：靠等待跑不出可断言的时长，
 * 而「刚下单那一刻」多数计价方案给的是 0，0 == 0 的断言什么也守不住。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerLiveFeeTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("进行中订单：带实时试算，且试算 = 立即归还后的实付")
    void inUseOrderCarriesEstimateThatMatchesSettlement() {
        String cabinetNo = ai.neargo.sharehub.support.RentFixture.rentable(jdbc);
        assertThat(cabinetNo).as("测试库里得有一台在线、非故障、有宝的柜机").isNotBlank();

        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        String orderNo = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cabinetNo, "useFreeDeposit", true), token)
                .okData().path("orderNo").asText();

        // 起租推到 90 分钟前。**必须写 UTC** —— rent_start_at 存的是
        // LocalDateTime.now(ZoneOffset.UTC)，这台机器/这个库的时区不参与
        //（见「三个时区」：同一行数据里不同列的时区口径本来就不一样）。
        jdbc.update("update ord_order set rent_start_at=? where order_no=?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(90), orderNo);

        JsonNode inUse = get("/mp/trade/orders/" + orderNo, token).okData();
        assertThat(inUse.path("status").asText()).isEqualTo("IN_USE");

        assertThat(inUse.path("durationMin").asInt())
                .as("进行中的 durationMin 本来就是 0（只在归还那一刻写入）—— 钉住它，"
                        + "因为这正是端上必须改用 estimatedDurationMin 的理由").isZero();
        assertThat(inUse.path("feeAmount").asDouble())
                .as("同上：进行中的 feeAmount 恒为 0").isZero();

        assertThat(inUse.path("estimatedDurationMin").asInt())
                .as("试算时长要反映真实已用时长（起租已推到 90 分钟前）").isGreaterThanOrEqualTo(89);
        BigDecimal estimated = new BigDecimal(inUse.path("estimatedFee").asText("0"));
        assertThat(estimated)
                .as("借了 90 分钟还试算成 0，等于这个字段没接上").isGreaterThan(BigDecimal.ZERO);

        // 列表不带试算：逐单跑计价链挂在列表上就是 N+1，而订单列表是最长的那个列表
        JsonNode listed = null;
        for (JsonNode row : get("/mp/trade/orders", token).okData().path("list")) {
            if (orderNo.equals(row.path("orderNo").asText())) listed = row;
        }
        assertThat(listed).as("刚借的这一单要能在我的订单里找到").isNotNull();
        assertThat(listed.path("estimatedFee").isNull())
                .as("列表投影**不带**试算（N+1）；进行中的行由端上按 rentStartAt 显示已用时长").isTrue();

        // —— 口径一致 ——
        String staff = login("admin");
        post("/internal/trade/orders/" + orderNo + "/return",
                Map.of("returnCabinetNo", cabinetNo), staff).okData();

        JsonNode settled = get("/mp/trade/orders/" + orderNo, token).okData();
        assertThat(new BigDecimal(settled.path("feeAmount").asText("0")))
                .as("""
                        试算与结算必须同一口径（同一份下单快照 + 倍率 + 券 + 免单 + 封顶）。
                        对不上不会报错，只会让用户盯了一路的数与最终账单不一样。
                        试算=%s 实付=%s""", estimated, settled.path("feeAmount"))
                .isEqualByComparingTo(estimated);
        assertThat(settled.path("estimatedFee").isNull())
                .as("已结算的单没有「现在多少钱」这回事，试算要让位给实打实的 feeAmount").isTrue();
    }
}
