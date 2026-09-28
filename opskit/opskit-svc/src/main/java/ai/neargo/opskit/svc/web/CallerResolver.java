package ai.neargo.opskit.svc.web;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.core.auth.ActorResolver;
import ai.neargo.opskit.platform.core.auth.MemberTokenAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.platform.api.directory.OnBehalfOf;
import ai.neargo.opskit.platform.core.idem.IdempotencyStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 *   <li>{@code okk_} 前缀 = 应用凭证 → 应用自身；请求体带 {@code onBehalfOf} 时代该成员（不存在即开通）；</li>
 *   <li>其他 = 成员令牌（JWT 按 iss、不透明令牌按前缀路由到 IdP）；成员令牌带 onBehalfOf → 403（只有应用能代人）。</li>
 * </ul>
 * 除 onBehalfOf 外，请求体里的操作人与数据范围一律不认 —— 身份只来自令牌。
 * 写请求带 {@code Idempotency-Key} 时，在这里（调用方已知、业务未执行）占位或重放，见 {@link WriteRequestFilter}。
 */
public class CallerResolver implements HandlerMethodArgumentResolver {

    private static final String APP_PREFIX = "okk_";

    private final AppAppService apps;
    private final ActorResolver actors;
    private final MemberTokenAuthenticator members;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json = new ObjectMapper();

    public CallerResolver(AppAppService apps, ActorResolver actors, MemberTokenAuthenticator members, IdempotencyStore idempotency) {
        this.apps = apps;
        this.actors = actors;
        this.members = members;
        this.idempotency = idempotency;
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
        byte[] body = req == null ? null : (byte[]) req.getAttribute(WriteRequestFilter.BODY);
        OnBehalfOf onBehalfOf = onBehalfOf(body);
        Actor actor;
        if (bearer.startsWith(APP_PREFIX)) {
            actor = actors.forApp(apps.authenticate(bearer), ref, onBehalfOf);
        } else {
            actor = members.authenticate(bearer, ref);
            if (onBehalfOf != null) {
                throw new OpsKitException(CommonErrors.PERMISSION_DENIED, "onBehalfOf requires an application credential");
            }
        }
        MDC.put("actor", actor.member().map(m -> m.key()).orElse("app:" + actor.app().orElse("?")));
        MDC.put("workspace", actor.tenant().workspaceId().value());
        claimIdempotency(req, body, actor);
        return actor;
    }

    /** 幂等键按调用方隔离：应用（含代成员）按应用，成员令牌按成员。 */
    private void claimIdempotency(HttpServletRequest req, byte[] body, Actor actor) {
        String key = req == null || body == null ? null : req.getHeader(WriteRequestFilter.IDEM_HEADER);
        if (key == null || req.getAttribute(WriteRequestFilter.IDEM_CLAIM) != null) {
            return;
        }
        String caller = actor.app().map(a -> "app:" + a).orElseGet(() -> actor.member().map(PrincipalRef::key).orElse("?"));
        switch (idempotency.begin(caller, key, WriteRequestFilter.requestHash(req, body))) {
            case IdempotencyStore.Outcome.Proceed p -> req.setAttribute(WriteRequestFilter.IDEM_CLAIM, new String[] {caller, key});
            case IdempotencyStore.Outcome.Replay r -> throw new IdempotentReplay(r.httpStatus(), r.body());
        }
    }

    private OnBehalfOf onBehalfOf(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            return null; // 体不是 JSON：交给控制器按 400 处理
        }
        JsonNode o = root == null ? null : root.get("onBehalfOf");
        if (o == null || o.isNull()) {
            return null;
        }
        if (!o.isObject() || !o.path("externalRef").isString() || o.path("externalRef").asString().isBlank()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "onBehalfOf.externalRef is required", Map.of(),
                    java.util.List.of(new ai.neargo.opskit.commons.error.FieldError("onBehalfOf.externalRef", "opskit.validation.required")));
        }
        return new OnBehalfOf(o.get("externalRef").asString(), o.path("displayName").isString() ? o.get("displayName").asString() : null,
                strings(o.get("groups")), strings(o.get("scopes")), strings(o.get("perms")));
    }

    private static Set<String> strings(JsonNode n) {
        if (n == null || !n.isArray()) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        n.forEach(x -> out.add(x.asString()));
        return out;
    }
}
