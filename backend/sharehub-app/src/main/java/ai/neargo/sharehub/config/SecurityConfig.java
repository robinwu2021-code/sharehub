package ai.neargo.powerbank.config;

import ai.neargo.powerbank.auth.ConsumerTokenAuthFilter;
import ai.neargo.powerbank.auth.StaffTokenAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 认证鉴权（权限管理方案 + TDD-认证鉴权）。**运营端与 C 端分链、分过滤器、分上下文**，共用基座
 * {@link TokenStore}/LoginUser：
 * <ul>
 *   <li><b>C 端链</b>（@Order(1)，{@code /mp/**}）：{@link ConsumerTokenAuthFilter}，无 RBAC，属主鉴权；CORS(H5)。</li>
 *   <li><b>运营端链</b>（@Order(2)，默认兜底，{@code /api}、{@code /internal}）：{@link StaffTokenAuthFilter} +
 *       {@code @PreAuthorize("@perm.can(...)")}。</li>
 * </ul>
 * 两链均无状态、关 CSRF（Bearer 非 Cookie）、401/403 统一返 {@code {code,msg,data}} JSON（不跳登录页）。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final String[] allowedOrigins;

    public SecurityConfig(@Value("${powerbank.cors.allowed-origins:http://localhost:3000}") String origins) {
        this.allowedOrigins = origins.split("\\s*,\\s*");
    }

    /** C 端链：/mp/**。 */
    @Bean
    @Order(1)
    public SecurityFilterChain consumerChain(HttpSecurity http, ConsumerTokenAuthFilter consumerFilter) throws Exception {
        http
                .securityMatcher("/mp/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/mp/auth/**", "/mp/nearby/**", "/mp/public/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(consumerFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, resp, e) -> writeJson(resp, 401, "未认证或会话失效"))
                        .accessDeniedHandler((req, resp, e) -> writeJson(resp, 403, "无权访问")));
        return http.build();
    }

    /** 运营端链：默认兜底（/api、/internal 等）。 */
    @Bean
    @Order(2)
    public SecurityFilterChain opsChain(HttpSecurity http, StaffTokenAuthFilter staffFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/**", "/notify/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(staffFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, resp, e) -> writeJson(resp, 401, "未认证或会话失效"))
                        .accessDeniedHandler((req, resp, e) -> writeJson(resp, 403, "无权限")));
        return http.build();
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Arrays.asList(allowedOrigins));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    private static void writeJson(HttpServletResponse resp, int code, String msg) throws java.io.IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
