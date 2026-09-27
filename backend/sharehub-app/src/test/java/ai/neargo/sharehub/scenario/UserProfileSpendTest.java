package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户 360 档案的「累计消费」在超过 200 单之后**不再增长**。
 *
 * <p>运营端把这两个数**并排渲染**：{@code {count} 单 · {amount}}。
 * 而后端是这么算的：
 * <pre>
 *   PageResult&lt;RentOrder&gt; orderPage = rentOrders.pageByOwner(cUserNo, 1, 200);
 *   amount = orderPage.getList().stream()...  ← 只累加**这一页 200 行**
 *   new OrderStats(orderPage.getTotal(), amount, ...)  ← 单数取的是**全量 total**
 * </pre>
 * 于是一个下过 250 单的用户，档案上是「250 单 · （前 200 单的钱）」——
 * <b>两个数并排，一个对一个不对</b>，而接口一路 200、页面照常渲染。
 * 少的那部分钱没有任何地方会提示，只有拿它去核对的人才会发现。
 *
 * <p>与 {@code SiteLookupBeyondFirstPageTest} 是同一个形状（「拿分页当查询」），
 * 区别是这一处算的是钱。修法也一样：<b>该聚合就聚合，不是把上限调大</b> ——
 * 调大只是把同一个洞推远一点。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserProfileSpendTest extends ApiTestSupport {

    /** 顶到 200 之外就够；每单 1.00，于是期望值就是单数本身，读起来一目了然。 */
    private static final int ORDERS = 205;

    @Autowired
    JdbcTemplate jdbc;

    String cUser;

    @AfterAll
    void cleanup() {
        if (cUser != null) {
            jdbc.update("DELETE FROM ord_order WHERE c_user_no=?", cUser);
            jdbc.update("DELETE FROM usr_user WHERE c_user_no=?", cUser);
        }
    }

    @Test
    @DisplayName("205 单 × 1.00：累计消费要是 205.00，不是前 200 单的 200.00")
    void spendCoversEveryOrderNotJustTheFirstPage() {
        cUser = "CU-SPEND-" + rnd();
        jdbc.update("INSERT INTO usr_user (c_user_no, tenant_id, nickname, status) VALUES (?, 'MAIN', ?, 'NORMAL')",
                cUser, "累计消费测试 " + cUser);

        LocalDateTime startUtc = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
        for (int i = 0; i < ORDERS; i++) {
            // cabinet_no 是 NOT NULL 无默认值；本例只关心金额聚合，填一个占位机柜号即可
            jdbc.update("INSERT INTO ord_order (order_no, tenant_id, c_user_no, cabinet_no, status, device_type, currency,"
                            + " amount, fee_amount, started_at, rent_start_at)"
                            + " VALUES (?, 'MAIN', ?, 'CB-SPEND-NA', 'SETTLED', 'POWERBANK', 'AED', 1.00, 1.00, ?, ?)",
                    "ORDSP" + rnd(), cUser, LocalDateTime.now().minusHours(2), startUtc.toString());
        }

        JsonNode stats = get("/api/user/users/" + cUser + "/profile", login("admin")).okData().path("orderStats");

        assertThat(stats.path("count").asLong()).as("单数本来就取的是全量 total，这一位一直是对的")
                .isEqualTo(ORDERS);
        assertThat(new BigDecimal(stats.path("amount").asText("0")))
                .as("""
                        累计消费必须覆盖全部 %d 单。只累加第一页的话这里是 200.00 ——
                        而旁边的「单数」写着 %d，两个数并排、一个对一个不对，且不报错。""",
                        ORDERS, ORDERS)
                .isEqualByComparingTo(new BigDecimal(ORDERS));
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
