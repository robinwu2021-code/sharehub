package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import ai.neargo.sharehub.trade.service.RentOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站内信**从来没有人写过**。
 *
 * <p>`usr_message` 有表、有实体、有 mapper、有 service（分页 + 标已读 + 属主鉴权），
 * C 端有两个端点（`GET /mp/user/messages`、`POST .../read`）、有页面、有未读角标、
 * 点开即标已读 —— 整条读侧一应俱全，唯独 <b>全仓零处 insert</b>
 * （`CsSessionServiceImpl` 那两处写的是 `cs_message`，客服会话，另一张表）。
 * 于是收件箱永远是「暂无消息」，而这件事没有任何地方会报错：
 * 页面、接口、空态文案都工作得很好，看起来就像「这个用户没收到过消息」。
 *
 * <p>更具体一点：通知系统的四个渠道里，<b>{@code INAPP} 是唯一不需要渠道商的那个</b>
 * —— 写一行 `usr_message` 就完事 —— 却和短信一样被「渠道商对接留待接入层」的 TODO
 * 挡在外面。而 {@code NotifySendServiceImpl.send} 对任何渠道都落 {@code status=SENT}，
 * 所以运营端发送记录里那条 INAPP 显示「已发送」，用户的收件箱是空的。
 *
 * <p>本类钉的是<b>第一条真实生产路径</b>：订单结算 → 站内信。
 * 结算是最该通知的那件事（「账单出来了，收了你多少钱」），而它已经有事件
 * （`OrderSettledEvent`，finance 侧早就订阅着生成分润），不必新造。
 *
 * <p>顺带钉住 §1.5：<b>用户可见文本走三语三列</b>。单列存中文的话，
 * 阿语界面上就是一句中文 —— `FeeItem` 与公告都为同一个错改过一轮。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerInboxTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RentOrderService rents;

    final List<String> cabinets = new ArrayList<>();
    final List<String> users = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        for (String u : users) jdbc.update("DELETE FROM usr_message WHERE c_user_no=?", u);
    }

    @Test
    @DisplayName("借还一单 → 收件箱收到一条结算消息，三语齐、带订单号与实付")
    void settlementReachesTheInbox() {
        String cab = liveCabinet();
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        JsonNode login = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null).okData();
        String token = login.path("token").asText();
        String cUser = login.path("cUserNo").asText();
        users.add(cUser);

        assertThat(inbox(token)).as("下单前收件箱是空的 —— 本例的对照").isEmpty();

        String orderNo = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cab, "useFreeDeposit", true), token).okData().path("orderNo").asText();
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));

        List<JsonNode> msgs = inbox(token);
        assertThat(msgs).as("结算完必须有一条站内信 —— 此前 usr_message 全仓零写入").hasSize(1);
        JsonNode m = msgs.get(0);

        assertThat(m.path("type").asText()).isEqualTo("ORDER");
        assertThat(m.path("read").asBoolean()).as("新消息是未读的，否则未读角标永远不亮").isFalse();
        assertThat(m.path("body").asText()).as("正文要带订单号，否则用户对不上是哪一单").contains(orderNo);

        String amount = jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no=?",
                java.math.BigDecimal.class, orderNo).stripTrailingZeros().toPlainString();
        assertThat(m.path("body").asText().replace(".00", ""))
                .as("正文要带实付金额（%s）—— 「已结算」三个字答不出「收了我多少钱」", amount)
                .contains(amount.replace(".00", ""));

        // §1.5：用户可见文本三语三列。少一列的表现是阿语界面上出现一句中文，而不报错
        for (String f : List.of("title", "titleEn", "titleAr", "body", "bodyEn", "bodyAr")) {
            assertThat(m.path(f).asText("")).as("消息的 %s 不能为空（§1.5 三语三列）", f).isNotBlank();
        }
        assertThat(m.path("titleEn").asText()).as("英文标题不能就是中文标题")
                .isNotEqualTo(m.path("title").asText());
    }

    /** 标已读是幂等的：再调一次不改首次已读时间，否则「什么时候看到的」这件事会被后一次覆盖。 */
    @Test
    @DisplayName("标已读幂等：第二次不覆盖首次已读时间")
    void markReadIsIdempotent() {
        String cab = liveCabinet();
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        JsonNode login = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null).okData();
        String token = login.path("token").asText();
        users.add(login.path("cUserNo").asText());

        String orderNo = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cab, "useFreeDeposit", true), token).okData().path("orderNo").asText();
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));

        List<JsonNode> msgs = inbox(token);
        // 先断言有消息再取第 0 条 —— 否则写入方一旦失效，本例报的是 IndexOutOfBounds，
        // 而「红得不明不白」的负对照不算通过（见 negative-control 纪律）
        assertThat(msgs).as("结算应产生一条站内信").isNotEmpty();
        String no = msgs.get(0).path("messageNo").asText();
        String first = post("/mp/user/messages/" + no + "/read", Map.of(), token).okData().path("readAt").asText();
        String again = post("/mp/user/messages/" + no + "/read", Map.of(), token).okData().path("readAt").asText();

        assertThat(first).isNotBlank();
        assertThat(again).as("第二次标已读不该改 readAt").isEqualTo(first);
    }

    /** 别人的消息读不到也标不了 —— 属主鉴权（IDOR）。 */
    @Test
    @DisplayName("标他人消息被拒")
    void cannotMarkSomeoneElsesMessage() {
        String cab = liveCabinet();
        String phoneA = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otpA = post("/mp/auth/otp", Map.of("phone", phoneA), null).okData().path("devCode").asText();
        JsonNode a = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phoneA, "otp", otpA), null).okData();
        users.add(a.path("cUserNo").asText());
        String orderNo = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cab, "useFreeDeposit", true), a.path("token").asText())
                .okData().path("orderNo").asText();
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));
        List<JsonNode> aMsgs = inbox(a.path("token").asText());
        assertThat(aMsgs).as("A 的结算应产生一条站内信（同上，先断言再取下标）").isNotEmpty();
        String no = aMsgs.get(0).path("messageNo").asText();

        String phoneB = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otpB = post("/mp/auth/otp", Map.of("phone", phoneB), null).okData().path("devCode").asText();
        JsonNode b = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phoneB, "otp", otpB), null).okData();
        users.add(b.path("cUserNo").asText());

        var resp = post("/mp/user/messages/" + no + "/read", Map.of(), b.path("token").asText());
        assertThat(resp.code()).as("应为失败（响应：%s）", resp.msg()).isNotZero();
        assertThat(inbox(b.path("token").asText())).as("B 的收件箱里不该出现 A 的消息").isEmpty();
    }

    // —— 夹具 ——

    private List<JsonNode> inbox(String token) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode m : get("/mp/user/messages", token).okData().path("list")) out.add(m);
        return out;
    }

    private String liveCabinet() {
        String site = site();
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, 0.2, ?, ?, 'ACTIVE')",
                "CTMS" + rnd(), site, site, bizToday().minusDays(1).toString(), bizToday().plusYears(1).toString());
        String cab = "CBMS" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count,"
                        + " status, online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE', NOW(3))",
                cab, cab, site);
        return cab;
    }

    private String site() {
        String site = "STMS" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "站内信测试站点 " + site);
        return site;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
