package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** D5：机柜每次状态变动的原因都留在审计改前改后里，fault_reason 被下一次覆盖也不丢履历。 */
class CabinetReasonHistoryTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("★ 标故障再报废：两次原因都能在审计里查到")
    void both_reasons_survive_in_the_audit_trail() {
        String admin = login("ADMIN");
        String no = "CABR" + Long.toString(System.nanoTime(), 36).toUpperCase();
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), admin).okData();
        jdbc.update("UPDATE dev_cabinet SET status = 'DEPLOYED' WHERE cabinet_no = ?", no);

        post("/api/ops/devices/" + no + "/mark-fault", Map.of("reason", "主板进水"), admin).okData();
        post("/api/ops/devices/" + no + "/retire", Map.of("reason", "维修不经济"), admin).okData();

        assertThat(jdbc.queryForObject("SELECT fault_reason FROM dev_cabinet WHERE cabinet_no = ?", String.class, no))
                .as("当前原因是最后一次").isEqualTo("维修不经济");
        List<String> details = jdbc.queryForList(
                "SELECT detail FROM iam_audit_log WHERE action LIKE ? ORDER BY id", String.class, "%/devices/" + no + "/%");
        assertThat(String.join("\n", details)).as("第一次的原因没有被覆盖掉").contains("主板进水").contains("维修不经济");
    }
}
