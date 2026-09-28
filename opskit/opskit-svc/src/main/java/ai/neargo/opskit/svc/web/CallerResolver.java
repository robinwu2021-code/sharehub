package ai.neargo.opskit.svc.web;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.core.auth.ActorResolver;
import ai.neargo.opskit.platform.core.auth.MemberTokenAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 控制器方法里的 {@link Actor} 参数：从 {@code Authorization: Bearer} 与路径里的 {@code {ws}} 解析（21/06 §一）。
 *
 * <ul>
 *   <li>{@code okk_} 前缀 = 应用凭证 → 应用自身（代成员 onBehalfOf 随 O4-1 接入）；</li>
 *   <li>其他 = 成员令牌（JWT 按 iss、不透明令牌按前缀路由到 IdP）。</li>
 * </ul>
 * 请求体里的操作人与数据范围一律不认 —— 身份只来自令牌。
 */
public class CallerResolver implements HandlerMethodArgumentResolver {

    private static final String APP_PREFIX = "okk_";

    private final AppAppService apps;
    private final ActorResolver actors;
    private final MemberTokenAuthenticator members;

    public CallerResolver(AppAppService apps, ActorResolver actors, MemberTokenAuthenticator members) {
        this.apps = apps;
        this.actors = actors;
        this.members = members;
    }

    @Override
    public boolean supportsParameter(MethodParameter p) {
        return Actor.class.equals(p.getParameterType());
    }

    @Override
    public Actor resolveArgument(MethodParameter p, ModelAndViewContainer mav, NativeWebRequest web, WebDataBinderFactory binders) {
        HttpServletRequest req = web.getNativeRequest(HttpServletRequest.class);
        String header = req == null ? null : req.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new OpsKitException(CommonErrors.UNAUTHENTICATED, "missing bearer token");
        }
        String bearer = header.substring(7).strip();
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String ws = vars == null ? null : vars.get("ws");
        if (ws == null || ws.isBlank()) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
        WorkspaceRef ref = WorkspaceRef.parse(ws);
        Actor actor = bearer.startsWith(APP_PREFIX) ? actors.forApp(apps.authenticate(bearer), ref, null) : members.authenticate(bearer, ref);
        MDC.put("actor", actor.member().map(m -> m.key()).orElse("app:" + actor.app().orElse("?")));
        return actor;
    }
}
