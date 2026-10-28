package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 商家资料（V175）：商家号取自会话，只看自己；只能改联系人；运营会话不能用这组端点。 */
class MerchantProfileTest extends ApiTestSupport {

    private String merchant() {
        Map<String, Object> b = new HashMap<>();
        b.put("email", "p" + System.nanoTime() + "@example.com");
        b.put("operatorName", "资料测试 " + System.nanoTime());
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/applies", b, login("BD")).okData().path("applyNo").asText();
        String admin = login("ADMIN");
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        return post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin).okData().path("operatorNo").asText();
    }

    @Test
    @DisplayName("★★ 商家看自己的资料、改联系人；名称不随请求改")
    void merchant_reads_own_profile_and_edits_contact() {
        String a = merchant();
        String token = login("AGENT", "prof-" + a, a);
        JsonNode p = get("/api/agent/me/profile", token).okData();
        assertThat(p.path("agentNo").asText()).isEqualTo(a);
        String name = p.path("name").asText();

        JsonNode r = post("/api/agent/me/contact", Map.of("contactName", "Rania", "phone", "050 123 4567",
                "email", " Rania@Shop.AE ", "name", "被改的名字"), token).okData();
        assertThat(r.path("contactName").asText()).isEqualTo("Rania");
        assertThat(r.path("phone").asText()).isEqualTo("+971501234567");
        assertThat(r.path("email").asText()).isEqualTo("rania@shop.ae");
        assertThat(r.path("name").asText()).as("名称只读").isEqualTo(name);
    }

    @Test
    @DisplayName("★ 联系人姓名必填；运营会话没有商家号 → 不能用")
    void validation_and_staff_cannot_use() {
        String a = merchant();
        assertThat(post("/api/agent/me/contact", Map.of("contactName", " "), login("AGENT", "prof2-" + a, a)).status).isEqualTo(400);
        assertThat(get("/api/agent/me/profile", login("ADMIN")).status).isEqualTo(403);
    }
}
