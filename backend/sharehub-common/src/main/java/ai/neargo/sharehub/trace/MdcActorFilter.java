package ai.neargo.sharehub.trace;

import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把当前操作人写进 MDC，供日志模板 {@code %X{actor}} 输出。
 *
 * <h2>为什么值得单独一个过滤器</h2>
 * 日志里有 {@code traceId} 能把一次请求的所有行串起来，但它回答不了
 * <b>「是谁在操作时出的错」</b>。而排障时这个问题几乎总是第二个被问到 ——
 * 尤其是权限相关的问题（某个角色点了某个按钮报错），没有 actor 就只能
 * 拿时间戳去比运营端的操作记录。
 *
 * <h2>顺序：必须在认证之后</h2>
 * {@code @Order(LOWEST_PRECEDENCE)} —— 认证过滤器把身份放进 {@code CurrentUser} 之后才轮到它。
 * 放前面的话每次取到的都是空，而**空得很安静**，不会有任何报错提示你顺序错了。
 *
 * <h2>未登录不是异常</h2>
 * 登录接口、C 端公开接口本来就没有 actor。这时不写 MDC，
 * 日志模板里的 {@code %X{actor:-}} 会输出空 —— 比写一个 "anonymous" 更诚实，
 * 因为后者会让人以为系统认定了一个匿名主体。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class MdcActorFilter extends OncePerRequestFilter {

    /** MDC 键：日志模板用 {@code %X{actor}}。 */
    public static final String MDC_KEY = "actor";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String actor = SecurityUtils.currentUser().map(MdcActorFilter::label).orElse(null);
        if (actor != null) {
            MDC.put(MDC_KEY, actor);
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            // 线程池复用：不清会把上一个请求的操作人贴到下一个请求的日志上 ——
            // 那不是「日志少了一个字段」，是**日志在指认错误的人**
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * {@code 张三(U123)} —— 编号与名字都要。
     *
     * <p>只有编号：看日志的人得再查一次库才知道是谁；
     * 只有名字：重名时分不清，而运营团队重名很常见。
     */
    private static String label(LoginUser u) {
        String name = u.username();
        return (name == null || name.isBlank()) ? u.userNo() : name + "(" + u.userNo() + ")";
    }
}
