package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.auth.CallContext;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨服务凭证透传的安全属性（ADR-017 §5.2）。
 *
 * <p><b>为什么透传 token 而不是 X-User-Id/X-Roles</b>：本项目的红线是
 * 「只认 token 反查权限，绝不信客户端身份声明」。若跨服务改传身份声明，
 * <b>能访问 {@code /internal/**} 的任何人都能冒充任意身份</b> —— 红线当场作废。
 * 透传 token 则让对端自己反查，权限判定始终发生在持有 TokenStore 的那一侧。
 */
class CallContextTest extends ApiTestSupport {

    /**
     * 请求处理完毕后 ThreadLocal 必须已清理。
     *
     * <p><b>不清的后果是最危险的一类 bug</b>：线程池复用下，上一个请求的 token
     * 会泄露给下一个请求 —— 表现为「偶发地以别人的身份执行」，
     * 与并发和调度相关，几乎不可复现。
     */
    @Test
    void token_does_not_leak_across_requests() {
        // 先发一个带认证的请求，走完整条过滤器链
        get("/api/ops/dashboard", login("ADMIN"));

        assertThat(CallContext.token())
                .as("请求结束后测试线程不该残留任何 token —— 残留即意味着身份会跨请求泄露")
                .isNull();
    }

    /** 无调用方上下文时返回 null，而不是抛异常 —— 后台任务本就没有身份。 */
    @Test
    void no_context_yields_null_not_exception() {
        CallContext.clear();
        assertThat(CallContext.token()).isNull();
    }

    /** set/clear 的基本语义。 */
    @Test
    void set_then_clear_removes_the_value() {
        CallContext.setToken("stk_test");
        assertThat(CallContext.token()).isEqualTo("stk_test");
        CallContext.clear();
        assertThat(CallContext.token()).isNull();
    }
}
