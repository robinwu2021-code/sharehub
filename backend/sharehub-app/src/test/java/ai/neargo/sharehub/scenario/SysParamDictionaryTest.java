package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统参数字典化（V191，用户 2026-10-29：所有需要拍板的都做成配置 / 数据字典，管理员调整）。
 * 写入按字典校验并归一；元数据只认库里的；待拍板项可一键筛出。
 */
class SysParamDictionaryTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void restore() {
        jdbc.update("UPDATE sys_param SET value = 'CEIL' WHERE param_key = 'billing.unit_rounding'");
        jdbc.update("UPDATE sys_param SET value = '0' WHERE param_key = 'rent.service_fee.amount'");
        jdbc.update("UPDATE sys_param SET value = '1' WHERE param_key = 'rent.service_fee.vat_applicable'");
        jdbc.update("UPDATE sys_param SET value = '' WHERE param_key = 'approval.compensation.threshold'");
    }

    private int save(String key, String value) {
        return save(key, value, Map.of());
    }

    private int save(String key, String value, Map<String, Object> extra) {
        Map<String, Object> b = new HashMap<>(extra);
        b.put("paramKey", key);
        b.put("value", value);
        return post("/api/platform/sys-params/" + key, b, login("ADMIN")).status;
    }

    private String value(String key) {
        return jdbc.queryForObject("SELECT value FROM sys_param WHERE param_key = ?", String.class, key);
    }

    @Test
    @DisplayName("★★ 待拍板项一键筛出，带类型 / 可选值 / 范围 / 影响说明")
    void pending_decisions_listed_with_dictionary() {
        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode p : get("/api/platform/sys-params?size=200&pendingDecision=1", login("ADMIN")).okData().path("list")) {
            byKey.put(p.path("paramKey").asText(), p);
        }
        assertThat(byKey).containsKeys("rent.service_fee.amount", "finance.share.rate_min", "approval.compensation.threshold",
                "invoice.b2c.enabled", "billing.unit_rounding", "rent.overdue.mode", "contract.expiry.remind_days");
        assertThat(byKey.values()).allSatisfy(p -> assertThat(p.path("pendingDecision").asBoolean()).isTrue());
        JsonNode rounding = byKey.get("billing.unit_rounding");
        assertThat(rounding.path("valueType").asText()).isEqualTo("ENUM");
        assertThat(rounding.path("options")).hasSize(3);
        assertThat(rounding.path("options").get(0).path("label").asText()).isNotBlank();
        JsonNode fee = byKey.get("rent.service_fee.amount");
        assertThat(fee.path("valueType").asText()).isEqualTo("DECIMAL");
        assertThat(fee.path("hint").asText()).contains("0.60");
        assertThat(byKey.get("approval.compensation.threshold").path("nullable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★ 写入按字典校验并归一：枚举 / 数字范围 / 开关 / 必填 / 留空 = 关")
    void values_validated_and_normalised() {
        assertThat(save("billing.unit_rounding", "ROUND")).isEqualTo(400);
        assertThat(save("billing.unit_rounding", " floor ")).isEqualTo(200);
        assertThat(value("billing.unit_rounding")).isEqualTo("FLOOR");

        assertThat(save("rent.service_fee.amount", "0.6x")).isEqualTo(400);
        assertThat(save("rent.service_fee.amount", "-1")).isEqualTo(400);
        assertThat(save("rent.service_fee.amount", "0.60")).isEqualTo(200);
        assertThat(value("rent.service_fee.amount")).isEqualTo("0.6");
        assertThat(save("rent.service_fee.amount", "")).as("不可留空").isEqualTo(400);

        assertThat(save("rent.service_fee.vat_applicable", "yes")).isEqualTo(400);
        assertThat(save("rent.service_fee.vat_applicable", "false")).isEqualTo(200);
        assertThat(value("rent.service_fee.vat_applicable")).isEqualTo("0");

        assertThat(save("contract.expiry.remind_days", "0")).isEqualTo(400);
        assertThat(save("contract.expiry.remind_days", "60.5")).isEqualTo(400);

        assertThat(save("approval.compensation.threshold", "50")).isEqualTo(200);
        assertThat(save("approval.compensation.threshold", "")).as("留空 = 关闭审批").isEqualTo(200);
        assertThat(value("approval.compensation.threshold")).isEmpty();
    }

    @Test
    @DisplayName("★ 元数据只认库里的：请求体把类型改成 TEXT、把可留空改成 1 都不生效")
    void dictionary_cannot_be_rewritten_from_request() {
        assertThat(save("billing.unit_rounding", "WHATEVER", Map.of("valueType", "TEXT", "valueOptions", "[]"))).isEqualTo(400);
        assertThat(save("rent.service_fee.amount", "", Map.of("nullable", 1))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT value_type FROM sys_param WHERE param_key = 'billing.unit_rounding'", String.class))
                .isEqualTo("ENUM");
    }
}
