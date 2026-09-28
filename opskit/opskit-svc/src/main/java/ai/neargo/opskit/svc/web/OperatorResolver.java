package ai.neargo.opskit.svc.web;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.platform.core.auth.OperatorAuth;
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
 * 运维面控制器里的 {@link OperatorAuth.Operator} 参数：{@code Authorization: Bearer opk_…}，路径 {@code {a}} 须是该运维方所属账户
 * （或 {@code self}）；别的账户按不存在（404），不给探测线索。
 */
public class OperatorResolver implements HandlerMethodArgumentResolver {

    private final OperatorAuth auth;
    private final ai.neargo.opskit.platform.core.idem.IdempotencyStore idempotency;

    public OperatorResolver(OperatorAuth auth, ai.neargo.opskit.platform.core.idem.IdempotencyStore idempotency) {
        this.auth = auth;
        this.idempotency = idempotency;
    }

    @Override
    public boolean supportsParameter(MethodParameter p) {
        return OperatorAuth.Operator.class.equals(p.getParameterType());
    }

    @Override
    public OperatorAuth.Operator resolveArgument(MethodParameter p, ModelAndViewContainer mav, NativeWebRequest web, WebDataBinderFactory binders) {
        HttpServletRequest req = web.getNativeRequest(HttpServletRequest.class);
        String header = req == null ? null : req.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new OpsKitException(CommonErrors.UNAUTHENTICATED, "missing bearer token");
        }
        OperatorAuth.Operator op = auth.authenticate(header.substring(7).strip());
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String a = vars == null ? null : vars.get("a");
        if (a == null || !("self".equals(a) || op.account().value().equals(a))) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "account not found");
        }
        MDC.put("actor", "ops:" + op.code());
        // 与业务面同一套幂等：签发凭证这类写操作重试不能签出第二套
        byte[] body = (byte[]) req.getAttribute(WriteRequestFilter.BODY);
        String key = body == null ? null : req.getHeader(WriteRequestFilter.IDEM_HEADER);
        if (key != null && req.getAttribute(WriteRequestFilter.IDEM_CLAIM) == null) {
            String caller = "ops:" + op.code();
            switch (idempotency.begin(caller, key, WriteRequestFilter.requestHash(req, body))) {
                case ai.neargo.opskit.platform.core.idem.IdempotencyStore.Outcome.Proceed x -> req.setAttribute(WriteRequestFilter.IDEM_CLAIM,
                        new String[] {caller, key});
                case ai.neargo.opskit.platform.core.idem.IdempotencyStore.Outcome.Replay r -> throw new IdempotentReplay(r.httpStatus(), r.body());
            }
        }
        return op;
    }
}
