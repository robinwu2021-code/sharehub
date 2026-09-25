package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.loc.service.ContractService;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合同走审批（TDD-运营核心流程/02）+ 文件上传（/01）的端到端。
 *
 * <p>每个用例自建站点：共享测试库里同一站点常有多份历史合同，期限重叠校验会让结果取决于库的历史。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractApprovalTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ContractService contracts;

    String bd;
    String admin;

    @BeforeAll
    void tokens() {
        bd = login("BD");
        admin = login("ADMIN");
    }

    @Test
    @DisplayName("★ 起草 → 提交 → 他人审批通过 → 签署（开始日已到）→ 生效；介绍费事件恰好一次")
    void happyPath() {
        String site = newSite();
        String no = draft(site, LocalDate.now().minusDays(1), LocalDate.now().plusYears(1)).path("contractNo").asText();
        assertThat(post("/api/ops/contracts/" + no + "/submit", Map.of(), bd).okData().path("status").asText()).isEqualTo("PENDING");
        assertThat(post("/api/ops/contracts/" + no + "/audit", Map.of("result", "APPROVE"), admin).okData().path("status").asText())
                .isEqualTo("SIGNED");

        String fileNo = upload("CONTRACT_SCAN", "scan.pdf", pdfBytes(), bd).path("fileNo").asText();
        JsonNode signed = post("/api/ops/contracts/" + no + "/sign",
                Map.of("signedAt", LocalDate.now().toString(), "fileNos", List.of(fileNo)), bd).okData();
        assertThat(signed.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(signed.path("attachments").get(0).path("fileNo").asText()).isEqualTo(fileNo);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_type='CONTRACT_SIGNED' AND aggregate_id=?",
                Integer.class, no)).isEqualTo(1);

        JsonNode logs = get("/api/ops/contracts/" + no + "/logs", bd).okData();
        assertThat(logs.findValuesAsText("event")).containsSubsequence("CREATE", "SUBMIT", "APPROVE", "SIGN", "ACTIVATE");

        // 附件可按限时链接取回（本地存储：自签自验）
        String url = get("/api/platform/files/" + fileNo + "/url", bd).okData().path("url").asText();
        assertThat(rawGet(url)).isEqualTo(200);
    }

    @Test
    @DisplayName("不能审批自己提交的合同 → 409；驳回不填原因 → 400；驳回回到草稿")
    void auditRules() {
        String site = newSite();
        String no = draft(site, LocalDate.now().plusDays(10), LocalDate.now().plusYears(1)).path("contractNo").asText();
        post("/api/ops/contracts/" + no + "/submit", Map.of(), admin).okData();
        assertThat(post("/api/ops/contracts/" + no + "/audit", Map.of("result", "APPROVE"), admin).status).isEqualTo(409);
        assertThat(post("/api/ops/contracts/" + no + "/audit", Map.of("result", "REJECT"), bd).status).isEqualTo(400);
        assertThat(post("/api/ops/contracts/" + no + "/audit", Map.of("result", "REJECT", "reason", "比例太高"), bd)
                .okData().path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("只有草稿可改；期限与同站点待审合同重叠时提交 → 409")
    void draftOnlyAndOverlap() {
        String site = newSite();
        String a = draft(site, LocalDate.now().plusDays(1), LocalDate.now().plusYears(1)).path("contractNo").asText();
        post("/api/ops/contracts/" + a + "/submit", Map.of(), bd).okData();
        assertThat(post("/api/ops/contracts/" + a, body(site, LocalDate.now().plusDays(1), LocalDate.now().plusYears(1)), bd).status)
                .isEqualTo(409);
        String b = draft(site, LocalDate.now().plusMonths(3), LocalDate.now().plusYears(2)).path("contractNo").asText();
        assertThat(post("/api/ops/contracts/" + b + "/submit", Map.of(), bd).status).isEqualTo(409);
    }

    @Test
    @DisplayName("开始日在未来：签署后保持 SIGNED；推到开始日跑定时 → ACTIVE；再推过到期日 → EXPIRED；再跑一次无变化")
    void tickActivatesAndExpires() {
        String site = newSite();
        LocalDate start = LocalDate.now().plusDays(5), end = LocalDate.now().plusDays(20);
        String no = draft(site, start, end).path("contractNo").asText();
        post("/api/ops/contracts/" + no + "/submit", Map.of(), bd).okData();
        post("/api/ops/contracts/" + no + "/audit", Map.of("result", "APPROVE"), admin).okData();
        String fileNo = upload("CONTRACT_SCAN", "scan.pdf", pdfBytes(), bd).path("fileNo").asText();
        assertThat(post("/api/ops/contracts/" + no + "/sign", Map.of("signedAt", LocalDate.now().toString(), "fileNos", List.of(fileNo)), bd)
                .okData().path("status").asText()).isEqualTo("SIGNED");

        contracts.tick(start);
        assertThat(status(no)).isEqualTo("ACTIVE");
        contracts.tick(end.plusDays(1));
        assertThat(status(no)).isEqualTo("EXPIRED");
        assertThat(contracts.tick(end.plusDays(1)).expired()).isZero();
    }

    @Test
    @DisplayName("文件：改名成 .pdf 的非 PDF 拒绝；别人上传的临时文件不能绑定")
    void fileGuards() {
        assertThat(uploadResp("CONTRACT_SCAN", "fake.pdf", "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8), bd).status)
                .isEqualTo(400);
        String site = newSite();
        String no = draft(site, LocalDate.now().plusDays(1), LocalDate.now().plusYears(1)).path("contractNo").asText();
        String someoneElses = upload("CONTRACT_SCAN", "scan.pdf", pdfBytes(), admin).path("fileNo").asText();
        assertThat(post("/api/ops/contracts/" + no + "/attachments", Map.of("fileNos", List.of(someoneElses)), bd).status).isEqualTo(400);
    }

    // —— 夹具 ——

    private String status(String no) {
        return jdbc.queryForObject("SELECT status FROM loc_contract WHERE contract_no=?", String.class, no);
    }

    private String newSite() {
        String siteNo = "STT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status) VALUES (?, 'MAIN', ?, ?, 'PREPARING')",
                siteNo, venue, "合同测试站点 " + siteNo);
        return siteNo;
    }

    private Map<String, Object> body(String site, LocalDate start, LocalDate end) {
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_site WHERE site_no=?", String.class, site);
        Map<String, Object> m = new HashMap<>();
        m.put("venueNo", venue);
        m.put("siteNo", site);
        m.put("shareMode", "SHARE");
        m.put("shareRate", 0.2);
        m.put("startAt", start.toString());
        m.put("endAt", end.toString());
        return m;
    }

    private JsonNode draft(String site, LocalDate start, LocalDate end) {
        JsonNode d = post("/api/ops/contracts", body(site, start, end), bd).okData();
        assertThat(d.path("status").asText()).isEqualTo("DRAFT");
        return d;
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    private JsonNode upload(String category, String name, byte[] bytes, String token) {
        return uploadResp(category, name, bytes, token).okData();
    }

    private Resp uploadResp(String category, String name, byte[] bytes, String token) {
        String boundary = "----sharehub" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"category\"\r\n\r\n" + category + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(bytes);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/platform/files"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return sendRequest(req);
    }

    private int rawGet(String relativeUrl) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + relativeUrl)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
