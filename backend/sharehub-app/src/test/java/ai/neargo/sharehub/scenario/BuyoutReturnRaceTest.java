package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.RentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2：买断与归还同时到达，同一单只能请款一次。
 *
 * <p>此前输掉乐观锁竞争的一方 {@code updateById} 返回 0 行却不检查，照常往下请款 ——
 * 一单两笔 pay_order，而 pay_order 没有唯一约束兜底。这条用例把两边同时打出去若干轮，
 * 只看结果：每单最多一笔请款、订单终态是两者之一。
 */
class BuyoutReturnRaceTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private String consumer() {
        String phone = "+97150" + (1_000_000 + (System.nanoTime() % 8_000_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    @Test
    @DisplayName("★★ 买断与归还并发：每单最多一笔请款")
    void buyout_and_return_race_charges_once() throws Exception {
        String admin = login("ADMIN");
        List<String> orders = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            RentFixture.rentable(jdbc);
            String token = consumer();
            orders.add(post("/mp/trade/orders/rent", Map.of("cabinetNo", RentFixture.CABINET, "useFreeDeposit", true), token)
                    .okData().path("orderNo").asText());
            tokens.add(token);
        }
        // 出宝回执由模拟弹仓自动补齐；等订单都到 IN_USE
        for (String o : orders) {
            for (int t = 0; t < 50 && !"IN_USE".equals(jdbc.queryForObject(
                    "SELECT status FROM ord_order WHERE order_no = ?", String.class, o)); t++) Thread.sleep(100);
        }

        // 借了 3 小时：两条路都会请款（免费时长内的归还不请款，那样测不出重复扣款）
        for (String o : orders) {
            jdbc.update("UPDATE ord_order SET rent_start_at = rent_start_at - INTERVAL 3 HOUR WHERE order_no = ?", o);
        }
                ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < orders.size(); i++) {
            String o = orders.get(i), token = tokens.get(i);
            CountDownLatch go = new CountDownLatch(1);
            pool.submit(() -> { go.await(); return post("/internal/trade/orders/" + o + "/return", Map.of("returnCabinetNo", "CAB1006"), admin); });
            pool.submit(() -> { go.await(); return post("/mp/trade/orders/" + o + "/buyout", Map.of(), token); });
            go.countDown();
            Thread.sleep(400);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (String o : orders) {
            Integer charges = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_order WHERE order_no = ? AND type IN ('RENT','BUYOUT')", Integer.class, o);
            assertThat(charges).as("订单 %s 只能请款一次（借了 3 小时，必然有一次）", o).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no = ?", String.class, o))
                    .isEqualTo("SETTLED");
        }
    }
}
