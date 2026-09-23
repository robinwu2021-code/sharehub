package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **拓展归因：商机能记到伙伴头上**（ADR-027 §五 / TDD-A2 第 3 批）。
 *
 * <p>「这个场地是某个代理商谈下来的」是开站常态，而拓展佣金的依据就是这件事。
 * 在 V55 之前 {@code loc_lead.owner} 只接受 employee_no —— 记不下来的话，
 * 佣金只能靠人记：谁谈的、谈成没有，全在聊天记录里。
 *
 * <p>本测试盯的是**不会报错、只会安静算错钱**的那几件事：归属类型脏值进库 ·
 * 签下了却没落成责任行 · 没签就先把佣金依据写出去 · 副产物失败把主动作一起回滚。
 */
class LeadAttributionTest extends ApiTestSupport {

    /** ST308 在别处没人用，避免用例之间互相改对方的责任行。 */
    private static final String SITE = "ST308";
    private static final String PARTNER = "AG004";

    private Map<String, Object> lead(String stage, String ownerType, String owner, String siteNo) {
        Map<String, Object> m = new HashMap<>();
        m.put("venueName", "归因测试场地 " + System.nanoTime());
        m.put("stage", stage);
        m.put("ownerType", ownerType);
        m.put("owner", owner);
        if (siteNo != null) m.put("siteNo", siteNo);
        return m;
    }

    private JsonNode responsibilities(String admin) {
        return get("/api/ops/sites/" + SITE + "/agents", admin).okData();
    }

    private boolean hasDevelop(String admin, String agentNo) {
        for (JsonNode r : responsibilities(admin)) {
            if (agentNo.equals(r.path("agentNo").asText()) && "DEVELOP".equals(r.path("role").asText())) {
                return true;
            }
        }
        return false;
    }

    private void cleanup(String admin, String agentNo) {
        for (JsonNode r : responsibilities(admin)) {
            if (agentNo.equals(r.path("agentNo").asText()) && "DEVELOP".equals(r.path("role").asText())) {
                post("/api/ops/sites/" + SITE + "/agents/" + r.path("id").asLong() + "/remove",
                        Map.of(), admin).okData();
            }
        }
    }

    @Test
    void signed_lead_owned_by_partner_becomes_a_develop_responsibility() {
        String admin = login("ADMIN");
        try {
            post("/api/ops/leads", lead("SIGNED", "AGENT", PARTNER, SITE), admin).okData();
            assertThat(hasDevelop(admin, PARTNER))
                    .as("签下且归属伙伴 = 拓展佣金的依据，必须落成责任行，不能只留在聊天记录里")
                    .isTrue();
        } finally {
            cleanup(admin, PARTNER);
        }
    }

    @Test
    void unsigned_lead_writes_nothing() {
        // 还在谈的商机不该先把佣金依据写出去 —— 没谈成也分钱是**反向的**静默错账。
        String admin = login("ADMIN");
        try {
            post("/api/ops/leads", lead("NEGOTIATING", "AGENT", PARTNER, SITE), admin).okData();
            assertThat(hasDevelop(admin, PARTNER)).as("没签就不该有拓展责任行").isFalse();
        } finally {
            cleanup(admin, PARTNER);
        }
    }

    @Test
    void staff_owned_lead_writes_nothing() {
        // 自己人谈下来的不产生对外佣金。归属类型判错的后果是白付一笔。
        String admin = login("ADMIN");
        try {
            post("/api/ops/leads", lead("SIGNED", "STAFF", PARTNER, SITE), admin).okData();
            assertThat(hasDevelop(admin, PARTNER)).as("员工归属不产生伙伴拓展责任").isFalse();
        } finally {
            cleanup(admin, PARTNER);
        }
    }

    @Test
    void sign_first_then_site_still_writes_it() {
        // 先签下、后建站是常态：签的那一刻没有站点可挂。
        // 条件若写成「阶段翻成 SIGNED 的那一刻」，这一类商机的佣金依据**永远不会被写出来**。
        String admin = login("ADMIN");
        try {
            String no = post("/api/ops/leads", lead("SIGNED", "AGENT", PARTNER, null), admin)
                    .okData().path("leadNo").asText();
            assertThat(hasDevelop(admin, PARTNER)).as("前提：还没指定站点时不写").isFalse();

            post("/api/ops/leads/" + no, lead("SIGNED", "AGENT", PARTNER, SITE), admin).okData();
            assertThat(hasDevelop(admin, PARTNER)).as("站点补填上去的那一次保存要补写").isTrue();
        } finally {
            cleanup(admin, PARTNER);
        }
    }

    @Test
    void saving_twice_does_not_duplicate_the_responsibility() {
        // 商机会被反复编辑。每存一次多一行拓展责任，就是每存一次多付一份佣金。
        String admin = login("ADMIN");
        try {
            String no = post("/api/ops/leads", lead("SIGNED", "AGENT", PARTNER, SITE), admin)
                    .okData().path("leadNo").asText();
            post("/api/ops/leads/" + no, lead("SIGNED", "AGENT", PARTNER, SITE), admin).okData();

            int n = 0;
            for (JsonNode r : responsibilities(admin)) {
                if (PARTNER.equals(r.path("agentNo").asText()) && "DEVELOP".equals(r.path("role").asText())) n++;
            }
            assertThat(n).as("重复保存不该多出责任行").isEqualTo(1);
        } finally {
            cleanup(admin, PARTNER);
        }
    }

    @Test
    void illegal_owner_type_is_refused() {
        // 脏枚举进库后，判断处会走进「既不是 STAFF 也不是 AGENT」的缝里 —— 不报错，只是不算钱。
        assertThat(post("/api/ops/leads", lead("NEW", "PARTNER_X", PARTNER, null), login("ADMIN")).status)
                .as("归属方类型越界必须当场拒").isEqualTo(400);
    }

    @Test
    void conflicting_responsibility_does_not_block_saving_the_lead() {
        // 该伙伴在本站点已有「牵线」，与「拓展」互斥。冲突是真实的业务问题，要让运营看见并决定，
        // 但**不能把商机也存不上** —— BD 会以为没存上而重填一遍，而真正该看见的是那条告警。
        String admin = login("ADMIN");
        long refer = post("/api/ops/sites/" + SITE + "/agents",
                Map.of("agentNo", PARTNER, "role", "REFER"), admin).okData().path("id").asLong();
        try {
            assertThat(post("/api/ops/leads", lead("SIGNED", "AGENT", PARTNER, SITE), admin).status)
                    .as("归因写不成，商机本身仍要存下来").isEqualTo(200);
            assertThat(hasDevelop(admin, PARTNER)).as("互斥的责任不该被写进去").isFalse();
        } finally {
            post("/api/ops/sites/" + SITE + "/agents/" + refer + "/remove", Map.of(), admin).okData();
            cleanup(admin, PARTNER);
        }
    }
}
