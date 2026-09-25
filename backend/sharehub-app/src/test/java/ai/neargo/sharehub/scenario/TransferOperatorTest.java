package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调拨单的经办人。调拨是<b>真的把设备从一个地方搬到另一个地方</b>，
 * 「谁经的手」是少了一台时唯一的追责线索。
 *
 * <h2>全仓只有这一处例外</h2>
 * 同类字段在别处一律由服务端按当前登录人回填：
 * {@code UsrFreeWhitelist.grantedBy}、{@code UsrBlacklist.blacklistedBy/releasedBy}、
 * {@code AgtApply.operatorNo} 都是 service 从 staff 上下文写。
 * 只有 {@code inv_transfer.operator_no} 走的是请求体 ——
 * 建单时实体直接 {@code insert(body)}（全字段），更新时又有一句
 * {@code if (notBlank(body.getOperatorNo())) current.setOperatorNo(...)}。
 *
 * <p>所以任何有 {@code device:inventory:transfer} 的人都可以把经办人写成别人的工号，
 * 而且<b>不报错</b> —— 单据看起来完全正常，只是署了另一个人的名。
 */
class TransferOperatorTest extends ApiTestSupport {

    private Map<String, Object> draft() {
        Map<String, Object> m = new HashMap<>();
        m.put("fromType", "WAREHOUSE");
        m.put("fromRef", "WH0001");
        m.put("fromName", "三号仓");
        m.put("toType", "SITE");
        m.put("toRef", "ST0001");
        m.put("toName", "万达广场");
        m.put("itemType", "POWERBANK");
        m.put("powerbankCount", 40);
        return m;
    }

    private JsonNode row(String admin, String no) {
        return findInPages("/api/ops/inventory-transfers?keyword=" + no, "transferNo", no, admin);
    }

    @Test
    @DisplayName("★★ 建单时传别人的工号，经办人不能变成他")
    void operator_cannot_be_forged_on_create() {
        String admin = login("ADMIN");
        Map<String, Object> evil = draft();
        evil.put("operatorNo", "EMP_SOMEONE_ELSE");

        String no = post("/api/ops/inventory-transfers", evil, admin).okData().path("transferNo").asText();

        assertThat(row(admin, no).path("operator").asText(""))
                .as("经办人由服务端按当前登录人回填，传进来的一律不算")
                .isNotEqualTo("EMP_SOMEONE_ELSE");
    }

    @Test
    @DisplayName("★★ 建完单之后也不能把经办人改成别人")
    void operator_cannot_be_forged_on_update() {
        String admin = login("ADMIN");
        String no = post("/api/ops/inventory-transfers", draft(), admin).okData().path("transferNo").asText();
        String before = row(admin, no).path("operator").asText("");

        Map<String, Object> evil = new HashMap<>();
        evil.put("powerbankCount", 41);
        evil.put("operatorNo", "EMP_SOMEONE_ELSE");
        post("/api/ops/inventory-transfers/" + no, evil, admin);

        assertThat(row(admin, no).path("operator").asText(""))
                .as("经办人不随保存端点变 —— 要换人经办就换个人来操作")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("经办人要真的有值——否则丢货时无人可问")
    void operator_is_filled_in() {
        String admin = login("ADMIN");
        String no = post("/api/ops/inventory-transfers", draft(), admin).okData().path("transferNo").asText();

        assertThat(row(admin, no).path("operator").asText(""))
                .as("建单即落经办人")
                .isNotBlank();
    }
}
