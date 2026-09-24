package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入驻申请的端到端回归（ADR-030 §三）。
 *
 * <h2>为什么必须是集成测试</h2>
 * mock 侧已有 20 条（{@code ops-web/lib/mock/db/apply.test.ts}），但那验的是 <b>mock 自己的实现</b>。
 * 两边逻辑各写了一遍，谁也没验过对方。真正只有在真实 DB 上才验得到的是三件事：
 *
 * <ol>
 *   <li><b>激活派生的事务边界</b> —— 通过之后主体、自然人、属主成员关系行要么全有要么全无；</li>
 *   <li><b>生成列 {@code active_key} 的实际行为</b> —— 「同手机号至多一张在途」是 DB 强制的，
 *       不是应用层拼出来的；</li>
 *   <li><b>两个提交端点的判权差异</b> —— 自助免鉴权、代建判 {@code agent:apply:create}。</li>
 * </ol>
 *
 * <h2>为什么每条用例都用随机手机号</h2>
 * 测试库不会在用例之间重置。用固定号的话，第二次跑就会撞上自己上一次留下的在途申请 ——
 * 那种「第一次绿、第二次红」的测试比没有测试更糟。
 */
@org.springframework.test.context.TestPropertySource(properties = {
        // ⚠️ **把限流调高，而不是关掉**：关掉的话「限流是否真的挂在这两个端点上」就没人验了。
        // 调高到一个测试跑不满的数，逻辑仍在链路上。
        //
        // 不调的话整批用例会撞 429 —— 十几个用例都从 localhost 发码 + 提交，
        // 十次/分钟的闸三四个用例就用完了。第一次跑绿、第二次全红，而代码一行没改。
        "sharehub.security.apply-rate-per-minute=10000",
})
class AgentApplyFlowTest extends ApiTestSupport {

    /** dev-mode 下的固定验证码（{@code OtpService.DEV_MASTER}）。 */
    private static final String OTP = "000000";

    private static String randomPhone() {
        return "+97150" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_000_000));
    }

    private static Map<String, Object> body(String phone, String otp, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("phone", phone);
        b.put("otp", otp);
        b.put("email", "t" + Math.abs(phone.hashCode()) + "@example.com");
        b.put("operatorName", name);
        b.put("operatorType", "AGENT");
        return b;
    }

    /** 自助提交一条，返回申请单号。 */
    private String submitSelfService(String phone, String name) {
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();
        return post("/api/agent/apply", body(phone, OTP, name), null)
                .okData().path("applyNo").asText();
    }

    /**
     * 按编号过滤，别拉一页再翻。{@code agt_apply} 已经 456 行、页大小 200 ——
     * 这里之所以还找得到，只是因为列表按 id 倒序、刚建的那条恰好落在第一页。
     * 谁改一次排序，这个用例就会报「申请没找到」，而真实原因是分页。
     */
    private JsonNode findApply(String applyNo, String token) {
        JsonNode page = get("/api/agent/applies?page=1&size=200&keyword=" + applyNo, token).okData();
        for (JsonNode r : page.path("list")) {
            if (applyNo.equals(r.path("applyNo").asText())) return r;
        }
        return null;
    }

    // ——————————————————— 自助入口 ———————————————————

    @Test
    void self_service_endpoint_needs_no_token_but_does_need_a_valid_code() {
        String phone = randomPhone();
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();

        // 错码必须被拒 —— 否则这个免鉴权端点等于对所有人敞开
        Resp bad = post("/api/agent/apply", body(phone, "123456", "错码测试"), null);
        assertThat(bad.code()).as("错误验证码应被拒: %s", bad.msg()).isNotEqualTo(0);

        // 对码放行，且**全程没有令牌**
        String applyNo = post("/api/agent/apply", body(phone, OTP, "自助提交测试"), null)
                .okData().path("applyNo").asText();
        assertThat(applyNo).startsWith("AP");
    }

    @Test
    void source_is_decided_by_the_route_not_by_the_request_body() {
        String phone = randomPhone();
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();

        // 即便请求体里塞 source，也不该被采纳 —— 否则自助申请可以自称代建，绕开 OTP 与限流
        Map<String, Object> forged = body(phone, OTP, "伪造来源测试");
        forged.put("source", "OPS_CREATED");
        String applyNo = post("/api/agent/apply", forged, null).okData().path("applyNo").asText();

        JsonNode row = findApply(applyNo, login("ADMIN"));
        assertThat(row).as("提交的申请应出现在待办队列里").isNotNull();
        assertThat(row.path("source").asText())
                .as("source 由路由决定，请求体里的值一概不认")
                .isEqualTo("SELF_SERVICE");
    }

    @Test
    void contact_details_are_returned_masked_only() {
        String phone = randomPhone();
        String applyNo = submitSelfService(phone, "掩码检查");
        JsonNode row = findApply(applyNo, login("ADMIN"));

        String mask = row.path("phoneMask").asText();
        assertThat(mask).as("只给掩码").contains("****");
        assertThat(mask).as("明文一个字符都不该出现在出参里")
                .isNotEqualTo(phone.replace("+", ""));
        // 内部字段不得外泄
        assertThat(row.has("phoneHash")).as("hash 是登录键，不该出现在任何出参里").isFalse();
        assertThat(row.has("phoneEnc")).isFalse();
    }

    // ——————————————————— 生成列：同手机号至多一张在途 ———————————————————

    @Test
    void db_rejects_a_second_in_flight_application_for_the_same_phone() {
        String phone = randomPhone();
        submitSelfService(phone, "第一张");

        post("/api/agent/apply/otp", Map.of("phone", phone), null);
        Resp second = post("/api/agent/apply", body(phone, OTP, "第二张"), null);
        assertThat(second.code())
                .as("同手机号第二张在途应被拒（生成列 active_key）: %s", second.msg())
                .isNotEqualTo(0);
    }

    @Test
    void a_rejected_application_frees_the_phone_for_resubmission() {
        String admin = login("ADMIN");
        String phone = randomPhone();
        String first = submitSelfService(phone, "会被驳回的");

        post("/api/agent/applies/" + first + "/audit",
                Map.of("approve", false, "rejectReason", "材料不全"), admin).okData();

        // 终态之后 active_key 变成 apply_no，同手机号可以再提 —— 驳回重提是正常路径
        post("/api/agent/apply/otp", Map.of("phone", phone), null);
        Resp again = post("/api/agent/apply", body(phone, OTP, "重新提交"), null);
        assertThat(again.code()).as("驳回后应能重提: %s", again.msg()).isEqualTo(0);
    }

    // ——————————————————— 状态机 ———————————————————

    @Test
    void state_machine_is_enforced_by_the_server() {
        String admin = login("ADMIN");
        String applyNo = submitSelfService(randomPhone(), "状态机测试");

        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        // 已在审核中，再受理一次要被拒
        Resp twice = post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin);
        assertThat(twice.code()).as("重复受理应被拒: %s", twice.msg()).isNotEqualTo(0);

        post("/api/agent/applies/" + applyNo + "/audit",
                Map.of("approve", false, "rejectReason", "不合格"), admin).okData();
        // 终态之后不能再审 —— 否则审核人看不出第二次改了什么
        Resp afterFinal = post("/api/agent/applies/" + applyNo + "/audit",
                Map.of("approve", true), admin);
        assertThat(afterFinal.code()).as("已终态不应再受理审核: %s", afterFinal.msg()).isNotEqualTo(0);
    }

    @Test
    void rejecting_without_a_reason_is_refused() {
        String admin = login("ADMIN");
        String applyNo = submitSelfService(randomPhone(), "驳回无原因");
        Resp r = post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", false), admin);
        assertThat(r.code()).as("驳回必须带原因（它要原样回显给申请人）: %s", r.msg()).isNotEqualTo(0);
    }

    // ——————————————————— 激活派生 ———————————————————

    @Test
    void approving_derives_an_operator_that_is_immediately_visible() {
        String admin = login("ADMIN");
        String applyNo = submitSelfService(randomPhone(), "派生检查公司");

        JsonNode result = post("/api/agent/applies/" + applyNo + "/audit",
                Map.of("approve", true, "shareRate", 0.33, "regionScope", "迪拜"), admin).okData();

        String operatorNo = result.path("operatorNo").asText();
        assertThat(operatorNo).as("通过后必须回写主体号").startsWith("AG");

        /*
         * 关键断言：主体要**立刻能从代理商档案查到**。
         * 只断言「接口返回了 operatorNo」是不够的 —— 派生写在同一个事务里，
         * 若事务边界错了（比如主体插入后异常但没回滚），返回值照样是对的，
         * 而档案里查无此人。这种不一致只有跨接口查一次才暴露得出来。
         */
        // 按编号过滤，**不要拉全表再翻**：库里代理商已经 215 个，
        // 翻第一页 200 条时新建的那个正好掉到了页外，于是这条断言从
        // 「派生没生效」变成了「分页没够着」—— 两种失败长得一模一样。
        JsonNode agents = get("/api/agent/agents?page=1&size=200&keyword=" + operatorNo, admin).okData();
        boolean found = false;
        for (JsonNode a : agents.path("list")) {
            if (operatorNo.equals(a.path("agentNo").asText())) {
                found = true;
                assertThat(a.path("name").asText()).isEqualTo("派生检查公司");
                assertThat(a.path("status").asText()).isEqualTo("ENABLED");
            }
        }
        assertThat(found).as("审核通过派生出的主体 %s 应能在代理商档案里查到", operatorNo).isTrue();
    }

    @Test
    void the_same_phone_may_apply_for_a_second_operator() {
        String admin = login("ADMIN");
        String phone = randomPhone();

        String first = submitSelfService(phone, "第一个主体");
        post("/api/agent/applies/" + first + "/audit", Map.of("approve", true), admin).okData();

        // 这条是 ADR-030 的核心：手机号已存在**不是重复注册**，是多主体申请。
        // 把它写反（当成重复拒掉），用户会卡死在注册页而没有任何出路。
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();
        Resp second = post("/api/agent/apply", body(phone, OTP, "第二个主体"), null);
        assertThat(second.code())
                .as("已有主体的手机号仍应能申请第二个主体: %s", second.msg())
                .isEqualTo(0);

        JsonNode row = findApply(second.okData().path("applyNo").asText(), admin);
        assertThat(row.path("phoneAlreadyKnown").asBoolean())
                .as("审核台要看得出这是多主体申请").isTrue();
    }

    // ——————————————————— 判权 ———————————————————

    @Test
    void ops_created_endpoint_requires_the_create_permission() {
        // VIEWER 没有 agent:apply:create —— 代建是受控动作，它替商家做主且跳过 OTP
        Resp denied = post("/api/agent/applies",
                body(randomPhone(), null, "越权代建"), login("VIEWER"));
        assertThat(denied.status).as("无 agent:apply:create 应被拒").isIn(403, 200);
        if (denied.status == 200) {
            assertThat(denied.code()).as("业务码也应非 0: %s", denied.msg()).isNotEqualTo(0);
        }
    }

    @Test
    void ops_created_needs_no_otp_because_the_staff_token_vouches_for_it() {
        String admin = login("ADMIN");
        String phone = randomPhone();
        // 代建不传 otp：经办员工的令牌已经背书，他面对的是线下签好约的商家
        Map<String, Object> b = body(phone, null, "代建免码测试");
        b.remove("otp");
        String applyNo = post("/api/agent/applies", b, admin).okData().path("applyNo").asText();

        assertThat(findApply(applyNo, admin).path("source").asText()).isEqualTo("OPS_CREATED");
    }

    @Test
    void reading_the_queue_requires_the_read_permission() {
        Resp r = get("/api/agent/applies?page=1&size=10", login("CS"));
        // CS 没有 agent:apply:read
        assertThat(r.status == 403 || r.code() != 0)
                .as("无 agent:apply:read 不应看到入驻队列（status=%d, msg=%s）", r.status, r.msg())
                .isTrue();
    }

    // ——————————————————— 申请人查进度 ———————————————————

    @Test
    void the_applicant_can_read_back_the_rejection_reason() {
        String admin = login("ADMIN");
        String phone = randomPhone();
        String applyNo = submitSelfService(phone, "回显检查");

        post("/api/agent/applies/" + applyNo + "/audit",
                Map.of("approve", false, "rejectReason", "营业执照与主体名称不一致"), admin).okData();

        // 免鉴权，凭手机号 + OTP。这一页存在的全部意义就是让人知道自己错在哪
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();
        JsonNode mine = get("/api/agent/apply/mine?phone=" + phone.replace("+", "%2B") + "&otp=" + OTP, null)
                .okData();

        assertThat(mine.path("rejectReason").asText()).isEqualTo("营业执照与主体名称不一致");
        assertThat(mine.path("status").asText()).isEqualTo("REJECTED");
        // 申请人视角不该拿到任何内部字段
        assertThat(mine.has("principalNo")).isFalse();
        assertThat(mine.has("source")).isFalse();
    }
}
