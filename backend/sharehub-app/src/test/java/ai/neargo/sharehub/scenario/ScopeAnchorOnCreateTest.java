package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新产生的单据，代理在自己的后台里看得见吗。
 *
 * <h2>这类缺陷为什么没人发现</h2>
 * 代理能看到哪些订单/工单，靠的是这几张表上的<b>数据范围锚点</b>
 * （{@code agent_no} / {@code site_no} / {@code location_no}，见 {@code DataScopeRegistration}）。
 * 锚点为空时，{@code DataScopeHandler} 注入的 {@code WHERE agent_no = ?} 一行都匹配不上。
 *
 * <p>症状是<b>页面正常渲染、只是行少了</b> —— 不报错、不告警。
 * 代理不会觉得「系统坏了」，只会觉得「我的单怎么还没到」，
 * 而运营那边看得一清二楚，双方对不上话。
 *
 * <h2>为什么测「代理看得见」而不是「列里有值」</h2>
 * 直接查库断言 {@code agent_no} 非空，只能证明写进去了，证明不了它<b>管用</b> ——
 * 锚点列名与数据范围注册表对不上时，列里有值照样查不到。
 * 这里走完整链路：消费者借 → 代理登录 → 在自己的列表里找。
 */
class ScopeAnchorOnCreateTest extends ApiTestSupport {

    /** CAB1005 → LOC205 / ST305 / AG006（种子里这台柜子有明确归属）。 */
    private static final String CABINET_OF_AG006 = "CAB1005";
    private static final String AGENT_OF_CABINET = "AG006";

    /** CAB1001 → LOC201 / ST301 / AG002。 */
    private static final String CABINET_OF_AG002 = "CAB1001";
    private static final String AGENT_OF_CABINET_2 = "AG002";

    private static final String PHONE = "+971500009301";

    @Test
    @DisplayName("★★ 消费者刚借的单，该柜子的代理要能在自己的订单列表里看到")
    void a_new_rent_order_is_visible_to_the_agent_who_operates_that_cabinet() {
        String orderNo = rent(CABINET_OF_AG006);

        assertThat(listContains("/api/trade/orders", "orderNo", orderNo, loginAgent(AGENT_OF_CABINET)))
                .as("""
                        代理在自己的后台里看不到这一单。数据范围按 ord_order.agent_no 过滤，
                        而下单时这一列没有被写入 —— 页面照常渲染，只是少了行，没有任何报错。
                        锚点要在下单时从机柜的归属派生出来。""")
                .isTrue();
    }

    @Test
    @DisplayName("★★ 给某台柜子开的工单，该柜子的代理要能看到")
    void a_new_work_order_is_visible_to_the_agent_who_operates_that_cabinet() {
        String admin = login("ADMIN");
        Map<String, Object> body = new HashMap<>();
        body.put("type", "FAULT");
        body.put("source", "MANUAL");
        body.put("priority", "HIGH");
        body.put("cabinetNo", CABINET_OF_AG002);
        body.put("description", "[数据范围锚点] 开单后代理应看得见");
        String woNo = post("/api/ops/work-orders", body, admin).okData().path("woNo").asText();
        assertThat(woNo).isNotBlank();

        assertThat(listContains("/api/ops/work-orders", "woNo", woNo, loginAgent(AGENT_OF_CABINET_2)))
                .as("""
                        代理看不到给自己柜子开的工单。锚点由调用方在请求体里给 ——
                        而运营端压根不传（ops-web 的 WorkOrderDraft 里没有这三个字段），
                        于是从界面开的工单锚点一律为空。
                        锚点是**服务端该派生的东西**，不该采信调用方 ——
                        本仓库对机柜早就是这个规矩（CabinetServiceImpl：siteNo/agentNo 一律反查）。""")
                .isTrue();
    }

    @Test
    @DisplayName("★★ 请求体里塞别人的 agentNo 不算数——锚点只认机柜的真实归属")
    void a_forged_agent_in_the_draft_does_not_decide_who_sees_the_work_order() {
        // 锚点决定「谁看得见」。采信调用方传的值，等于让他把工单塞进任意代理的视野，
        // 或者从该看到的人眼前藏起来。本仓库对机柜早就是这个规矩
        // （CabinetServiceImpl：siteNo/agentNo 一律反查）。
        String admin = login("ADMIN");
        Map<String, Object> body = new HashMap<>();
        body.put("type", "FAULT");
        body.put("source", "MANUAL");
        body.put("priority", "HIGH");
        body.put("cabinetNo", CABINET_OF_AG002);       // 这台柜子真正归 AG002
        body.put("agentNo", AGENT_OF_CABINET);         // 却声称归 AG006
        body.put("siteNo", "ST305");
        body.put("description", "[数据范围锚点] 伪造归属应被忽略");
        String woNo = post("/api/ops/work-orders", body, admin).okData().path("woNo").asText();

        assertThat(listContains("/api/ops/work-orders", "woNo", woNo, loginAgent(AGENT_OF_CABINET)))
                .as("请求体声称归 AG006，但柜子是 AG002 的 —— AG006 不该看得到")
                .isFalse();
        assertThat(listContains("/api/ops/work-orders", "woNo", woNo, loginAgent(AGENT_OF_CABINET_2)))
                .as("真正该看到的是柜子的归属方 AG002")
                .isTrue();
    }

    // ——————————————————————— 脚手架 ———————————————————————

    /** C 端 phone_otp 登录后扫码借出，返回单号。 */
    private String rent(String cabinetNo) {
        String otp = post("/mp/auth/otp", Map.of("phone", PHONE), null).okData().path("devCode").asText();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", PHONE, "otp", otp), null)
                .okData().path("token").asText();
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cabinetNo), token)
                .okData().path("orderNo").asText();
        assertThat(orderNo).as("前置：借出应当成功").startsWith("ORD");
        return orderNo;
    }

    private boolean listContains(String path, String key, String value, String token) {
        return findInPages(path, key, value, token) != null;
    }
}
