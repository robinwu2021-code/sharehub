package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费者提交之后，运营端得有人受理（注销 C-AC-05 / 开票 C-IV-03 的运营侧）。
 *
 * <h2>这两处的共同形状</h2>
 * <b>C 端已经在产生数据，运营端没有对应的动作面</b> —— 不报错、不红、用户也不会投诉，
 * 因为他们不知道本该有。只有拿领域模型逐对象扫一遍才会露出来。
 *
 * <ul>
 *   <li><b>注销</b>：C 端能提交、能看冷静期、能自助撤销，而运营端零入口。
 *       用户打电话说「我点错了」时，客服既看不到队列也无从代为撤销 ——
 *       只能让他自己在 App 里找，而他正是因为找不到才打的电话。</li>
 *   <li><b>开票</b>：运营端「发票」那个菜单叶管的是 {@code fin_invoice}（给场地方/代理商开的
 *       结算发票），与消费者开票是两个对象、两张表、两套业务键。
 *       于是 C 端能提交，提交之后申请永远停在 APPLIED。</li>
 * </ul>
 */
class OpsConsumerQueueTest extends ApiTestSupport {

    private String consumerToken() {
        String phone = "+9715003" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    @Test
    @DisplayName("★★ 注销队列看得到，且客服能代为撤销——撤销之后 C 端自己查也该是撤销了")
    void ops_can_see_and_revoke_a_logoff_request() {
        String token = consumerToken();
        String cUserNo = get("/mp/user/profile", token).okData().path("cUserNo").asText();
        post("/mp/user/logoff", Map.of(), token).okData();

        String cs = login("CS");
        JsonNode mine = findInPages("/api/user/logoffs?status=PENDING", "cUserNo", cUserNo, cs);
        assertThat(mine).as("刚提交的注销申请应当出现在运营端队列里").isNotNull();
        assertThat(mine.path("coolingUntil").asText()).as("冷静期截止时间要带出来").isNotBlank();
        // 这个出参能给只读角色看的前提：它不含手机号与姓名
        assertThat(mine.has("phone")).as("注销队列不该带手机号").isFalse();

        post("/api/user/logoffs/" + cUserNo + "/revoke", Map.of(), cs).okData();

        // 运营端说撤了不算数，要 C 端自己查得到才算
        JsonNode after = get("/mp/user/logoff", token).okData();
        assertThat(after.isNull() || !"PENDING".equals(after.path("status").asText()))
                .as("客服代撤之后，用户在 App 里看到的也必须不是 PENDING").isTrue();
    }

    @Test
    @DisplayName("注销队列要权限码——没有 user:logoff:read 的角色应当 403")
    void logoff_queue_is_gated() {
        assertThat(get("/api/user/logoffs", login("BD")).status)
                .as("BD 不持有 user:logoff:read").isEqualTo(403);
    }

    @Test
    @DisplayName("★★ 开票申请：受理后 C 端看到的状态跟着变，且驳回必须写原因")
    void ops_can_issue_and_reject_a_consumer_invoice() {
        String token = consumerToken();
        String titleNo = post("/mp/user/invoice-titles",
                Map.of("type", "PERSONAL", "title", "测试抬头"), token).okData().path("titleNo").asText();
        String invoiceNo = post("/mp/user/invoices",
                Map.of("titleNo", titleNo, "amount", 12.5), token).okData().path("invoiceNo").asText();

        String fin = login("FINANCE");
        assertThat(findInPages("/api/user/cuser-invoices?status=APPLIED", "invoiceNo", invoiceNo, fin))
                .as("消费者提交的开票申请应当出现在受理队列里").isNotNull();

        // 驳回必须写原因：只说「已驳回」等于让用户无从改正后重提
        assertThat(post("/api/user/cuser-invoices/" + invoiceNo + "/reject", Map.of(), fin).status)
                .as("不写原因就驳回应当被拒").isEqualTo(400);

        JsonNode issued = post("/api/user/cuser-invoices/" + invoiceNo + "/issue",
                Map.of("fileUrl", "https://example.com/inv.pdf"), fin).okData();
        assertThat(issued.path("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.path("handledBy").asText()).as("受理人要留痕，否则事后问「谁开的」答不出来").isNotBlank();

        // 同一单不能再受理一次，否则消费者那边的状态会凭空变回去
        assertThat(post("/api/user/cuser-invoices/" + invoiceNo + "/issue",
                Map.of("fileUrl", "x"), fin).status).isEqualTo(409);

        // 运营端说开了不算数，要 C 端自己查得到才算
        JsonNode mine = findInPages("/mp/user/invoices", "invoiceNo", invoiceNo, token);
        assertThat(mine).isNotNull();
        assertThat(mine.path("status").asText()).as("C 端看到的状态要跟着变").isEqualTo("ISSUED");
    }
}
