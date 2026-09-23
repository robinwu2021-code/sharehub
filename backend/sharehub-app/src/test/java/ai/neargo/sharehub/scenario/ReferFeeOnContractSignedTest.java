package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **合同签约 → 一次性牵线费**（ADR-027 §四 / TDD-A2 第 4 批）。
 *
 * <p>牵线的对价是「把关系介绍过来」这个一次性动作。按逐单比例付会变成
 * 「介绍一次、分十年」；触发点选签约而不是首单结算 —— 合同签了钱就该付，
 * 不该让介绍人等第一个顾客（那个顾客什么时候来，牵线人既不能控制也看不见）。
 *
 * <p>本测试同时覆盖 A2-4 的**前置**：进场合同的写入口。它此前根本不存在，
 * 合同只有种子在写，前端的「新增/编辑」在 {@code USE_MOCK=0} 下必 404。
 */
class ReferFeeOnContractSignedTest extends ApiTestSupport {

    /** ST311 在别处没人用，避免用例之间互相改对方的责任行。 */
    private static final String SITE = "ST311";
    private static final String VENUE = "VEN301";
    private static final String REFERRER = "AG005";

    private Map<String, Object> contract(String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("venueNo", VENUE);
        m.put("siteNo", SITE);
        m.put("venueName", "牵线费测试场地方");
        m.put("siteName", "牵线费测试站点");
        m.put("shareRate", 0.2);
        m.put("entryFee", 1000);
        m.put("startAt", "2026-01-01");
        m.put("endAt", "2027-01-01");
        m.put("currency", "AED");
        m.put("status", status);
        return m;
    }

    private long referrer(String admin, Object oneOff) {
        Map<String, Object> m = new HashMap<>();
        m.put("agentNo", REFERRER);
        m.put("role", "REFER");
        if (oneOff != null) m.put("oneOffAmount", oneOff);
        return post("/api/ops/sites/" + SITE + "/agents", m, admin).okData().path("id").asLong();
    }

    private void drop(String admin, long id) {
        post("/api/ops/sites/" + SITE + "/agents/" + id + "/remove", Map.of(), admin).okData();
    }

    private JsonNode referFees(String admin, String contractNo) {
        return get("/api/trade/share-records?page=1&size=50&keyword=" + contractNo, admin)
                .okData().path("list");
    }

    @Test
    void contract_write_endpoint_exists_and_persists() {
        // 前置：这个端点此前不存在。合同是场地方分成的唯一依据 ——
        // 建不了合同，场地方费率就只能靠改库。
        String admin = login("ADMIN");
        JsonNode c = post("/api/ops/contracts", contract("ACTIVE"), admin).okData();
        assertThat(c.path("contractNo").asText()).as("服务端取号").startsWith("CT");
        assertThat(c.path("shareRate").asDouble()).isEqualTo(0.2);
    }

    @Test
    void contract_must_bind_a_site() {
        // 不绑站点的合同，场地方分成取价时找不到它，会静默回落到通用规则 ——
        // 而回落值与合同里白纸黑字写的比例往往不一样。
        Map<String, Object> bad = contract("ACTIVE");
        bad.remove("siteNo");
        assertThat(post("/api/ops/contracts", bad, login("ADMIN")).status)
                .as("合同不绑站点必须当场拒").isEqualTo(400);
    }

    @Test
    void end_date_before_start_is_refused() {
        Map<String, Object> bad = contract("ACTIVE");
        bad.put("endAt", "2025-01-01");
        assertThat(post("/api/ops/contracts", bad, login("ADMIN")).status).isEqualTo(400);
    }

    @Test
    void signing_pays_the_referrer_once() {
        String admin = login("ADMIN");
        long id = referrer(admin, 500);
        try {
            String no = post("/api/ops/contracts", contract("ACTIVE"), admin)
                    .okData().path("contractNo").asText();

            JsonNode fees = referFees(admin, no);
            assertThat(fees).as("签约应结出牵线费").hasSize(1);
            assertThat(fees.get(0).path("basis").asText()).isEqualTo("REFER");
            assertThat(fees.get(0).path("payeeNo").asText()).isEqualTo(REFERRER);
            assertThat(fees.get(0).path("amount").asDouble())
                    .as("金额直接取责任行上的一次性对价，不乘任何基数").isEqualTo(500.00);
            // 来源单据是合同本身，不是某一张订单 —— 硬塞订单号会指向一笔无关的交易
            assertThat(fees.get(0).path("orderNo").asText()).isEqualTo(no);
        } finally {
            drop(admin, id);
        }
    }

    @Test
    void saving_the_same_contract_again_does_not_pay_twice() {
        // 合同会被反复编辑（补附件、改到期日）。每存一次付一次牵线费，就是每改一次多付一笔。
        String admin = login("ADMIN");
        long id = referrer(admin, 500);
        try {
            String no = post("/api/ops/contracts", contract("ACTIVE"), admin)
                    .okData().path("contractNo").asText();
            Map<String, Object> again = contract("ACTIVE");
            again.put("entryFee", 1200);
            post("/api/ops/contracts/" + no, again, admin).okData();

            assertThat(referFees(admin, no)).as("重复保存不该重复付").hasSize(1);
        } finally {
            drop(admin, id);
        }
    }

    @Test
    void clearing_the_amount_actually_clears_it() {
        // MyBatis-Plus 的 updateById **只写非 null 字段**：默认策略下「把牵线费清空」
        // 会被静默丢掉 —— 保存成功、值没变、不报错，然后**继续按旧金额付钱**。
        String admin = login("ADMIN");
        long id = referrer(admin, 500);
        try {
            Map<String, Object> clear = new HashMap<>();
            clear.put("id", id);
            clear.put("agentNo", REFERRER);
            clear.put("role", "REFER");
            clear.put("oneOffAmount", null);
            post("/api/ops/sites/" + SITE + "/agents", clear, admin).okData();

            String no = post("/api/ops/contracts", contract("ACTIVE"), admin)
                    .okData().path("contractNo").asText();
            assertThat(referFees(admin, no)).as("清空之后不该再付").isEmpty();
        } finally {
            drop(admin, id);
        }
    }

    @Test
    void referrer_without_a_configured_amount_is_not_paid_zero() {
        // 没配 ≠ 0。记一条 0 元明细会把「该有人去配」和「明确不付」混为一谈，
        // 而运营看到 0 元只会以为这笔本来就不该有。
        String admin = login("ADMIN");
        long id = referrer(admin, null);
        try {
            String no = post("/api/ops/contracts", contract("ACTIVE"), admin)
                    .okData().path("contractNo").asText();
            assertThat(referFees(admin, no)).as("没配金额时不落库，只告警").isEmpty();
        } finally {
            drop(admin, id);
        }
    }

    @Test
    void site_without_referrer_pays_nothing() {
        // 绝大多数站点没有牵线人。多付给一个不存在的受益方是最贵的那种静默错账。
        String admin = login("ADMIN");
        String no = post("/api/ops/contracts", contract("ACTIVE"), admin)
                .okData().path("contractNo").asText();
        assertThat(referFees(admin, no)).isEmpty();
    }

    @Test
    void expired_contract_pays_nothing() {
        // 录一份已到期的历史合同不是签约动作，不该触发付款。
        String admin = login("ADMIN");
        long id = referrer(admin, 500);
        try {
            String no = post("/api/ops/contracts", contract("EXPIRED"), admin)
                    .okData().path("contractNo").asText();
            assertThat(referFees(admin, no)).as("非 ACTIVE 不视为签约").isEmpty();
        } finally {
            drop(admin, id);
        }
    }
}
