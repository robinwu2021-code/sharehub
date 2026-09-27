package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 找柜列表与机柜详情对「这儿能不能借」**必须给同一个答案**。
 *
 * <h3>2026-09-27 H5 对接实测</h3>
 * CAB1013 是 {@code ONLINE} 但 {@code status=FAULT}：首页列表显示「可借 1」
 * （它只看 {@code availableCount}），而 {@code /availability} 返 {@code borrowable:false}
 * （它判了在线与故障）。<b>用户走到店里才发现借不了。</b>
 *
 * <p>两处各写一遍判断是这类分叉的通用形状：**两边单看都对，合起来对不上，
 * 而没有任何东西会报错**。现在两边都调 {@code StoreCardAssembler#borrowable}。
 *
 * <p>本类把一台机柜临时改成故障再复原 —— test_sharehub 是共享库，
 * 不复原会让别的用例莫名其妙地少一台可借机柜。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BorrowableAgreesTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String cabinetNo;
    /** 被置为故障的柜 → 原状态（找柜列表按站点出卡，要让整店都坏才能看到站点卡变 0）。 */
    private final Map<String, String> originalStatus = new java.util.LinkedHashMap<>();

    @AfterEach
    void restore() {
        originalStatus.forEach((cab, st) ->
                jdbc.update("update dev_cabinet set status=? where cabinet_no=?", st, cab));
        originalStatus.clear();
    }

    @Test
    @DisplayName("机柜故障：列表不再报可借台数，详情也说借不了 —— 两边一致")
    void listAndDetailAgreeWhenCabinetIsFaulty() {
        cabinetNo = jdbc.queryForObject(
                "select cabinet_no from dev_cabinet where deleted=0 and available_count>0 "
                        + "and online_status='ONLINE' and status<>'FAULT' limit 1", String.class);
        assertThat(cabinetNo).isNotBlank();
        String siteNo = jdbc.queryForObject("select site_no from dev_cabinet where cabinet_no=?", String.class, cabinetNo);

        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        // 正常态：两边都说能借
        assertThat(cardOf(token, siteNo).path("availableBorrow").asInt()).isPositive();
        assertThat(detailBorrowable(token, cabinetNo)).isTrue();

        // 整店置为故障（一家店一张卡：只坏一台时站点卡照样有别的柜可借）
        jdbc.queryForList("select cabinet_no, status from dev_cabinet where deleted=0 and site_no=?", siteNo)
                .forEach(r -> originalStatus.put((String) r.get("cabinet_no"), (String) r.get("status")));
        jdbc.update("update dev_cabinet set status='FAULT' where deleted=0 and site_no=?", siteNo);

        JsonNode card = cardOf(token, siteNo);
        assertThat(detailBorrowable(token, cabinetNo))
                .as("详情本来就判了故障").isFalse();
        assertThat(card.path("availableBorrow").asInt())
                .as("""
                        列表此前只看 availableCount，于是仍然报「可借 N」——
                        用户据此走到店里，到了才发现借不了。两边必须同一个判断。""")
                .isZero();
        assertThat(card.path("status").asText())
                .as("""
                        出 PAUSED 而不是靠 availableBorrow=0 让端上渲染成「满仓」——
                        满仓是换个柜子还就行，停用是这家店白来，对用户是两件事。""")
                .isEqualTo("PAUSED");
    }

    /**
     * 站点停用时，列表也不能报「可借 N」。
     *
     * <p>2026-09-27 H5 对接实测：停用站点 Dubai Mall L1 在首页列着「可借 7」，
     * 而真去借会撞 409「该站点暂停服务」—— 因为「能不能借」当时在**三个地方各算一遍**：
     * 列表只看台数、详情看「在线且非故障」、借出走 RentabilityGuard（还要判站点可租、
     * DEPLOYED、整柜停借）。三处各自都说得通，合起来是用户白跑一趟。
     *
     * <p>现在展示层直接问那个权威守卫，判据只有一份。
     */
    @Test
    @DisplayName("站点停用：列表不报可借，且与真去借的结果一致")
    void pausedSiteIsNotOfferedInTheList() {
        String cab = jdbc.queryForObject(
                "select c.cabinet_no from dev_cabinet c join loc_site s on s.site_no = c.site_no "
                        + "where s.deleted=0 and c.deleted=0 and c.available_count>0 "
                        + "and c.online_status='ONLINE' and c.status='DEPLOYED' and s.status='ACTIVE' limit 1",
                String.class);
        assertThat(cab).isNotBlank();
        String siteNo = jdbc.queryForObject(
                "select site_no from dev_cabinet where cabinet_no=?", String.class, cab);
        String siteStatus = jdbc.queryForObject(
                "select status from loc_site where site_no=?", String.class, siteNo);

        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        try {
            jdbc.update("update loc_site set status='PAUSED' where site_no=?", siteNo);

            assertThat(cardOf(token, siteNo).path("availableBorrow").asInt())
                    .as("停用站点在列表里报「可借 N」，用户点进去才撞 409「该站点暂停服务」")
                    .isZero();
            assertThat(detailBorrowable(token, cab)).as("详情也要说借不了").isFalse();
            assertThat(post("/mp/trade/orders/rent",
                    Map.of("cabinetNo", cab, "useFreeDeposit", false), token).status)
                    .as("真去借本来就会被拒 —— 三处必须是同一个答案").isEqualTo(409);
        } finally {
            jdbc.update("update loc_site set status=? where site_no=?", siteStatus, siteNo);
        }
    }

    /** 找柜列表一家店一张卡（StoreCardAssembler#siteCard），按站点号取。 */
    private JsonNode cardOf(String token, String siteNo) {
        for (JsonNode c : get("/mp/nearby/cabinets", token).okData()) {
            if (siteNo.equals(c.path("siteNo").asText())) return c;
        }
        throw new AssertionError("找柜列表里没有这家店：" + siteNo);
    }

    private boolean detailBorrowable(String token, String cabinetNo) {
        return get("/mp/nearby/cabinets/" + cabinetNo + "/availability", token)
                .okData().path("borrowable").asBoolean();
    }
}
