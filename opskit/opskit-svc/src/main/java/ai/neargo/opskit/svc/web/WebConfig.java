package ai.neargo.opskit.svc.web;

import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.core.auth.ActorResolver;
import ai.neargo.opskit.platform.core.auth.MemberTokenAuthenticator;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final CallerResolver caller;

    public WebConfig(AppAppService apps, ActorResolver actors, MemberTokenAuthenticator members) {
        this.caller = new CallerResolver(apps, actors, members);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(caller);
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> r = new FilterRegistrationBean<>(new TraceIdFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }
}
