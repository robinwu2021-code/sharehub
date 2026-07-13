package ai.neargo.powerbank.scenario;

import ai.neargo.powerbank.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限矩阵：验证「授权只在 Controller @PreAuthorize、按角色权限码放行/拒绝」。
 * 未认证 → 401；已认证但缺权限码 → 403；有权限码 → 200（code:0）。
 * 均选无副作用/幂等端点（下发指令为骨架空操作；派单越权在 @PreAuthorize 前被拒不触状态机）。
 */
class RbacMatrixTest extends ApiTestSupport {

    private static final String CAB = "CAB1000";
    private static final String WO = "WO70000";
    private static final String WD = "WD3000";

    // —— 未认证 → 401 ——

    @Test
    void anonymous_read_is_unauthorized() {
        assertThat(get("/api/ops/dashboard", null).status).isEqualTo(401);
    }

    @Test
    void anonymous_write_is_unauthorized() {
        assertThat(post("/api/ops/cabinets/" + CAB + "/commands", Map.of("type", "REBOOT"), null).status)
                .isEqualTo(401);
    }

    // —— 已认证但缺权限 → 403 ——

    @Test
    void viewer_cannot_dispatch_work_order() {
        Resp r = post("/api/ops/work-orders/" + WO + "/dispatch", Map.of("assignee", "x"), login("VIEWER"));
        assertThat(r.status).isEqualTo(403);
        assertThat(r.code()).isEqualTo(403);
    }

    @Test
    void ops_cannot_create_site() {
        // OPS 有 location:poi:read 但无 location:poi:create。
        // 注意：@PreAuthorize 在请求体反序列化之后执行，故须传完整可反序列化的 Site（含原始 int 字段），
        // 否则会先因 null→int 反序列化失败返 500，测不到鉴权。
        Resp r = post("/api/ops/sites/ST-RBAC-DENY", fullSite(), login("OPS"));
        assertThat(r.status).isEqualTo(403);
        assertThat(r.code()).isEqualTo(403);
    }

    private static Map<String, Object> fullSite() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("siteNo", "ST-RBAC-DENY");
        m.put("name", "Denied Site");
        m.put("venueName", "N/A");
        m.put("agentNo", "");
        m.put("regionId", "Dubai");
        m.put("address", "nowhere");
        m.put("sceneType", "商场");
        m.put("pointCount", 0);
        m.put("cabinetCount", 0);
        m.put("status", "ACTIVE");
        return m;
    }

    @Test
    void finance_cannot_send_device_command() {
        // FINANCE 无 device:command:send
        Resp r = post("/api/ops/cabinets/" + CAB + "/commands", Map.of("type", "REBOOT"), login("FINANCE"));
        assertThat(r.status).isEqualTo(403);
    }

    @Test
    void cs_cannot_audit_withdrawal() {
        // CS 无 finance:withdrawal:audit
        Resp r = post("/api/trade/withdrawals/" + WD + "/audit", Map.of("approve", true), login("CS"));
        assertThat(r.status).isEqualTo(403);
    }

    // —— 有权限 → 200 ——

    @Test
    void ops_can_send_device_command() {
        // OPS 有 device:command:*
        post("/api/ops/cabinets/" + CAB + "/commands", Map.of("type", "REBOOT"), login("OPS")).okData();
    }

    @Test
    void finance_can_audit_withdrawal() {
        // FINANCE 有 finance:*
        post("/api/trade/withdrawals/" + WD + "/audit", Map.of("approve", true), login("FINANCE")).okData();
    }

    @Test
    void admin_can_do_everything() {
        String admin = login("ADMIN");
        post("/api/ops/cabinets/" + CAB + "/commands", Map.of("type", "REBOOT"), admin).okData();
        post("/api/agent/agents/AG-RBAC-ADMIN",
                Map.of("name", "Admin Made Co", "contact", "+9710000", "regionScope", "Dubai",
                        "shareRate", 0.1, "cabinetCount", 0, "status", "ENABLED"), admin).okData();
    }
}
