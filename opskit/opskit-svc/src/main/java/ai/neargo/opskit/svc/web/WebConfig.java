package ai.neargo.opskit.svc.web;

import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.core.auth.ActorResolver;
import ai.neargo.opskit.platform.core.auth.MemberTokenAuthenticator;
import java.util.List;
import ai.neargo.opskit.platform.core.idem.IdempotencyStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final CallerResolver caller;

    private final IdempotencyStore idempotency;
    private final OperatorResolver operator;

    public WebConfig(AppAppService apps, ActorResolver actors, MemberTokenAuthenticator members, IdempotencyStore idempotency,
            ai.neargo.opskit.platform.core.auth.OperatorAuth operators) {
        this.caller = new CallerResolver(apps, actors, members, idempotency);
        this.idempotency = idempotency;
        this.operator = new OperatorResolver(operators, idempotency);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(caller);
        resolvers.add(operator);
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> r = new FilterRegistrationBean<>(new TraceIdFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }

    /** 排在 traceId 之后：幂等放弃 / 请求体过大的日志要带 traceId。 */
    @Bean
    FilterRegistrationBean<WriteRequestFilter> writeRequestFilter() {
        FilterRegistrationBean<WriteRequestFilter> r = new FilterRegistrationBean<>(new WriteRequestFilter(idempotency));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return r;
    }
}
