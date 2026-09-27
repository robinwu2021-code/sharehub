package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.api.federation.ClaimMapping;
import ai.neargo.opskit.platform.api.federation.IdpConfig;
import ai.neargo.opskit.platform.api.federation.IdpKind;
import ai.neargo.opskit.platform.core.crypto.SecretCipher;
import ai.neargo.opskit.platform.store.dao.IdpDao;
import ai.neargo.opskit.platform.store.row.IdpRow;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionOperations;

/** 令牌联邦的登记（21/07 O1-3）。M1 只开放给运维面。 */
public class FederationAppService {

    private final IdpDao idps;
    private final SecretCipher cipher;
    private final Clock clock;
    private final TransactionOperations tx;
    private final AuditTrail audit;
    private final Runnable onChange;

    /** @param onChange 登记变化后清认证侧的缓存（前缀路由表、JWT 解码器） */
    public FederationAppService(IdpDao idps, SecretCipher cipher, Clock clock, TransactionOperations tx, AuditTrail audit, Runnable onChange) {
        this.idps = idps;
        this.cipher = cipher;
        this.clock = clock;
        this.tx = tx;
        this.audit = audit;
        this.onChange = onChange;
    }

    @SystemAction("运维面：登记账户的身份提供方")
    public void upsert(AccountId account, IdpConfig c) {
        List<FieldError> errors = new ArrayList<>();
        if (c.issuer() == null || c.issuer().isBlank() || c.issuer().length() > 256) {
            errors.add(new FieldError("issuer", "opskit.validation.required"));
        }
        if (c.kind() == null) {
            errors.add(new FieldError("kind", "opskit.validation.required"));
        } else if (c.kind() == IdpKind.JWT && !isUrl(c.jwksUrl())) {
            errors.add(new FieldError("jwksUrl", "opskit.validation.invalid_url"));
        } else if (c.kind() == IdpKind.INTROSPECT) {
            if (!isUrl(c.introspectUrl())) {
                errors.add(new FieldError("introspectUrl", "opskit.validation.invalid_url"));
            }
            if (c.tokenPrefix() == null || c.tokenPrefix().length() < 3) {
                errors.add(new FieldError("tokenPrefix", "opskit.validation.required"));
            }
        }
        if (c.claimMapping() == null) {
            errors.add(new FieldError("claimMapping", "opskit.validation.required"));
        }
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid idp config", Map.of(), errors);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        if (c.kind() == IdpKind.JWT) {
            config.put("jwksUrl", c.jwksUrl());
            if (c.audience() != null) {
                config.put("audience", c.audience());
            }
        } else {
            config.put("introspectUrl", c.introspectUrl());
            config.put("tokenPrefix", c.tokenPrefix());
            if (c.clientId() != null) {
                config.put("clientId", c.clientId());
                config.put("clientSecretEnc", cipher.encrypt(c.clientSecret() == null ? "" : c.clientSecret()));
            }
        }
        tx.executeWithoutResult(s -> {
            idps.findByIssuer(AccountId.UNKNOWN, c.issuer()).filter(r -> !r.accountId().equals(account.value())).ifPresent(r -> {
                // 签发方全局唯一：被别的账户占用时不许覆盖别人的登记
                throw new OpsKitException(CommonErrors.VALIDATION, "issuer already registered", Map.of(),
                        List.of(new FieldError("issuer", "opskit.platform.issuer_taken")));
            });
            if (c.kind() == IdpKind.INTROSPECT) {
                boolean prefixTaken = idps.introspectionIdps(AccountId.UNKNOWN).stream()
                        .anyMatch(r -> !r.issuer().equals(c.issuer()) && c.tokenPrefix().equals(r.config().get("tokenPrefix")));
                if (prefixTaken) {
                    throw new OpsKitException(CommonErrors.VALIDATION, "token prefix already registered", Map.of(),
                            List.of(new FieldError("tokenPrefix", "opskit.platform.token_prefix_taken")));
                }
            }
            idps.upsert(account, new IdpRow(account.value(), c.issuer(), c.kind().name(), config, mappingToMap(c.claimMapping()),
                    c.jitEnabled(), "ACTIVE", clock.millis()));
            audit.record(account, "", AuditTrail.OPS, "idp.upserted", "idp:" + c.issuer(), Map.of("kind", c.kind().name()));
        });
        onChange.run();
    }

    static Map<String, Object> mappingToMap(ClaimMapping m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("member", m.member());
        putIf(out, "displayName", m.displayName());
        putIf(out, "workspace", m.workspace());
        putIf(out, "groups", m.groups());
        putIf(out, "roles", m.roles());
        putIf(out, "perms", m.perms());
        putIf(out, "scopes", m.scopes());
        return out;
    }

    private static void putIf(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private static boolean isUrl(String s) {
        try {
            URI u = URI.create(s);
            return ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
