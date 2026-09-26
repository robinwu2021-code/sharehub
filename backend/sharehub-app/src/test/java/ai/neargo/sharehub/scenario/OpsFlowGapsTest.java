package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接入工单 §5.8 的三条「服务端该拦却没拦」（#3 #6 #7）。
 *
 * <p>共同点：前端都已经兜住了，所以界面上看不出问题 —— 而兜底放在前端就意味着
 * 换一个客户端（C 端、代理端、第三方、curl）会再踩一次。**闸必须在服务端**。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsFlowGapsTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;
    private String agentNo;
    private String exitNo;
    private final List<String> transfers = new java.util.ArrayList<>();

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
        agentNo = "AGGAP" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO agt_agent (agent_no, tenant_id, name, contact, agent_type, default_share_rate, status) "
                + "VALUES (?, 'MAIN', ?, '13900000000', 'AGENT', 0.30, 'ENABLED')", agentNo, "缺口用例代理");
    }

    @AfterAll
    void tearDown() {
        if (exitNo != null) jdbc.update("DELETE FROM agt_exit WHERE exit_no=?", exitNo);
        for (String t : transfers) {
            jdbc.update("DELETE FROM inv_transfer_item WHERE transfer_no=?", t);
            jdbc.update("DELETE FROM inv_transfer WHERE transfer_no=?", t);
        }
        jdbc.update("DELETE FROM agt_agent WHERE agent_no=?", agentNo);
    }

    @Test
    @DisplayName("#6 代理档案能查到在途清退单（此前单号只在「发起」那一次的返回值里，刷新就找不到了）")
    void open_exit_is_queryable_by_agent() {
        // 还没发起时：返回空而不是 404 ——「没有在清退」是正常状态，不是错误
        Resp none = get("/api/agent/agents/" + agentNo + "/exit", admin);
        assertThat(none.status).isEqualTo(200);
        assertThat(none.body.path("data").isNull()).isTrue();

        exitNo = post("/api/agent/agents/" + agentNo + "/exit", Map.of("reason", "合作到期不再续"), admin)
                .okData().path("exitNo").asText();
        assertThat(exitNo).isNotBlank();

        JsonNode open = get("/api/agent/agents/" + agentNo + "/exit", admin).okData();
        assertThat(open.path("exitNo").asText()).isEqualTo(exitNo);
        assertThat(open.path("status").asText()).isEqualTo("RECLAIMING");
    }

    @Test
    @DisplayName("#7 清退中的代理不能从档案里改回启用——否则提现解冻、又开始派新单，而清退单还停在原处")
    void cannot_re_enable_agent_while_exiting() {
        if (exitNo == null) open_exit_is_queryable_by_agent();
        assertThat(status()).as("发起清退即停用").isEqualTo("SUSPENDED");

        Resp r = post("/api/agent/agents/" + agentNo,
                Map.of("name", "缺口用例代理", "contact", "13900000000", "agentType", "AGENT",
                        "shareRate", 0.30, "cabinetCount", 0, "status", "ENABLED"), admin);
        assertThat(r.status).as("%s", r.body).isEqualTo(409);
        assertThat(status()).as("被拒之后状态不能被改掉").isEqualTo("SUSPENDED");

        // 正对照：不碰状态的字段照常能改 —— 否则这条闸可能是「整个保存都挂了」
        Resp ok = post("/api/agent/agents/" + agentNo,
                Map.of("name", "缺口用例代理（改名）", "contact", "13900000001", "agentType", "AGENT",
                        "shareRate", 0.30, "cabinetCount", 0, "status", "SUSPENDED"), admin);
        assertThat(ok.status).as("%s", ok.body).isEqualTo(200);
    }

    @Test
    @DisplayName("#3 零明细的调拨单不能发货——接收方等着收货，而车上什么都没有")
    void cannot_ship_empty_transfer() {
        String no = "TRGAP" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        transfers.add(no);
        jdbc.update("INSERT INTO inv_transfer (transfer_no, tenant_id, item_type, from_type, from_ref, from_location, "
                + "to_type, to_ref, to_location, status) "
                + "VALUES (?, 'MAIN', 'CABINET', 'WAREHOUSE', 'WH001', '中心仓', 'SITE', 'ST300', '测试站点', 'DRAFT')", no);

        Resp r = post("/api/ops/inventory-transfers/" + no + "/ship", Map.of(), admin);
        assertThat(r.status).as("专用发货端点要拒：%s", r.body).isEqualTo(409);

        /*
         * **发货有两个入口**，另一条是保存时传目标状态（走的是另一套代码）。
         * 只堵 /ship 等于没堵 —— 这条断言就是为了钉住那个入口。
         */
        Resp viaSave = post("/api/ops/inventory-transfers/" + no, Map.of("status", "IN_TRANSIT"), admin);
        assertThat(viaSave.status).as("保存端点这条路同样要拒：%s", viaSave.body).isEqualTo(409);

        assertThat(jdbc.query("SELECT status FROM inv_transfer WHERE transfer_no=?",
                (java.sql.ResultSet rs) -> rs.next() ? rs.getString(1) : null, no))
                .as("被拒之后仍是草稿，不能已经变成在途").isEqualTo("DRAFT");
    }

    private String status() {
        return jdbc.query("SELECT status FROM agt_agent WHERE agent_no=?",
                (java.sql.ResultSet rs) -> rs.next() ? rs.getString(1) : null, agentNo);
    }
}
