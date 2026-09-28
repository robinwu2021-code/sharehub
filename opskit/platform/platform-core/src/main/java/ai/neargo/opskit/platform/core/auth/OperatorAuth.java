package ai.neargo.opskit.platform.core.auth;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.AccountId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 运维凭证（{@code opk_…}）：账户级运维面（开空间 · 签发应用凭证 · 查审计 · 跨空间投递）的调用方。
 * 由部署方在启动引导的 operators 段声明、经环境变量注入；只存 SHA-256，不进库（单宿主部署一个账户，凭证随配置走）。
 * 与应用凭证 / 成员令牌互不相通：运维凭证调不了业务接口，业务凭证也调不了运维面。
 */
public class OperatorAuth {

    /** 认证通过的运维方。 */
    public record Operator(String code, AccountId account) {
    }

    private static final Pattern FORMAT = Pattern.compile("opk_[A-Za-z0-9_-]{43,96}");

    private final Map<String, Operator> byHash = new ConcurrentHashMap<>();

    /** 启动引导调用：登记（同代号重复登记 = 换凭证，旧的立即失效）。 */
    public void register(String code, String credential, AccountId account) {
        if (credential == null || !FORMAT.matcher(credential).matches()) {
            throw new IllegalArgumentException("operator credential for " + code + " must be opk_ followed by at least 43 base64url chars");
        }
        byHash.values().removeIf(o -> o.code().equals(code));
        byHash.put(Credentials.hash(credential), new Operator(code, account));
    }

    /** 任何失败一律 401、信息相同。 */
    public Operator authenticate(String bearer) {
        if (bearer == null || !bearer.startsWith("opk_") || bearer.length() > 100) {
            throw unauthenticated();
        }
        String presented = Credentials.hash(bearer);
        for (Map.Entry<String, Operator> e : byHash.entrySet()) {
            if (MessageDigest.isEqual(e.getKey().getBytes(StandardCharsets.US_ASCII), presented.getBytes(StandardCharsets.US_ASCII))) {
                return e.getValue();
            }
        }
        throw unauthenticated();
    }

    public boolean isEmpty() {
        return byHash.isEmpty();
    }

    private static OpsKitException unauthenticated() {
        return new OpsKitException(CommonErrors.UNAUTHENTICATED, "invalid operator credential");
    }
}
