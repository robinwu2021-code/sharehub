package ai.neargo.sharehub.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * 国际化（zh/en/ar）：MessageSource（UTF-8 properties）+ 按 {@code Accept-Language} 解析 Locale。
 * 消息 key 见 {@code resources/i18n/messages*.properties}；缺失 key 回退默认(zh) → key 本身。
 * 前端 ops-web 每次请求带 Accept-Language（见 lib/api/http-client.ts）。
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    static final Locale AR = Locale.forLanguageTag("ar");
    static final List<Locale> SUPPORTED = List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, AR);

    private final AuditTrailInterceptor auditTrail;

    public I18nConfig(AuditTrailInterceptor auditTrail) {
        this.auditTrail = auditTrail;
    }

    /**
     * 注册运营端写操作审计拦截器。
     *
     * <p>挂在本类而非新建一个 {@code WebMvcConfigurer}：多个 configurer 各注册各的，
     * 拦截器的**执行顺序**就散在几个文件里看不出来了。
     */
    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        // 路径来自拦截器自己，别在这里另写一份 —— 两处各写一份的后果是
        // 改了一边、另一边默默保持旧范围，而代码看起来完全正确。
        registry.addInterceptor(auditTrail).addPathPatterns(AuditTrailInterceptor.pathPatterns());
    }

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);      // 缺省回退到 messages.properties(zh)，不看系统 locale
        ms.setUseCodeAsDefaultMessage(true);      // 缺 key 用 key 本身，不抛（兼容旧 ServerException 成品串）
        return ms;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver r = new AcceptHeaderLocaleResolver();
        r.setSupportedLocales(SUPPORTED);
        r.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        return r;
    }
}
