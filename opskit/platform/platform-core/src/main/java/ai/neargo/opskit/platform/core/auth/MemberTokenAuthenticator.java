package ai.neargo.opskit.platform.core.auth;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.platform.api.directory.DirectorySource;
import ai.neargo.opskit.platform.api.directory.MemberUpsert;
import ai.neargo.opskit.platform.api.directory.MemberView;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import ai.neargo.opskit.platform.core.crypto.SecretCipher;
import ai.neargo.opskit.platform.store.dao.IdpDao;
import ai.neargo.opskit.platform.store.row.IdpRow;
import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 成员令牌 → {@link Actor}（21/02 §5.2 · §8.2）。
 *
 * <p>路由：JWT 按未验证的 {@code iss} 找到 IdP（找到之后才用它的 JWKS 真正验签）；不透明令牌按登记的前缀找。
 * 令牌方面的任何失败一律 401 {@code invalid token}，不区分是哪一种。
 */
public class MemberTokenAuthenticator {

    private static final Duration PREFIX_TABLE_TTL = Duration.ofMinutes(1);

    private final IdpDao idps;
    private final TenancyAppService tenancy;
    private final DirectoryAppService directory;
    private final ActorResolver actors;
    private final SecretCipher cipher;
    private final Clock clock;
    private final JwtVerifier jwt = new JwtVerifier();
    private final Introspector introspector;
    private final AtomicReference<PrefixTable> prefixes = new AtomicReference<>();

    private record PrefixTable(List<IdpRow> rows, long loadedAtMs) {
    }

    public MemberTokenAuthenticator(IdpDao idps, TenancyAppService tenancy, DirectoryAppService directory, ActorResolver actors,
            SecretCipher cipher, Clock clock, Duration introspectionCacheMaxTtl) {
        this.idps = idps;
        this.tenancy = tenancy;
        this.directory = directory;
        this.actors = actors;
        this.cipher = cipher;
        this.clock = clock;
        this.introspector = new Introspector(clock, introspectionCacheMaxTtl);
    }

    public Actor authenticate(String bearer, WorkspaceRef pathWorkspace) {
        if (bearer == null || bearer.isBlank() || bearer.length() > 8192) {
            throw invalid();
        }
        IdpRow idp;
        Map<String, Object> claims;
        try {
            Optional<IdpRow> byPrefix = byPrefix(bearer);
            if (byPrefix.isPresent()) {
                idp = byPrefix.get();
                Map<String, Object> c = idp.config();
                String secretEnc = (String) c.get("clientSecretEnc");
                claims = introspector.introspect(bearer, (String) c.get("introspectUrl"), (String) c.get("clientId"),
                        secretEnc == null ? null : cipher.decrypt(secretEnc));
            } else {
                String iss = JWTParser.parse(bearer).getJWTClaimsSet().getIssuer();
                idp = iss == null ? null : idps.findByIssuer(AccountId.UNKNOWN, iss).filter(r -> "JWT".equals(r.kind())).orElse(null);
                if (idp == null || !"ACTIVE".equals(idp.status())) {
                    throw invalid();
                }
                claims = jwt.verify(bearer, idp.issuer(), (String) idp.config().get("jwksUrl"), (String) idp.config().get("audience"));
            }
        } catch (JwtVerifier.BadToken | ParseException e) {
            throw invalid();
        }
        return toActor(idp, new TokenClaims(claims), pathWorkspace);
    }

    /** IdP 登记变化后调用：清前缀路由表与 JWT 解码器缓存。 */
    public void invalidateCaches() {
        prefixes.set(null);
        jwt.clear();
        introspector.clear();
    }

    private Actor toActor(IdpRow idp, TokenClaims claims, WorkspaceRef pathWorkspace) {
        Map<String, Object> m = idp.claimMapping();
        AccountId account = new AccountId(idp.accountId());
        WorkspaceView ws = tenancy.resolve(account, pathWorkspace);
        String wsClaim = (String) m.get("workspace");
        if (wsClaim != null) {
            String tenantRef = claims.string(wsClaim);
            // 令牌属于哪个运营商，就只能访问那个运营商的空间：不一致按「不存在」
            if (tenantRef == null || !tenantRef.equals(ws.externalRef())) {
                throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
            }
        }
        String externalRef = claims.string((String) m.get("member"));
        if (externalRef == null || externalRef.isBlank()) {
            throw invalid();
        }
        TenantContext t = new TenantContext(account, new WorkspaceId(ws.workspaceId()));
        MemberView member = idp.jitEnabled()
                ? directory.provision(t, new MemberUpsert(externalRef, claims.string((String) m.get("displayName")), null, null), DirectorySource.JIT)
                : directory.findMemberByExternalRef(t, externalRef).orElseThrow(MemberTokenAuthenticator::invalid);
        if (m.get("groups") != null) {
            directory.reconcileClaimedGroups(t, member.memberId(), claims.set((String) m.get("groups")));
        }
        return actors.memberActor(t, member, Optional.empty(), claims.set((String) m.get("roles")),
                claims.set((String) m.get("perms")), claims.set((String) m.get("scopes")));
    }

    private Optional<IdpRow> byPrefix(String token) {
        PrefixTable table = prefixes.get();
        long now = clock.millis();
        if (table == null || now - table.loadedAtMs() > PREFIX_TABLE_TTL.toMillis()) {
            table = new PrefixTable(idps.introspectionIdps(AccountId.UNKNOWN), now);
            prefixes.set(table);
        }
        return table.rows().stream().filter(r -> token.startsWith((String) r.config().get("tokenPrefix"))).findFirst();
    }

    private static OpsKitException invalid() {
        return new OpsKitException(CommonErrors.UNAUTHENTICATED, "invalid token");
    }
}
