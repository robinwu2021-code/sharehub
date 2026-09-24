package ai.neargo.sharehub.config;

import ai.neargo.sharehub.auth.ClientCode;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.platform.org.service.AuditLogService;
import ai.neargo.sharehub.platform.org.service.AuditOutcome;
import ai.neargo.sharehub.svc.InternalClient;
import ai.neargo.sharehub.trace.TraceContext;
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
 * <p><b>审哪些</b>：{@code /api/**} 与 {@code /internal/**} 的 POST/PUT/PATCH/DELETE。
 * 读不审（量大且无风险），{@code /mp/**} 不审（C 端行为审计属另一套，落 usr_* 侧）。
 *
 * <p>{@code /internal/**} 此前被整个排除，理由是「服务间调用没有操作人」。
 * 那个前提今天<b>只对一半成立</b>：{@code /internal/events/**} 确实是服务间的，
 * 而 {@code /internal/trade/**} · {@code /internal/user/**} 这批历史端点
 * <b>ops-web 今天就在用员工令牌调</b>（见 {@code InternalTokenFilter} 的说明）——
 * 结算生成、信用拉黑都在里面，而它们至今<b>一条审计都没有</b>。
 * 现在：有登录人就记登录人，没有就记 {@code SYSTEM:<服务名>}。
 *
 * <p><b>成没成都记</b>（{@link AuditOutcome}）。此前遇到 {@code >=400} 直接跳过，
 * 理由是「没做成的记进去会让『谁改了什么』失真」—— 顾虑对，结论反了：
 * 被拒绝的操作恰恰最该留痕。有人拿没权限的账号反复点某个危险操作是安全信号，
 * 而不记的话，事后查「谁试过改分润规则」得到的答案是<b>「没有人」</b>。
 * 失真问题由 {@code outcome} 列解决 —— 查询自己分开这两件事，不必靠丢数据。
 *
 * <p><b>带 traceId</b>：审计回答「谁改了什么」，运行日志回答「那次请求发生了什么」，
 * 两边各有一半。traceId 是它们之间唯一的那根线，没有它只能拿时间戳去猜。
 *
 * <p><b>写失败不影响业务，但要告警</b>：不让主流程 500（本表是操作留痕而非账务凭证；
 * {@code acct_ledger} 那种「丢一行就对不平账」的表绝不能这么取舍）。
 * 代价是审计可能缺行，所以失败必须打 <b>ERROR</b> 而不是 WARN ——
 * WARN 在生产日志里淹没得太快，而「审计悄悄停止工作」的后果是事后翻出一段
 * 看起来正常、实际缺行的历史，<b>没有任何迹象提示它缺了</b>。
 * ERROR 会进 {@code error/} 那份小文件（见 logback-spring.xml），告警接的就是它。
 */
@Component
public class AuditTrailInterceptor implements HandlerInterceptor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AuditTrailInterceptor.class);

    private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * 审哪些前缀。**这是唯一的一份** —— 注册处（{@code I18nConfig.addInterceptors}）
     * 由 {@link #pathPatterns()} 从这里派生。
     *
     * <p>此前这个决定写在两个地方：这里一份 {@code startsWith}，注册处一份
     * {@code addPathPatterns("/api/**")}。把 {@code /internal/} 加进本类之后，
     * 注册处仍然只放 {@code /api/**} 进来 —— 代码看起来完全正确，
     * 而新加的那一半<b>根本不会被调用</b>，且没有任何报错。
     */
    static final List<String> AUDITED_PREFIXES = List.of("/api/", "/internal/");

    /** 注册用的 Ant 模式，由 {@link #AUDITED_PREFIXES} 派生，保证两边不会分叉。 */
    public static String[] pathPatterns() {
        return AUDITED_PREFIXES.stream().map(p -> p + "**").toArray(String[]::new);
    }

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
            if (AUDITED_PREFIXES.stream().noneMatch(uri::startsWith)
                    || !WRITE_METHODS.contains(req.getMethod())) return;
            if (SKIP_PREFIXES.stream().anyMatch(uri::startsWith)) return;
            LoginUser u = SecurityUtils.currentUser().orElse(null);
            // /api 上没有身份 = 还没认证（401）。审计记不出「谁」，
            // 这属于安全日志而非操作审计。/internal 则相反：那里本来就可能没有人。
            if (u == null && uri.startsWith("/api/")) return;

            Target t = targetOf(uri);
            // 端来自**会话的 realm**，不来自请求头：能被被审计方自己设置的字段，
            // 会让人以为它可信，而改个 header 就能伪造，伪造出来的和真的长得一样。
            auditLogs.append(new AuditLogService.Entry(
                    u == null ? systemActor(req) : u.userNo(),
                    u == null ? systemActor(req) : u.username(),
                    u == null ? null : ClientCode.of(u.realm()),
                    req.getMethod() + " " + uri,
                    AuditOutcome.ofStatus(res.getStatus()),
                    TraceContext.currentTraceId(),
                    json(t.type()), json(t.no()),
                    json(Map.of("query", req.getQueryString() == null ? "" : req.getQueryString())),
                    clientIp(req)));
        } catch (RuntimeException e) {
            // 审计写失败**不让业务 500**（见类注释的取舍），但必须是 ERROR 而不是 WARN：
            // WARN 在生产日志里淹没得太快，而「审计悄悄停止工作」的代价是
            // 事后翻审计得到一段看起来正常、实际缺行的历史 —— 没有任何迹象提示它缺了。
            // ERROR 会进 error/ 那份小文件（见 logback-spring.xml），告警接的就是它。
            log.error("审计写入失败，这条操作没有留痕：uri={} actor={}。"
                            + "连续出现说明审计已经在静默失效，先查 iam_audit_log 是否可写。",
                    req.getRequestURI(), SecurityUtils.currentUser().map(LoginUser::userNo).orElse("?"), e);
        }
    }

    /**
     * 没有登录人的内部调用记成 {@code SYSTEM:<服务名>}。
     *
     * <p><b>服务名是调用方自报的</b>（{@link InternalClient#CALLER_HEADER}）——
     * 内部凭证是一把共享密钥，持有它的任何一方都能声称自己是任何服务。
     * 所以这一段是<b>排障线索</b>而非身份证明：可信的部分是 {@code SYSTEM}
     * （确实有人拿着内部密钥做了这件事），后半截只是它自己说的。
     *
     * <p>为什么这里可以记而 {@code clientCode} 不能采信请求头：伪造 clientCode
     * 只要在浏览器里改个 header，伪造这个得先拿到服务密钥 ——
     * 而拿到密钥的人本来就能做这件事。前者会把不可信的值伪装成可信，后者不会。
     */
    private static String systemActor(HttpServletRequest req) {
        String caller = req.getHeader(InternalClient.CALLER_HEADER);
        return (caller == null || caller.isBlank()) ? "SYSTEM" : "SYSTEM:" + caller.trim();
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
