package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.loc.service.ContractService;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **合同生效 → 一次性牵线费**（ADR-027 §四 / TDD-A2 第 4 批）。
 *
 * <p>牵线的对价是「把关系介绍过来」这个一次性动作。按逐单比例付会变成
 * 「介绍一次、分十年」；触发点选签约而不是首单结算 —— 合同签了钱就该付，
 * 不该让介绍人等第一个顾客（那个顾客什么时候来，牵线人既不能控制也看不见）。
 *
 * <p>2026-09-25 合同走审批（TDD-运营核心流程/02）：「签约」精确为<b>合同生效（ACTIVATE）</b>——
 * 起草 → 提交 → 他人审批 → 签署（上传签署件）→ 开始日已到即生效。事件只在生效那一刻发一次，
 * 录入 / 编辑 / 审批都不再触发付款。每个用例自建站点：同一站点期限重叠的合同提交不了。
 */
class ReferFeeOnContractSignedTest extends ApiTestSupport {

    private static final String VENUE = "VEN301";
    private static final String REFERRER = "AG005";

    @Autowired
    ContractService contracts;

    private String newSite(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "牵线费测试站点");
        m.put("venueNo", VENUE);
        m.put("regionId", "R-REFER");
        m.put("openHours", "10:00-22:00");
        return post("/api/ops/sites", m, admin).okData().path("siteNo").asText();
    }

    private Map<String, Object> contract(String site, LocalDate start, LocalDate end) {
        Map<String, Object> m = new HashMap<>();
        m.put("venueNo", VENUE);
        m.put("siteNo", site);
        m.put("shareRate", 0.2);
        m.put("entryFee", 1000);
        m.put("startAt", start.toString());
        m.put("endAt", end.toString());
        m.put("currency", "AED");
        return m;
    }

    /** 走完审批链：BD 起草并提交 → 管理员审批 → BD 上传签署件并签署。开始日已到则当场生效。 */
    private String signedContract(String site, LocalDate start) {
        String bd = login("BD"), admin = login("ADMIN");
        String no = post("/api/ops/contracts", contract(site, start, start.plusYears(1)), bd).okData().path("contractNo").asText();
        post("/api/ops/contracts/" + no + "/submit", Map.of(), bd).okData();
        post("/api/ops/contracts/" + no + "/audit", Map.of("result", "APPROVE"), admin).okData();
        String fileNo = uploadFile("CONTRACT_SCAN", "scan.pdf", minimalPdf(), bd).path("fileNo").asText();
        post("/api/ops/contracts/" + no + "/sign", Map.of("signedAt", LocalDate.now().toString(), "fileNos", List.of(fileNo)), bd).okData();
        return no;
    }

    private long referrer(String admin, String site, Object oneOff) {
        Map<String, Object> m = new HashMap<>();
        m.put("agentNo", REFERRER);
        m.put("role", "REFER");
        if (oneOff != null) m.put("oneOffAmount", oneOff);
        return post("/api/ops/sites/" + site + "/agents", m, admin).okData().path("id").asLong();
    }

    private void drop(String admin, String site, long id) {
        post("/api/ops/sites/" + site + "/agents/" + id + "/remove", Map.of(), admin).okData();
    }

    private JsonNode referFees(String admin, String contractNo) {
        return get("/api/trade/share-records?page=1&size=50&keyword=" + contractNo, admin)
                .okData().path("list");
    }

    @Test
    void contract_write_endpoint_exists_and_persists() {
        // 合同是场地方分成的唯一依据 —— 建不了合同，场地方费率就只能靠改库。
        String admin = login("ADMIN");
        JsonNode c = post("/api/ops/contracts", contract(newSite(admin), LocalDate.now(), LocalDate.now().plusYears(1)), admin).okData();
        assertThat(c.path("contractNo").asText()).as("服务端取号").startsWith("CT");
        assertThat(c.path("shareRate").asDouble()).isEqualTo(0.2);
        assertThat(c.path("status").asText()).as("录入即草稿，状态只经审批动作改").isEqualTo("DRAFT");
    }

    @Test
    void contract_must_bind_a_site() {
        // 不绑站点的合同，场地方分成取价时找不到它，会静默回落到通用规则 ——
        // 而回落值与合同里白纸黑字写的比例往往不一样。
        Map<String, Object> bad = contract("ignored", LocalDate.now(), LocalDate.now().plusYears(1));
        bad.remove("siteNo");
        assertThat(post("/api/ops/contracts", bad, login("ADMIN")).status)
                .as("合同不绑站点必须当场拒").isEqualTo(400);
    }

    @Test
    void end_date_before_start_is_refused() {
        String admin = login("ADMIN");
        Map<String, Object> bad = contract(newSite(admin), LocalDate.now(), LocalDate.now().minusYears(1));
        assertThat(post("/api/ops/contracts", bad, admin).status).isEqualTo(400);
    }

    @Test
    void signing_pays_the_referrer_once() {
        String admin = login("ADMIN");
        String site = newSite(admin);
        long id = referrer(admin, site, 500);
        try {
            String no = signedContract(site, LocalDate.now().minusDays(1));

            JsonNode fees = referFees(admin, no);
            assertThat(fees).as("生效应结出牵线费").hasSize(1);
            assertThat(fees.get(0).path("basis").asText()).isEqualTo("REFER");
            assertThat(fees.get(0).path("payeeNo").asText()).isEqualTo(REFERRER);
            assertThat(fees.get(0).path("amount").asDouble())
                    .as("金额直接取责任行上的一次性对价，不乘任何基数").isEqualTo(500.00);
            // 来源单据是合同本身，不是某一张订单 —— 硬塞订单号会指向一笔无关的交易
            assertThat(fees.get(0).path("orderNo").asText()).isEqualTo(no);
        } finally {
            drop(admin, site, id);
        }
    }

    @Test
    void activation_is_the_only_trigger_and_happens_once() {
        // 合同会被反复处理（补附件、定时任务每小时扫一遍）。每处理一次付一次，就是每扫一次多付一笔。
        String admin = login("ADMIN");
        String site = newSite(admin);
        long id = referrer(admin, site, 500);
        try {
            String no = signedContract(site, LocalDate.now().minusDays(1));
            contracts.tick(LocalDate.now());
            contracts.tick(LocalDate.now());
            assertThat(post("/api/ops/contracts/" + no + "/sign", Map.of("signedAt", LocalDate.now().toString()), login("BD")).status)
                    .as("生效后不能再签署").isIn(400, 409);
            assertThat(referFees(admin, no)).as("重复处理不该重复付").hasSize(1);
        } finally {
            drop(admin, site, id);
        }
    }

    @Test
    void clearing_the_amount_actually_clears_it() {
        // MyBatis-Plus 的 updateById **只写非 null 字段**：默认策略下「把牵线费清空」
        // 会被静默丢掉 —— 保存成功、值没变、不报错，然后**继续按旧金额付钱**。
        String admin = login("ADMIN");
        String site = newSite(admin);
        long id = referrer(admin, site, 500);
        try {
            Map<String, Object> clear = new HashMap<>();
            clear.put("id", id);
            clear.put("agentNo", REFERRER);
            clear.put("role", "REFER");
            clear.put("oneOffAmount", null);
            post("/api/ops/sites/" + site + "/agents", clear, admin).okData();

            String no = signedContract(site, LocalDate.now().minusDays(1));
            assertThat(referFees(admin, no)).as("清空之后不该再付").isEmpty();
        } finally {
            drop(admin, site, id);
        }
    }

    @Test
    void referrer_without_a_configured_amount_is_not_paid_zero() {
        // 没配 ≠ 0。记一条 0 元明细会把「该有人去配」和「明确不付」混为一谈，
        // 而运营看到 0 元只会以为这笔本来就不该有。
        String admin = login("ADMIN");
        String site = newSite(admin);
        long id = referrer(admin, site, null);
        try {
            String no = signedContract(site, LocalDate.now().minusDays(1));
            assertThat(referFees(admin, no)).as("没配金额时不落库，只告警").isEmpty();
        } finally {
            drop(admin, site, id);
        }
    }

    @Test
    void site_without_referrer_pays_nothing() {
        // 绝大多数站点没有牵线人。多付给一个不存在的受益方是最贵的那种静默错账。
        String admin = login("ADMIN");
        String no = signedContract(newSite(admin), LocalDate.now().minusDays(1));
        assertThat(referFees(admin, no)).isEmpty();
    }

    @Test
    void signed_but_not_yet_started_pays_only_on_activation() {
        // 签了但开始日未到：不是生效，不付；到了开始日由定时任务生效，那一刻付。
        String admin = login("ADMIN");
        String site = newSite(admin);
        long id = referrer(admin, site, 500);
        try {
            LocalDate start = LocalDate.now().plusDays(7);
            String no = signedContract(site, start);
            assertThat(referFees(admin, no)).as("SIGNED 未生效不付").isEmpty();
            contracts.tick(start);
            assertThat(referFees(admin, no)).as("生效那一刻付").hasSize(1);
        } finally {
            drop(admin, site, id);
        }
    }
}
