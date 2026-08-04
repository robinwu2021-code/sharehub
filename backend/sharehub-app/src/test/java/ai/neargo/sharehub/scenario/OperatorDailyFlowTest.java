package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 端到端：模拟一个真实的运营方（Dubai / AED）走一天日常运营。多角色协作：
 * 运营(OPS)做设备巡检与工单，拓展(BD)建代理商/站点，财务(FINANCE)审核提现。
 *
 * <p>数据来自 {@code fixtures/operator-daily.json}；写入均以固定业务键 upsert，可重复执行不累积。
 * 每步对应 ops-web 上的一个真实操作，断言 powerbank 契约与状态机行为。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorDailyFlowTest extends ApiTestSupport {

    private JsonNode fx;
    private String opsToken;
    private String bdToken;
    private String financeToken;

    @BeforeAll
    void signIn() {
        fx = fixtures();
        JsonNode a = fx.get("actors");
        opsToken = login("OPS", a.at("/ops/username").asText(), null);
        bdToken = login("BD", a.at("/bd/username").asText(), null);
        financeToken = login("FINANCE", a.at("/finance/username").asText(), null);
    }

    /** 步骤 1：登录后校验会话身份与权限码（/api/auth/me）。 */
    @Test @Order(1)
    void step01_login_yields_role_and_perms() {
        JsonNode me = get("/api/auth/me", opsToken).okData();
        assertThat(me.path("authenticated").asBoolean()).isTrue();
        assertThat(me.path("role").asText()).isEqualTo("OPS");
        // OPS 拥有工单全量与设备指令权限（通配）
        assertThat(me.path("perms").toString()).contains("workorder:*").contains("device:command:*");
    }

    /** 步骤 2：打开工作台总览——GMV/订单/在线率，货币 AED。 */
    @Test @Order(2)
    void step02_dashboard_overview() {
        JsonNode d = get("/api/ops/dashboard", opsToken).okData();
        assertThat(d.path("currency").asText()).isEqualTo("AED");
        assertThat(d.has("ordersToday")).isTrue();
        assertThat(d.has("openWorkOrders")).isTrue();
    }

    /** 步骤 3：设备巡检——分页列柜机、筛线下柜、查一台详情（含仓位）。 */
    @Test @Order(3)
    void step03_inspect_cabinets() {
        JsonNode page = get("/api/ops/cabinets?page=1&size=10", opsToken).okData();
        assertThat(page.path("total").asInt()).isEqualTo(fx.at("/expectedBaseline/cabinetsTotal").asInt());
        assertThat(page.path("list")).hasSize(10);

        // 关键词 + 状态过滤（曾被数据权限拦截器破坏，现已修复）
        JsonNode offline = get("/api/ops/cabinets?onlineStatus=OFFLINE&page=1&size=50", opsToken).okData();
        assertThat(offline.path("list").isArray()).isTrue();
        offline.path("list").forEach(c ->
                assertThat(c.path("onlineStatus").asText()).isEqualTo("OFFLINE"));

        String cabNo = fx.at("/device/inspectCabinetNo").asText();
        JsonNode detail = get("/api/ops/cabinets/" + cabNo, opsToken).okData();
        assertThat(detail.at("/cabinet/cabinetNo").asText()).isEqualTo(cabNo);
        assertThat(detail.path("slots").isArray()).isTrue();
        assertThat(detail.path("slots").size()).isGreaterThan(0);
    }

    /** 步骤 4：远程运维——对巡检到的柜机下发 REBOOT 指令（OPS 有 device:command:*）。 */
    @Test @Order(4)
    void step04_send_remote_command() {
        String cabNo = fx.at("/device/inspectCabinetNo").asText();
        JsonNode cmd = post("/api/ops/cabinets/" + cabNo + "/commands",
                fx.at("/device/rebootCommand"), opsToken).okData();
        assertThat(cmd.path("commandId").asText()).startsWith("CMD");
    }

    /** 步骤 5：工单处理——取一条待处理(CREATED)工单派单给工程师；随后重复派单被状态机拒(400)。 */
    @Test @Order(5)
    void step05_dispatch_work_order_and_reject_illegal() {
        JsonNode created = get("/api/ops/work-orders?status=CREATED&page=1&size=1", opsToken).okData();
        assumeTrue(created.path("list").size() > 0, "无待派工单（多次运行后 CREATED 已耗尽）——跳过");
        String woNo = created.path("list").get(0).path("woNo").asText();

        // 契约（contracts/workorder.ts）：dispatch 返回工单富行（不是旧 {ok:true}）
        JsonNode row = post("/api/ops/work-orders/" + woNo + "/dispatch",
                Map.of("assignee", "Ahmed Field-Eng"), opsToken).okData();
        assertThat(row.path("woNo").asText()).isEqualTo(woNo);
        assertThat(row.path("status").asText()).isEqualTo("DISPATCHED");
        assertThat(row.path("assigneeName").asText()).isEqualTo("Ahmed Field-Eng");

        // 已 DISPATCHED，再次派单 = 非法迁移 → 400（WoStateMachine 拒绝）
        Resp again = post("/api/ops/work-orders/" + woNo + "/dispatch",
                Map.of("assignee", "Someone-Else"), opsToken);
        assertThat(again.status).isEqualTo(400);
        assertThat(again.code()).isEqualTo(400);
        assertThat(again.msg()).contains("非法迁移");
    }

    /** 步骤 6：网络拓展（BD）——建代理商 → 建站点 → 建点位（固定键 upsert，幂等）。 */
    @Test @Order(6)
    void step06_bd_expand_network() {
        JsonNode exp = fx.get("expansion");

        String agentNo = exp.at("/agent/agentNo").asText();
        JsonNode agent = post("/api/agent/agents/" + agentNo, exp.get("agent"), bdToken).okData();
        assertThat(agent.path("agentNo").asText()).isEqualTo(agentNo);
        assertThat(agent.path("name").asText()).isEqualTo("Gulf Franchise LLC");

        String siteNo = exp.at("/site/siteNo").asText();
        JsonNode site = post("/api/ops/sites/" + siteNo, exp.get("site"), bdToken).okData();
        assertThat(site.path("siteNo").asText()).isEqualTo(siteNo);
        assertThat(site.path("regionId").asText()).isEqualTo("Dubai Marina");

        String locNo = exp.at("/location/locationNo").asText();
        JsonNode loc = post("/api/ops/locations/" + locNo, exp.get("location"), bdToken).okData();
        assertThat(loc.path("locationNo").asText()).isEqualTo(locNo);

        // 复查：新站点可被关键词检索到（走 MariaDB，重启存活）
        JsonNode found = get("/api/ops/sites?keyword=JBR&page=1&size=20", bdToken).okData();
        boolean hit = false;
        for (JsonNode s : found.path("list")) {
            if (siteNo.equals(s.path("siteNo").asText())) hit = true;
        }
        assertThat(hit).as("新建站点 %s 应可检索", siteNo).isTrue();
    }

    /**
     * 步骤 7：财务——申请一笔提现并审核通过。
     *
     * <p>原来是「列表取第一条直接审」。提现端点从内存实现迁到落库版（合规四件套）后这不再成立：
     * 列表里可能全是已审过的单，再审一次就是非法迁移（PAYING --APPROVE--> ?）。
     * 改为自己申请一笔再审，既幂等又顺带覆盖了申请流程。
     * 出参也从 {@code OkResult} 变成 {@code Withdrawal}（前端契约本就要这个形状）。
     */
    @Test @Order(7)
    void step07_finance_audit_withdrawal() {
        Map<String, Object> req = new HashMap<>();
        req.put("payeeType", "AGENT");
        req.put("payeeNo", "AG002");
        req.put("payeeName", "日常流程测试");
        req.put("amount", 500);
        req.put("currency", "AED");
        String no = post("/api/trade/withdrawals", req, financeToken).okData().path("withdrawNo").asText();
        assertThat(no).startsWith("WD");

        JsonNode w = post("/api/trade/withdrawals/" + no + "/audit",
                Map.of("approve", true), financeToken).okData();
        assertThat(w.path("status").asText()).isEqualTo("PAYING");
        assertThat(w.path("auditorName").asText()).as("审批人由服务端回填").isNotBlank();
    }
}
