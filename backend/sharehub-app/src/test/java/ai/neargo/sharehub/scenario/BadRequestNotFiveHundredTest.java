package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **请求本身不合法要回 4xx，不能回 500**。
 *
 * <h3>为什么要有这一条</h3>
 * 2026-09-25 上线后实测：`GET /api/ops/qc-records` 少传必填的 `itemNo`，
 * 返回 **500「服务器错误」** 外加一条带堆栈的 ERROR 日志 ——
 * 「前端漏传一个参数」和「后端挂了」长得一模一样。
 * 运维会去翻后端日志、怀疑刚上线的版本，而真正该改的是调用方；
 * 同时 ERROR 级别贬值（这类根本不需要人介入，却和真故障混在一起）。
 *
 * <p>根因：`GlobalExceptionHandler` 没有处理 Spring MVC 的请求绑定异常族，
 * 它们全落进兜底的 `@ExceptionHandler(Exception.class)`。
 * 这与该类注释里记的「ResponseStatusException 被抹成 500」是同一类问题 —— 当时只修了一半。
 *
 * <h3>这条为什么能拦住回归</h3>
 * 断言的是**状态码**而不是文案：文案可以改、可以翻译，而「参数错了却报 500」这件事
 * 无论文案怎么变都是错的。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BadRequestNotFiveHundredTest extends ApiTestSupport {

    private String admin;

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
    }

    @Test
    @DisplayName("① 缺必填参数 → 400，不是 500（上线实测撞到的那一条）")
    void missing_required_param_is_400() {
        Resp r = get("/api/ops/qc-records", admin);
        assertThat(r.status)
                .as("缺 itemNo 应当是 400；回 500 的话「前端漏传参数」和「后端挂了」分不开")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("② 参数类型不符 → 400")
    void type_mismatch_is_400() {
        Resp r = get("/api/ops/work-orders?page=abc&size=1", admin);
        assertThat(r.status).isEqualTo(400);
    }

    @Test
    @DisplayName("③ 请求体不是合法 JSON → 400")
    void unreadable_body_is_400() {
        Resp r = postRaw("/api/ops/alarm-todos/NOPE/done", "{not-json", admin);
        assertThat(r.status).isEqualTo(400);
    }

    @Test
    @DisplayName("④ 方法不符 → 不是 500（实际是 401：安全层先拦，到不了异常处理器）")
    void wrong_method_is_not_500() {
        Resp r = post("/api/ops/ops-flow-metrics", Map.of(), admin);
        /*
         * 这里**故意不断言 405**。Spring Security 6 对「路径上没有匹配该方法的授权规则」
         * 先返回 401（部署记忆里记过同款现象：不存在的 /api/* 也回 401 而非 404），
         * 请求根本到不了 MVC 的方法匹配，405 handler 自然不会被调用。
         * 断言 405 会让这条用例测的是「安全层碰巧没拦」，而不是它要测的东西。
         * 本条守住的是底线：**调用方的问题不该被记成服务端故障**。
         */
        assertThat(r.status).as("方法不符不该是 500").isNotEqualTo(500);
    }

    @Test
    @DisplayName("⑤ 正对照：参数齐全时该端点本身是好的（否则上面四条可能只是「这个接口坏了」）")
    void the_endpoint_itself_works() {
        Resp r = get("/api/ops/qc-records?itemNo=NOSUCH", admin);
        assertThat(r.status).isEqualTo(200);
    }
}
