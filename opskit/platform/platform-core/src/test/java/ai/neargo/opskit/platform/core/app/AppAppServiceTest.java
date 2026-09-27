package ai.neargo.opskit.platform.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.account.AccountKind;
import ai.neargo.opskit.platform.api.account.CreateAccountRequest;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.app.AppSecrets;
import ai.neargo.opskit.platform.api.app.AuthenticatedApp;
import ai.neargo.opskit.platform.api.app.CreateAppRequest;
import ai.neargo.opskit.platform.core.crypto.SecretCipherTest;
import ai.neargo.opskit.platform.core.support.PlatformFixture;
import ai.neargo.opskit.platform.store.dao.AccountDao;
import ai.neargo.opskit.platform.store.dao.AppDao;
import ai.neargo.opskit.platform.store.dao.WorkspaceDao;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-2：凭证只显示一次 · 哈希存储 · 轮换 · 可访问空间。 */
class AppAppServiceTest {

    record Env(TenancyAppService tenancy, AppAppService apps, PlatformFixture f) {
    }

    private static Env env(TestDatabase db) {
        PlatformFixture f = new PlatformFixture(db);
        WorkspaceDao ws = new WorkspaceDao(f.jdbc);
        return new Env(new TenancyAppService(new AccountDao(f.jdbc), ws, f.ids, f.clock, f.tx),
                new AppAppService(new AppDao(f.jdbc), ws, SecretCipherTest.cipher(), f.ids, f.clock, f.tx,
                        new AuditTrail(new ai.neargo.opskit.platform.store.dao.AuditDao(f.jdbc, db.dialect()), f.clock)), f);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void issuedCredentialAuthenticatesAndIsStoredOnlyAsHash(TestDatabase db) {
        Env e = env(db);
        AccountId a = new AccountId(e.tenancy.createAccount(new CreateAccountRequest("A", AccountKind.PARTNER)).accountId());
        AppSecrets issued = e.apps.createApp(a, new CreateAppRequest("ShareHub 后端", true, List.of(), "https://example.com/hooks"));

        AuthenticatedApp who = e.apps.authenticate(issued.credential());
        assertThat(who.appId()).isEqualTo(issued.app().appId());
        assertThat(who.accountId()).isEqualTo(a.value());
        assertThat(issued.app().credentialHint()).isEqualTo(issued.credential().substring(issued.credential().length() - 4));

        String stored = e.f.jdbc.sql("SELECT credential_hash FROM plat_app").query(String.class).single();
        assertThat(stored).isNotEqualTo(issued.credential()).hasSize(64).doesNotContain(issued.credential().substring(4));
        assertThat(e.apps.signingSecret(a, issued.app().appId())).isEqualTo(issued.signingSecret());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void everyBadCredentialIsTheSame401(TestDatabase db) {
        Env e = env(db);
        AccountId a = new AccountId(e.tenancy.createAccount(new CreateAccountRequest("A", AccountKind.PARTNER)).accountId());
        String good = e.apps.createApp(a, new CreateAppRequest("x", true, List.of(), null)).credential();

        for (String bad : new String[] {null, "", "Bearer " + good, good + "x", "okk_forged", "okk_" + "a".repeat(200)}) {
            assertThatThrownBy(() -> e.apps.authenticate(bad))
                    .isInstanceOf(OpsKitException.class)
                    .satisfies(ex -> {
                        assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.UNAUTHENTICATED);
                        assertThat(ex.getMessage()).isEqualTo("invalid credential");
                    });
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void rotationRevokesTheOldCredentialImmediately(TestDatabase db) {
        Env e = env(db);
        AccountId a = new AccountId(e.tenancy.createAccount(new CreateAccountRequest("A", AccountKind.PARTNER)).accountId());
        AppSecrets first = e.apps.createApp(a, new CreateAppRequest("x", true, List.of(), null));

        AppSecrets second = e.apps.rotate(a, first.app().appId());

        assertThat(e.apps.authenticate(second.credential()).appId()).isEqualTo(first.app().appId());
        assertThatThrownBy(() -> e.apps.authenticate(first.credential())).isInstanceOf(OpsKitException.class);
        assertThat(e.apps.signingSecret(a, first.app().appId())).isEqualTo(second.signingSecret()).isNotEqualTo(first.signingSecret());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void workspaceGrantsAreEnforcedAndConfinedToTheAccount(TestDatabase db) {
        Env e = env(db);
        AccountId a = new AccountId(e.tenancy.createAccount(new CreateAccountRequest("A", AccountKind.PARTNER)).accountId());
        AccountId b = new AccountId(e.tenancy.createAccount(new CreateAccountRequest("B", AccountKind.PARTNER)).accountId());
        String ws1 = e.tenancy.createWorkspace(a, new CreateWorkspaceRequest("1", null, null, null)).workspaceId();
        String ws2 = e.tenancy.createWorkspace(a, new CreateWorkspaceRequest("2", null, null, null)).workspaceId();
        String foreign = e.tenancy.createWorkspace(b, new CreateWorkspaceRequest("B", null, null, null)).workspaceId();

        AuthenticatedApp limited = e.apps.authenticate(e.apps.createApp(a, new CreateAppRequest("l", false, List.of(ws1), null)).credential());
        assertThat(limited.canAccess(ws1)).isTrue();
        assertThat(limited.canAccess(ws2)).isFalse();

        AuthenticatedApp all = e.apps.authenticate(e.apps.createApp(a, new CreateAppRequest("all", true, List.of(), null)).credential());
        String later = e.tenancy.createWorkspace(a, new CreateWorkspaceRequest("3", null, null, null)).workspaceId();
        assertThat(all.canAccess(later)).as("全空间应用覆盖之后新开的空间").isTrue();

        // 把别的账户的空间授权给自己的应用 → 按不存在拒绝
        assertThatThrownBy(() -> e.apps.createApp(a, new CreateAppRequest("x", false, List.of(ws1, foreign), null)))
                .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.NOT_FOUND);
    }
}
