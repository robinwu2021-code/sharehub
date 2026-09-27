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
    private String originalStatus;

    @AfterEach
    void restore() {
        if (cabinetNo != null && originalStatus != null) {
            jdbc.update("update dev_cabinet set status=? where cabinet_no=?", originalStatus, cabinetNo);
        }
    }

    @Test
    @DisplayName("机柜故障：列表不再报可借台数，详情也说借不了 —— 两边一致")
    void listAndDetailAgreeWhenCabinetIsFaulty() {
        cabinetNo = jdbc.queryForObject(
                "select cabinet_no from dev_cabinet where deleted=0 and available_count>0 "
                        + "and online_status='ONLINE' and status<>'FAULT' limit 1", String.class);
        assertThat(cabinetNo).isNotBlank();
        originalStatus = jdbc.queryForObject(
                "select status from dev_cabinet where cabinet_no=?", String.class, cabinetNo);

        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        // 正常态：两边都说能借
        assertThat(cardOf(token, cabinetNo).path("availableBorrow").asInt()).isPositive();
        assertThat(detailBorrowable(token, cabinetNo)).isTrue();

        // 置为故障
        jdbc.update("update dev_cabinet set status='FAULT' where cabinet_no=?", cabinetNo);

        JsonNode card = cardOf(token, cabinetNo);
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

    private JsonNode cardOf(String token, String cabinetNo) {
        for (JsonNode c : get("/mp/nearby/cabinets", token).okData()) {
            if (cabinetNo.equals(c.path("cabinetNo").asText())) return c;
        }
        throw new AssertionError("找柜列表里没有这台机柜：" + cabinetNo);
    }

    private boolean detailBorrowable(String token, String cabinetNo) {
        return get("/mp/nearby/cabinets/" + cabinetNo + "/availability", token)
                .okData().path("borrowable").asBoolean();
    }
}
