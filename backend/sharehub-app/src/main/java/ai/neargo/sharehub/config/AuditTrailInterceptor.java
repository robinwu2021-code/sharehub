package ai.neargo.sharehub.config;

import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.platform.org.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 运营端写操作审计（{@code iam_audit_log} 的生产者）。
 *
 * <p><b>为什么是拦截器而不是 {@code @OperateLog} 注解</b>：审计的价值来自「无一遗漏」。
 * 逐端点加注解意味着<b>每加一个写端点就多一次漏加的机会</b>，而漏加是静默的 ——
 * 事后翻审计只会看到「这个操作没人做过」，无从分辨是真没人做还是忘了标注。
 * 横切拦截则相反：新端点默认就被覆盖，要排除得显式写进白名单（在这里，看得见）。
 *
 * <p><b>只审运营端写操作</b>：{@code /api/**} 的 POST/PUT/PATCH/DELETE。
 * 读不审（量大且无风险），{@code /mp/**} 不审（C 端行为审计属另一套，落 usr_* 侧），
 * {@code /internal/**} 不审（服务间调用没有「操作人」，记下来只会是一串 system）。
 *
 * <p><b>失败不影响业务</b>：审计写失败只记日志，不让主流程 500 ——
 * 但这是有代价的取舍（审计可能缺行）。之所以能这样取舍，是因为本表是<b>操作留痕</b>
 * 而非账务凭证；{@code acct_ledger} 那种「丢一行就对不平账」的表绝不能这么做。
 */
@Component
public class AuditTrailInterceptor implements HandlerInterceptor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AuditTrailInterceptor.class);

    private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * 免审白名单。登录/登出走的是认证链路，本身不带会话身份，
     * 且失败尝试属安全事件（应进安全日志而非操作审计）。
     */
    private static final List<String> SKIP_PREFIXES = List.of("/api/auth/");

    private final AuditLogService auditLogs;

    public AuditTrailInterceptor(AuditLogService auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, @Nullable Exception ex) {
        try {
            String uri = req.getRequestURI();
            if (!uri.startsWith("/api/") || !WRITE_METHODS.contains(req.getMethod())) return;
            if (SKIP_PREFIXES.stream().anyMatch(uri::startsWith)) return;
            // 只审**改动成功**的操作：400/403 是「没做成」，记进审计会让「谁改了什么」失真
            if (res.getStatus() >= 400) return;

            LoginUser u = SecurityUtils.currentUser().orElse(null);
            if (u == null) return;   // 无身份即非运营端写入（过滤链已挡住匿名写）

            Target t = targetOf(uri);
            auditLogs.append(u.userNo(), u.username(), req.getMethod() + " " + uri,
                    json(t.type()), json(t.no()),
                    json(Map.of("query", req.getQueryString() == null ? "" : req.getQueryString())),
                    clientIp(req));
        } catch (RuntimeException e) {
            // 审计失败不影响业务（见类注释的取舍说明），但必须留日志 —— 静默丢审计等于没有审计
            log.warn("审计写入失败 uri={} : {}", req.getRequestURI(), e.toString());
        }
    }

    /**
     * 从路径推出操作对象：{@code /api/ops/work-orders/WO123/dispatch} → (work-orders, WO123)。
     * 取「最后一个像业务键的段」而非固定位置 —— 动作端点的键在倒数第二段，CRUD 在最后一段。
     */
    private static Target targetOf(String uri) {
        String[] seg = uri.split("/");
        String type = seg.length > 3 ? seg[3] : "";
        String no = "";
        for (String s : seg) {
            if (looksLikeKey(s)) no = s;
        }
        return new Target(type, no);
    }

    /** 业务键形如 WO123/CAB1001/AG001：含数字且非纯小写单词（后者是资源名或动作名）。 */
    private static boolean looksLikeKey(String s) {
        return s.length() >= 3 && s.chars().anyMatch(Character::isDigit)
                && s.chars().anyMatch(Character::isUpperCase);
    }

    /** 反向代理下 remoteAddr 是网关地址，优先取转发头的首跳。 */
    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    /** {@code iam_audit_log} 的 target_type/target_no/detail 都是 JSON 列（V13，带 json_valid CHECK）。 */
    private static String json(Object v) {
        return ai.neargo.sharehub.common.Json.write(v);
    }

    private record Target(String type, String no) {
    }
}
