package ai.neargo.opskit.platform.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.account.AccountKind;
import ai.neargo.opskit.platform.api.account.CreateAccountRequest;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.api.directory.RoleUpsert;
import ai.neargo.opskit.platform.api.federation.ClaimMapping;
import ai.neargo.opskit.platform.api.federation.IdpConfig;
import ai.neargo.opskit.platform.api.federation.IdpKind;
import ai.neargo.opskit.platform.core.app.FederationAppService;
import ai.neargo.opskit.platform.core.crypto.SecretCipherTest;
import ai.neargo.opskit.platform.core.support.DirectoryEnv;
import ai.neargo.opskit.platform.store.dao.IdpDao;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-3：多签发方 JWT（JWKS）+ 内省；声明映射；缺声明 = 无权限；未登记签发方 401。 */
class MemberTokenAuthenticatorTest {

    static final String ISSUER = "https://idp.partner.example";

    /** 本地身份提供方：发布 JWKS、应答内省，并记下内省被调了几次。 */
    static final class LocalIdp implements AutoCloseable {
        final RSAKey key;
        final HttpServer server;
        final Map<String, String> introspection = new ConcurrentHashMap<>();
        final AtomicInteger introspectCalls = new AtomicInteger();

        LocalIdp() throws Exception {
            key = new RSAKeyGenerator(2048).keyID("k1").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", ex -> respond(ex, new JWKSet(key.toPublicJWK()).toString()));
            server.createContext("/introspect", ex -> {
                introspectCalls.incrementAndGet();
                String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String token = java.net.URLDecoder.decode(form.substring("token=".length()), StandardCharsets.UTF_8);
                respond(ex, introspection.getOrDefault(token, "{\"active\":false}"));
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String sign(JWTClaimsSet claims, RSAKey with) throws Exception {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(with.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(with));
            return jwt.serialize();
        }

        private static void respond(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    record Env(DirectoryEnv d, MemberTokenAuthenticator auth, FederationAppService federation) {
    }

    private static Env env(TestDatabase db) {
        DirectoryEnv d = new DirectoryEnv(db);
        IdpDao dao = new IdpDao(d.f.jdbc, db.dialect());
        MemberTokenAuthenticator auth = new MemberTokenAuthenticator(dao, d.tenancy, d.directory, d.actors, SecretCipherTest.cipher(),
                d.f.clock, Duration.ofMinutes(5));
        FederationAppService fed = new FederationAppService(dao, SecretCipherTest.cipher(), d.f.clock, d.f.tx, d.audit, auth::invalidateCaches);
        return new Env(d, auth, fed);
    }

    private static final ClaimMapping MAPPING = new ClaimMapping("sub", "name", "tenant", "groups", "roles", "perms", "scopes");

    private static JWTClaimsSet.Builder claims(String sub) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience("opskit").subject(sub).claim("name", "Ali").claim("tenant", "T0001")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)));
    }

    private static void assertInvalid(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(OpsKitException.class).satisfies(e -> {
            assertThat(((OpsKitException) e).errorKey()).isEqualTo(CommonErrors.UNAUTHENTICATED);
            assertThat(e.getMessage()).isEqualTo("invalid token");
        });
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void jwtClaimsBecomeTheActor(TestDatabase db) throws Exception {
        Env e = env(db);
        try (LocalIdp idp = new LocalIdp()) {
            e.federation.upsert(e.d.account, new IdpConfig(ISSUER, IdpKind.JWT, idp.base() + "/jwks", "opskit", null, null, null, null, MAPPING, true));
            e.d.directory.upsertRole(e.d.system(), WorkspaceId.TEMPLATE, new RoleUpsert("agent", Map.of(), Set.of("ticket:work")));
            String token = idp.sign(claims("E107").claim("groups", List.of("field-ops")).claim("roles", "agent")
                    .claim("scopes", List.of("site:ST0311")).claim("perms", List.of("location:contract:audit")).build(), idp.key);

            Actor a = e.auth.authenticate(token, WorkspaceRef.parse("ext:T0001"));

            assertThat(a.member()).isPresent();
            assertThat(a.app()).isEmpty();
            assertThat(a.groups()).containsExactly("field-ops");
            assertThat(a.roles()).containsExactly("agent");
            assertThat(a.productPermissions()).containsExactly("ticket:work");
            assertThat(a.scopes()).containsExactly("site:ST0311");
            assertThat(a.externalPerms()).containsExactly("location:contract:audit");
            assertThat(e.d.directory.membersOf(e.d.tenant, "field-ops")).as("令牌里的组落进目录（CLAIM）").hasSize(1);
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void missingClaimsMeanNothingNotEverything(TestDatabase db) throws Exception {
        Env e = env(db);
        try (LocalIdp idp = new LocalIdp()) {
            e.federation.upsert(e.d.account, new IdpConfig(ISSUER, IdpKind.JWT, idp.base() + "/jwks", "opskit", null, null, null, null, MAPPING, true));
            Actor a = e.auth.authenticate(idp.sign(claims("E107").build(), idp.key), WorkspaceRef.parse("ext:T0001"));
            assertThat(a.groups()).isEmpty();
            assertThat(a.roles()).isEmpty();
            assertThat(a.productPermissions()).isEmpty();
            assertThat(a.scopes()).as("没有范围声明 = 只看得见自己参与的").isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void everyBadJwtIsTheSame401(TestDatabase db) throws Exception {
        Env e = env(db);
        try (LocalIdp idp = new LocalIdp()) {
            e.federation.upsert(e.d.account, new IdpConfig(ISSUER, IdpKind.JWT, idp.base() + "/jwks", "opskit", null, null, null, null, MAPPING, true));
            RSAKey stranger = new RSAKeyGenerator(2048).keyID("k1").generate();
            Instant past = Instant.now().minusSeconds(3600);
            WorkspaceRef ws = WorkspaceRef.parse("ext:T0001");

            assertInvalid(() -> e.auth.authenticate(sign(idp, claims("E1").expirationTime(Date.from(past)).build(), idp.key), ws));
            assertInvalid(() -> e.auth.authenticate(sign(idp, claims("E1").audience("someone-else").build(), idp.key), ws));
            assertInvalid(() -> e.auth.authenticate(sign(idp, claims("E1").build(), stranger), ws));
            assertInvalid(() -> e.auth.authenticate(sign(idp, claims("E1").issuer("https://unknown.example").build(), idp.key), ws));
            assertInvalid(() -> e.auth.authenticate("not-a-token", ws));
            assertInvalid(() -> e.auth.authenticate("", ws));
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void tokenOfOneOperatorCannotReachAnother(TestDatabase db) throws Exception {
        Env e = env(db);
        e.d.tenancy.createWorkspace(e.d.account, new CreateWorkspaceRequest("另一运营商", "T0002", null, null));
        try (LocalIdp idp = new LocalIdp()) {
            e.federation.upsert(e.d.account, new IdpConfig(ISSUER, IdpKind.JWT, idp.base() + "/jwks", "opskit", null, null, null, null, MAPPING, true));
            String t0001Token = idp.sign(claims("E107").build(), idp.key);
            assertThatThrownBy(() -> e.auth.authenticate(t0001Token, WorkspaceRef.parse("ext:T0002")))
                    .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.NOT_FOUND);
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void withoutJitOnlyKnownMembersGetIn(TestDatabase db) throws Exception {
        Env e = env(db);
        try (LocalIdp idp = new LocalIdp()) {
            e.federation.upsert(e.d.account, new IdpConfig(ISSUER, IdpKind.JWT, idp.base() + "/jwks", "opskit", null, null, null, null, MAPPING, false));
            assertInvalid(() -> e.auth.authenticate(sign(idp, claims("E404").build(), idp.key), WorkspaceRef.parse("ext:T0001")));
            assertThat(e.d.f.jdbc.sql("SELECT COUNT(*) FROM plat_member").query(Integer.class).single()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void opaqueTokensAreRoutedByPrefixIntrospectedAndCached(TestDatabase db) throws Exception {
        Env e = env(db);
        try (LocalIdp idp = new LocalIdp()) {
            e.federation.upsert(e.d.account, new IdpConfig("sharehub-staff", IdpKind.INTROSPECT, null, null, idp.base() + "/introspect",
                    "opskit", "s3cret", "stk_", MAPPING, true));
            long exp = e.d.f.clock.instant().plusSeconds(3600).getEpochSecond();
            idp.introspection.put("stk_live", "{\"active\":true,\"sub\":\"E107\",\"tenant\":\"T0001\",\"groups\":\"field-ops night\",\"exp\":" + exp + "}");

            Actor a = e.auth.authenticate("stk_live", WorkspaceRef.parse("ext:T0001"));
            e.auth.authenticate("stk_live", WorkspaceRef.parse("ext:T0001"));
            assertThat(a.groups()).containsExactlyInAnyOrder("field-ops", "night");
            assertThat(idp.introspectCalls.get()).as("有效期内命中缓存，不再打宿主").isEqualTo(1);

            e.d.f.clock.advanceMillis(Duration.ofMinutes(6).toMillis());
            e.auth.authenticate("stk_live", WorkspaceRef.parse("ext:T0001"));
            assertThat(idp.introspectCalls.get()).as("缓存上限 5 分钟").isEqualTo(2);

            assertInvalid(() -> e.auth.authenticate("stk_revoked", WorkspaceRef.parse("ext:T0001")));
            assertInvalid(() -> e.auth.authenticate("stk_revoked", WorkspaceRef.parse("ext:T0001")));
            assertThat(idp.introspectCalls.get()).as("inactive 不缓存").isEqualTo(4);
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void issuerAndPrefixCannotBeHijackedByAnotherAccount(TestDatabase db) throws Exception {
        Env e = env(db);
        AccountId other = new AccountId(e.d.tenancy.createAccount(new CreateAccountRequest("别人", AccountKind.PARTNER)).accountId());
        try (LocalIdp idp = new LocalIdp()) {
            e.federation.upsert(e.d.account, new IdpConfig(ISSUER, IdpKind.JWT, idp.base() + "/jwks", "opskit", null, null, null, null, MAPPING, true));
            e.federation.upsert(e.d.account, new IdpConfig("sh", IdpKind.INTROSPECT, null, null, idp.base() + "/introspect", null, null, "stk_", MAPPING, true));
            assertThatThrownBy(() -> e.federation.upsert(other, new IdpConfig(ISSUER, IdpKind.JWT, idp.base() + "/jwks", null, null, null, null, null, MAPPING, true)))
                    .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.VALIDATION);
            assertThatThrownBy(() -> e.federation.upsert(other, new IdpConfig("sh2", IdpKind.INTROSPECT, null, null, idp.base() + "/introspect", null, null, "stk_", MAPPING, true)))
                    .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.VALIDATION);
        }
    }

    private static String sign(LocalIdp idp, JWTClaimsSet c, RSAKey k) {
        try {
            return idp.sign(c, k);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
