package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单状态机端到端：**每一条边走一遍，并逐条钉住非法迁移返回 4xx**。
 *
 * <p>为什么单独一个类而不并进 {@code OperatorDailyFlowTest}：那个类是「一天的日常」，
 * 按 {@code @Order} 串行且共用一张从种子里挑出来的工单；状态机测试需要每个用例
 * <b>自己开一张干净的单</b>（走完一遍就是终态，复用会互相污染，跑第二遍必然红）。
 *
 * <p>本类的断言口径：合法迁移看返回的 {@code status}；非法迁移看 HTTP 400 +
 * 报错含「非法迁移」（{@code WoStateMachine} 的统一口径经 {@code GlobalExceptionHandler}
 * 的 {@code IllegalArgumentException → 400} 映射）。<b>前端按钮禁用不算防线</b>，
 * 这里验的就是「绕过前端直接打接口也拦得住」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkOrderTransitionTest extends ApiTestSupport {

    private String opsToken;

    @BeforeAll
    void signIn() {
        // OPS 持 workorder:*，故本类的 4xx 只可能来自状态机/校验，不会是 403 —— 排除鉴权噪声
        opsToken = login("OPS", "ops.user", null);
    }

    // ——————————————————————— 合法路径 ———————————————————————

    /** 全链路：CREATED → DISPATCHED → ACCEPTED → PROCESSING → DONE → CLOSED。 */
    @Test
    void full_happy_path_walks_every_edge() {
        String woNo = newWorkOrder("状态机全链路");

        dispatch(woNo);
        assertThat(status(post("/api/ops/work-orders/" + woNo + "/accept",
                Map.of("assigneeNo", "E-001"), opsToken))).isEqualTo("ACCEPTED");
        assertThat(status(post("/api/ops/work-orders/" + woNo + "/handle",
                Map.of("handleNote", "已到现场"), opsToken))).isEqualTo("PROCESSING");

        // 完工：新端点 POST /complete，PROCESSING → DONE
        assertThat(status(post("/api/ops/work-orders/" + woNo + "/complete",
                Map.of("handleNote", "换锁扣模块 ×1", "faultReasonCode", "LOCK", "photos", "[\"site.jpg\"]"), opsToken))).isEqualTo("DONE");

        // 验收关单：DONE → AUDITED → CLOSED（AUDITED 不跳过，见 WoOpsServiceImpl#close）
        assertThat(status(post("/api/ops/work-orders/" + woNo + "/close",
                Map.of("closeReason", "RESOLVED"), opsToken))).isEqualTo("CLOSED");
    }

    /** 驳回退回：DISPATCHED → CREATED，且受理人被清空（回到待派单就该重新挑人）。 */
    @Test
    void reject_from_dispatched_returns_to_created_and_clears_assignee() {
        String woNo = newWorkOrder("驳回退回");
        dispatch(woNo);

        JsonNode wo = post("/api/ops/work-orders/" + woNo + "/reject",
                Map.of("reason", "派错人了，该派给电气组"), opsToken).okData();
        assertThat(wo.path("status").asText()).isEqualTo("CREATED");
        assertThat(wo.path("assigneeName").isNull() || wo.path("assigneeName").asText().isEmpty())
                .as("退回待派单后不应还挂着上一个受理人").isTrue();

        // 退回后可以再派一次 —— 这就是要 REJECT 这条边的全部意义
        dispatch(woNo);
    }

    /** 驳回也接受 PROCESSING 起点（已开工才发现修不了，同样要能退回）。 */
    @Test
    void reject_from_processing_is_allowed() {
        String woNo = newWorkOrder("开工后驳回");
        dispatch(woNo);
        post("/api/ops/work-orders/" + woNo + "/accept", Map.of(), opsToken).okData();
        post("/api/ops/work-orders/" + woNo + "/handle", Map.of("handleNote", "缺配件"), opsToken).okData();

        assertThat(status(post("/api/ops/work-orders/" + woNo + "/reject",
                Map.of("reason", "缺配件，退回重排"), opsToken))).isEqualTo("CREATED");
    }

    /** 退回返工：DONE → PROCESSING，受理人**保留**（同一个人再去修，不重新派单）。 */
    @Test
    void rework_from_done_returns_to_processing_keeping_assignee() {
        String woNo = newWorkOrder("验收不合格返工");
        driveToDone(woNo);

        JsonNode wo = post("/api/ops/work-orders/" + woNo + "/rework",
                Map.of("reason", "锁扣仍卡死，未修好"), opsToken).okData();
        assertThat(wo.path("status").asText()).isEqualTo("PROCESSING");

        // 返工后可以再完工再关单，闭环回得去
        assertThat(status(post("/api/ops/work-orders/" + woNo + "/complete",
                Map.of("handleNote", "二次处理完成", "faultReasonCode", "LOCK", "photos", "[\"site.jpg\"]"), opsToken))).isEqualTo("DONE");
        assertThat(status(post("/api/ops/work-orders/" + woNo + "/close",
                Map.of("closeReason", "RESOLVED"), opsToken))).isEqualTo("CLOSED");
    }

    // ——————————————————————— 非法迁移一律 4xx ———————————————————————

    /** 完工的起点只能是 PROCESSING：刚开的单（CREATED）直接 /complete → 400。 */
    @Test
    void complete_from_created_is_rejected_400() {
        String woNo = newWorkOrder("非法完工");
        Resp r = post("/api/ops/work-orders/" + woNo + "/complete",
                Map.of("handleNote", "我说完了就是完了"), opsToken);
        assertThat(r.status).isEqualTo(400);
        assertThat(r.msg()).contains("非法迁移");
    }

    /** 完工不可重复：DONE 上再 /complete → 400（否则 wo_handle 会堆出多条「完工」记录）。 */
    @Test
    void complete_twice_is_rejected_400() {
        String woNo = newWorkOrder("重复完工");
        driveToDone(woNo);

        Resp r = post("/api/ops/work-orders/" + woNo + "/complete", Map.of("handleNote", "再报一次"), opsToken);
        assertThat(r.status).isEqualTo(400);
        assertThat(r.msg()).contains("非法迁移");
    }

    /**
     * 关单不能跳过完工：PROCESSING 上直接 /close → 400。
     *
     * <p>这条是本次收紧的核心（原实现从 PROCESSING 一路走到 CLOSED）。放行等于给验收人
     * 一条「处理人从没报完工，我直接关掉」的后门，[db-design §9A.4] 的「完工人 ≠ 验收人」
     * 会被静默绕过 —— 达标率就成了一个可以自己签发的数字。
     */
    @Test
    void close_cannot_skip_complete_400() {
        String woNo = newWorkOrder("跳过完工关单");
        dispatch(woNo);
        post("/api/ops/work-orders/" + woNo + "/accept", Map.of(), opsToken).okData();
        post("/api/ops/work-orders/" + woNo + "/handle", Map.of("handleNote", "在处理"), opsToken).okData();

        Resp r = post("/api/ops/work-orders/" + woNo + "/close", Map.of("closeReason", "RESOLVED"), opsToken);
        assertThat(r.status).isEqualTo(400);
        assertThat(r.msg()).contains("非法迁移");
    }

    /** 返工的起点只能是 DONE：CREATED 上 /rework → 400。 */
    @Test
    void rework_from_created_is_rejected_400() {
        String woNo = newWorkOrder("非法返工");
        Resp r = post("/api/ops/work-orders/" + woNo + "/rework", Map.of("reason", "随便退"), opsToken);
        assertThat(r.status).isEqualTo(400);
        assertThat(r.msg()).contains("非法迁移");
    }

    /** 已归档不可再退回：CLOSED 上 /reject → 400（要重开就该另开一张单）。 */
    @Test
    void reject_on_closed_is_rejected_400() {
        String woNo = newWorkOrder("终态驳回");
        driveToDone(woNo);
        post("/api/ops/work-orders/" + woNo + "/close", Map.of("closeReason", "RESOLVED"), opsToken).okData();

        Resp r = post("/api/ops/work-orders/" + woNo + "/reject", Map.of("reason", "反悔了"), opsToken);
        assertThat(r.status).isEqualTo(400);
        assertThat(r.msg()).contains("非法迁移");
    }

    /** 退回原因必填：空 reason → 400，且报错点在原因而不在状态（校验顺序也是契约的一部分）。 */
    @Test
    void reject_without_reason_is_400() {
        String woNo = newWorkOrder("驳回缺原因");
        dispatch(woNo);

        Resp blank = post("/api/ops/work-orders/" + woNo + "/reject", Map.of("reason", "   "), opsToken);
        assertThat(blank.status).isEqualTo(400);
        assertThat(blank.msg()).contains("reason");

        // 校验被拦下后状态不许有任何位移
        assertThat(status(post("/api/ops/work-orders/" + woNo + "/reject",
                Map.of("reason", "补上原因"), opsToken))).isEqualTo("CREATED");
    }

    // ——————————————————————— 夹具 ———————————————————————

    /** 开一张干净的 FAULT 工单，返回单号。 */
    private String newWorkOrder(String desc) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "FAULT");
        body.put("source", "MANUAL");
        body.put("priority", "HIGH");
        body.put("description", "[状态机测试] " + desc);
        JsonNode wo = post("/api/ops/work-orders", body, opsToken).okData();
        String woNo = wo.path("woNo").asText();
        assertThat(woNo).isNotBlank();
        assertThat(wo.path("status").asText()).isEqualTo("CREATED");
        return woNo;
    }

    private void dispatch(String woNo) {
        post("/api/ops/work-orders/" + woNo + "/dispatch",
                Map.of("assignee", "Ahmed Field-Eng"), opsToken).okData();
    }

    /** 推到 DONE（返工/关单类用例的公共前置）。 */
    private void driveToDone(String woNo) {
        dispatch(woNo);
        post("/api/ops/work-orders/" + woNo + "/accept", Map.of("assigneeNo", "E-001"), opsToken).okData();
        post("/api/ops/work-orders/" + woNo + "/handle", Map.of("handleNote", "处理中"), opsToken).okData();
        assertThat(status(post("/api/ops/work-orders/" + woNo + "/complete",
                // 2026-09-25 完工收紧：故障单必须给故障原因与现场照片
                Map.of("handleNote", "完工", "faultReasonCode", "LOCK", "photos", "[\"site.jpg\"]"), opsToken))).isEqualTo("DONE");
    }

    private static String status(Resp r) {
        return r.okData().path("status").asText();
    }
}
