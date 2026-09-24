package ai.neargo.sharehub.trace;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code %X{actor}} 真的会被填上吗。
 *
 * <h2>为什么这条值得单独测</h2>
 * {@link MdcActorFilter} 依赖一个**没有任何东西保证**的前提：它必须排在认证之后。
 * 排错了的症状是 actor 永远为空 —— 而日志模板写的是 {@code %X{actor:-}}，
 * 空值原样输出成空，<b>不报错、不告警、看日志的人也只会以为这次请求恰好没登录</b>。
 * 这类「配了等于没配」的东西只能靠一条真请求来证伪。
 *
 * <h2>为什么要一个测试专用端点</h2>
 * MDC 在请求结束时就被清了，测试线程读不到。要证明「请求进行中 actor 是对的」，
 * 只能在请求里面读。用现成接口的话得先找一个必然会打日志的接口，
 * 而那等于把这条测试绑在别人的日志语句上 —— 对方哪天不打了，这里会悄悄变成空测。
 */
@Import(MdcActorFilterOrderTest.Probe.class)
class MdcActorFilterOrderTest extends ApiTestSupport {

    @Test
    void an_authenticated_request_carries_the_actor_in_mdc() {
        String admin = login("ADMIN");

        JsonNode actor = get("/api/__mdc-probe", admin).okData().path("actor");

        // 先判节点再取值。`asText()` 在 JSON null 上返回的是**字符串 "null"**，
        // 于是 isNotBlank() 会通过 —— 顺序错的那次反向验证就是这么险些糊弄过去的。
        assertThat(actor.isNull() || actor.isMissingNode())
                .as("actor 没填上，说明 MdcActorFilter 排在了认证前面 —— 而那个错误不会有任何报错")
                .isFalse();
        assertThat(actor.asText())
                .as("格式是 名字(编号)，两样都要：只有编号得再查一次库，只有名字分不清重名")
                .contains("(").endsWith(")");
    }

    @Test
    void the_actor_is_gone_once_the_request_is_over() {
        // 线程池会复用线程。不清的话下一个请求的日志会**指认上一个人** ——
        // 那比少一个字段严重得多。
        String admin = login("ADMIN");
        get("/api/__mdc-probe", admin).okData();

        assertThat(MDC.get(MdcActorFilter.MDC_KEY)).isNull();
    }

    /** 只在本测试里注册。返回请求处理中途的 MDC 值 —— 这是唯一能从外面看见它的办法。 */
    @TestConfiguration
    @RestController
    static class Probe {
        /**
         * 包一层记录而不是直接返回 {@code String}：{@code ApiResponseWrapper} 会把返回值
         * 裹进 {@code Result}，而裸 String 的返回类型让 Spring 选了
         * {@code StringHttpMessageConverter}，它拿到 Result 就 ClassCastException → 500。
         */
        record Actor(String actor) {
        }

        @GetMapping("/api/__mdc-probe")
        public Actor actor() {
            return new Actor(MDC.get(MdcActorFilter.MDC_KEY));
        }
    }
}
