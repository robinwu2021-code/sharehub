package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.event.dedup.EventIdempotency;
import ai.neargo.sharehub.svc.InternalClient;
import ai.neargo.sharehub.svc.ServiceLocator;
import ai.neargo.sharehub.svc.ServiceName;
import ai.neargo.sharehub.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨进程地基（B7 · TDD-cross-process-foundation）。
 *
 * <p>**没有配服务凭证**（属性里显式留空）：本类要验的正是「没配密钥时是拒绝而不是放行」，
 * 以及 {@code InternalClient} 把「没配地址」与「连不上」分开这件事。
 */
@SpringBootTest(properties = {
        "sharehub.dev-mode.enabled=false",
        "sharehub.services.internal-token=",
        "sharehub.outbox.dispatch.enabled=false",   // 轮询由测试显式驱动，不让后台线程插手
})
class CrossProcessFoundationTest {

    private static final String HANDLER = "TestHandler";

    @Autowired
    EventIdempotency dedup;

    @Autowired
    InternalClient internalClient;

    @Autowired
    ServiceLocator locator;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ai.neargo.sharehub.svc.InternalTokenFilter filter;

    private String eventNo;

    @BeforeEach
    void freshEvent() {
        eventNo = "EVTTEST" + System.nanoTime();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM sys_event_consumed WHERE event_no = ?", eventNo);
        TraceContext.clear();
    }

    // ───────────────────────── 消费去重 ─────────────────────────

    @Test
    @DisplayName("同一事件重复投递，业务只执行一次")
    void duplicateDeliveryRunsActionOnce() {
        AtomicInteger runs = new AtomicInteger();

        boolean first = dedup.once(eventNo, HANDLER, "TEST_EVENT", runs::incrementAndGet);
        boolean second = dedup.once(eventNo, HANDLER, "TEST_EVENT", runs::incrementAndGet);

        assertThat(first).as("第一次应当真的执行").isTrue();
        assertThat(second).as("第二次应当被去重跳过").isFalse();
        assertThat(runs.get()).as("业务动作只能跑一次").isEqualTo(1);
    }

    @Test
    @DisplayName("同一事件的不同消费者各自都要执行")
    void differentHandlersEachRun() {
        AtomicInteger runs = new AtomicInteger();

        dedup.once(eventNo, "HandlerA", "TEST_EVENT", runs::incrementAndGet);
        dedup.once(eventNo, "HandlerB", "TEST_EVENT", runs::incrementAndGet);

        assertThat(runs.get())
                .as("去重键是 (event_no, handler)；只按 event_no 去重会让第二个消费者永远收不到事件")
                .isEqualTo(2);
        jdbc.update("DELETE FROM sys_event_consumed WHERE event_no = ?", eventNo);
    }

    @Test
    @DisplayName("业务失败时去重记录一起回滚（否则事件永远不会被处理）")
    void failedActionRollsBackDedupRecord() {
        AtomicInteger runs = new AtomicInteger();

        try {
            dedup.once(eventNo, HANDLER, "TEST_EVENT", () -> {
                runs.incrementAndGet();
                throw new IllegalStateException("消费失败");
            });
        } catch (IllegalStateException expected) {
            // 预期：异常要抛出去，让投递方知道这次失败了
        }

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_event_consumed WHERE event_no = ? AND handler = ?",
                Integer.class, eventNo, HANDLER);
        assertThat(rows)
                .as("去重记录必须随业务一起回滚 —— 否则这个事件永远不会被重新处理，且没有任何症状")
                .isZero();

        // 回滚之后还能重投并成功
        boolean retried = dedup.once(eventNo, HANDLER, "TEST_EVENT", runs::incrementAndGet);
        assertThat(retried).isTrue();
        assertThat(runs.get()).isEqualTo(2);
    }

    // ───────────────────────── 服务凭证 ─────────────────────────

    @Test
    @DisplayName("没配密钥时内部调用拒绝，且与「连不上」分开")
    void missingTokenIsNotConfiguredNotUnreachable() {
        // 先给一个地址，把「没配地址」这个原因排除掉，确保拒绝确实来自缺密钥
        locator.getTargets().put(ServiceName.SHAREHUB, "http://127.0.0.1:9");

        InternalClient.Result r = internalClient.post(ServiceName.SHAREHUB, "/internal/ping", "{}", 1);

        assertThat(r.outcome())
                .as("缺密钥是配置问题，不会自己好；混进 UNREACHABLE 会让人干等对方恢复")
                .isEqualTo(InternalClient.Outcome.NOT_CONFIGURED);
        assertThat(r.message()).contains("internal-token");
        locator.getTargets().remove(ServiceName.SHAREHUB);
    }

    @Test
    @DisplayName("没配地址时返回 NOT_CONFIGURED，并指出是哪个配置键")
    void missingTargetIsNotConfigured() {
        InternalClient.Result r = internalClient.get(ServiceName.GATEWAY, "/internal/ping", 1);

        assertThat(r.outcome()).isEqualTo(InternalClient.Outcome.NOT_CONFIGURED);
        assertThat(r.message())
                .as("报错要能直接指向要改的那一行配置")
                .contains("sharehub.services.targets.GATEWAY");
    }

    @Test
    @DisplayName("凭证只圈跨进程新路径，不误伤仍在用员工令牌的历史业务端点")
    void filterScopeCoversOnlyCrossProcessPaths() {
        // 这条守的是一次真实的事故预演：最初写成 /internal/** 一刀切，
        // 会立刻打断 ops-web 正在用的 /internal/gw/vendors 与 /internal/user/credit/blacklist。
        assertThat(filter.appliesTo("/internal/events/device"))
                .as("跨进程事件必须过凭证闸门").isTrue();
        assertThat(filter.appliesTo("/internal/ping"))
                .as("连通性探测属跨进程").isTrue();

        for (String legacy : new String[]{
                "/internal/gw/vendors",                 // ops-web 供应商页
                "/internal/user/credit/blacklist",      // ops-web 拉黑
                "/internal/trade/orders/ORD1/return",   // 归还链路
        }) {
            assertThat(filter.appliesTo(legacy))
                    .as("历史业务端点 %s 仍走员工令牌，迁到 /api/platform/... 之前不能断", legacy)
                    .isFalse();
        }
    }

    // ───────────────────────── 链路追踪 ─────────────────────────

    @Test
    @DisplayName("入站带合法 traceparent 就沿用")
    void adoptsInboundTraceparent() {
        String inbound = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        assertThat(TraceContext.adopt(inbound)).isEqualTo(inbound);
        assertThat(TraceContext.currentTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("入站没有或格式不合法就生成新的，而不是拒绝请求")
    void generatesWhenInboundMissingOrMalformed() {
        String generated = TraceContext.adopt(null);
        assertThat(generated).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

        TraceContext.clear();
        String fromGarbage = TraceContext.adopt("不是-合法的-traceparent");
        assertThat(fromGarbage)
                .as("链路标识是可观测性设施，不该成为业务请求失败的原因")
                .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    }

    @Test
    @DisplayName("clear 之后不留残留（线程复用会把上个请求的链路带给下一个）")
    void clearLeavesNothingBehind() {
        TraceContext.adopt(null);
        assertThat(TraceContext.currentTraceId()).isNotNull();

        TraceContext.clear();
        assertThat(TraceContext.currentTraceId())
                .as("残留的链路比没有链路更糟 —— 它是错的而不是空的")
                .isNull();
    }
}
