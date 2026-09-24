package ai.neargo.sharehub.audit;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.config.AuditTrailInterceptor;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditDetail;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditLogEntry;
import ai.neargo.sharehub.platform.org.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditChanges} 的**线程生命周期** —— 改动不能跨请求漏出去。
 *
 * <h2>为什么必须单测，不能只靠集成用例</h2>
 * 集成用例里，一个正常的写请求会走到 {@code drain()}，而 drain 本身就清空了 ——
 * 于是「有没有别的清理」看不出来。实测过：把 {@code afterCompletion} 的
 * {@code finally} 整段删掉，集成用例<b>照样全绿</b>。
 *
 * <p>真正要防的是<b>走不到 drain 的路径</b>：GET、免审前缀、{@code /mp/**}
 * （那里连拦截器都不挂）。这些路径上若有服务记了改动，值会留在线程上，
 * 等这个线程下次服务一个被审计的请求时被 drain 进去 ——
 * <b>那条审计不是少了信息，是记了别人的改动</b>，而且看起来完全正常。
 *
 * <p>所以直接测生命周期方法：这是唯一能把两道清理分别验证的办法。
 */
class AuditChangesLifecycleTest {

    private final AuditTrailInterceptor interceptor = new AuditTrailInterceptor(new NoopAuditLog());

    @AfterEach
    void cleanUp() {
        AuditChanges.clear();
    }

    @Test
    @DisplayName("★★ 请求开始时清掉上一个请求漏下的改动")
    void pre_handle_drops_whatever_the_previous_request_left_behind() {
        // 模拟：上一个请求（比如 /mp/**，连拦截器都不挂）记了改动却没人 drain
        AuditChanges.record("rate", "0.08", "0.05");
        assertThat(AuditChanges.drain()).as("前置：确实留下了东西").isNotEmpty();
        AuditChanges.record("rate", "0.08", "0.05");

        interceptor.preHandle(new MockHttpServletRequest("POST", "/api/ops/venues"),
                new MockHttpServletResponse(), new Object());

        assertThat(AuditChanges.drain())
                .as("这个请求看到的必须只有自己产生的改动，否则审计会把别人的改动记到它头上")
                .isEmpty();
    }

    @Test
    @DisplayName("走不到 drain 的路径，结束时也要清干净")
    void after_completion_clears_even_when_it_returns_early() {
        // GET 不是写方法 → afterCompletion 立刻 return，走不到 drain()
        AuditChanges.record("rate", "0.08", "0.05");

        interceptor.afterCompletion(new MockHttpServletRequest("GET", "/api/ops/venues"),
                new MockHttpServletResponse(), new Object(), null);

        assertThat(AuditChanges.drain())
                .as("提前 return 的路径不清的话，这些改动会漏给下一个请求")
                .isEmpty();
    }

    /** 什么都不做的审计实现 —— 这里测的是 ThreadLocal 的生死，与写不写库无关。 */
    private static class NoopAuditLog implements AuditLogService {
        @Override
        public PageResult<AuditLogEntry> page(Integer page, Integer size, String keyword,
                                              String actor, String action, String targetType) {
            return new PageResult<>(List.of(), 0L);
        }

        @Override
        public AuditDetail detail(String id) {
            return null;
        }

        @Override
        public void append(Entry entry) {
            // 不落库
        }
    }
}
