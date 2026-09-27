package ai.neargo.opskit.platform.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.PlatformErrors;
import ai.neargo.opskit.platform.api.account.AccountKind;
import ai.neargo.opskit.platform.api.account.CreateAccountRequest;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.platform.core.support.PlatformFixture;
import ai.neargo.opskit.platform.store.dao.AccountDao;
import ai.neargo.opskit.platform.store.dao.WorkspaceDao;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-1：账户 · 空间 · 外部租户号寻址 · 跨账户即不存在。 */
class TenancyAppServiceTest {

    private static TenancyAppService service(PlatformFixture f) {
        return new TenancyAppService(new AccountDao(f.jdbc), new WorkspaceDao(f.jdbc), f.ids, f.clock, f.tx);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void workspaceIsAddressableByIdAndByPartnerTenantRef(TestDatabase db) {
        TenancyAppService svc = service(new PlatformFixture(db));
        AccountId partner = new AccountId(svc.createAccount(new CreateAccountRequest("ShareHub 伙伴", AccountKind.PARTNER)).accountId());

        WorkspaceView ws = svc.createWorkspace(partner, new CreateWorkspaceRequest("迪拜运营商", "T0001", "Asia/Dubai", "ar"));

        assertThat(ws.workspaceId()).startsWith("ws_");
        assertThat(svc.resolve(partner, WorkspaceRef.parse(ws.workspaceId()))).isEqualTo(ws);
        assertThat(svc.resolve(partner, WorkspaceRef.parse("ext:T0001"))).isEqualTo(ws);
        assertThat(ws.defaultZone()).isEqualTo("Asia/Dubai");
    }

    /** 负对照语义：DAO 若漏了账户条件，这里就能用别人的凭证拿到别人的空间 —— 用例会变红。 */
    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void anotherAccountSeesNothing(TestDatabase db) {
        TenancyAppService svc = service(new PlatformFixture(db));
        AccountId a = new AccountId(svc.createAccount(new CreateAccountRequest("A", AccountKind.PARTNER)).accountId());
        AccountId b = new AccountId(svc.createAccount(new CreateAccountRequest("B", AccountKind.PARTNER)).accountId());
        WorkspaceView ws = svc.createWorkspace(a, new CreateWorkspaceRequest("A 的客户", "T0001", null, null));

        for (String ref : new String[] {ws.workspaceId(), "ext:T0001"}) {
            assertThatThrownBy(() -> svc.resolve(b, WorkspaceRef.parse(ref)))
                    .isInstanceOf(OpsKitException.class)
                    .extracting(e -> ((OpsKitException) e).errorKey()).isEqualTo(CommonErrors.NOT_FOUND);
        }
        assertThat(svc.listWorkspaces(b)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void partnerTenantRefIsUniquePerAccountOnly(TestDatabase db) {
        TenancyAppService svc = service(new PlatformFixture(db));
        AccountId a = new AccountId(svc.createAccount(new CreateAccountRequest("A", AccountKind.PARTNER)).accountId());
        AccountId b = new AccountId(svc.createAccount(new CreateAccountRequest("B", AccountKind.PARTNER)).accountId());
        svc.createWorkspace(a, new CreateWorkspaceRequest("一", "T0001", null, null));

        assertThatThrownBy(() -> svc.createWorkspace(a, new CreateWorkspaceRequest("二", "T0001", null, null)))
                .extracting(e -> ((OpsKitException) e).errorKey()).isEqualTo(PlatformErrors.WORKSPACE_REF_TAKEN);
        // 另一个账户用同一个租户号互不影响；不给租户号的空间可以有多个（唯一键里 NULL 不冲突）
        svc.createWorkspace(b, new CreateWorkspaceRequest("B 一", "T0001", null, null));
        svc.createWorkspace(a, new CreateWorkspaceRequest("无号一", null, null, null));
        svc.createWorkspace(a, new CreateWorkspaceRequest("无号二", " ", null, null));
        assertThat(svc.listWorkspaces(a)).hasSize(3);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void reportsEveryValidationErrorAtOnce(TestDatabase db) {
        TenancyAppService svc = service(new PlatformFixture(db));
        AccountId a = new AccountId(svc.createAccount(new CreateAccountRequest("A", AccountKind.DIRECT)).accountId());

        assertThatThrownBy(() -> svc.createWorkspace(a, new CreateWorkspaceRequest(" ", null, "Mars/Olympus", "fr")))
                .isInstanceOf(OpsKitException.class)
                .satisfies(e -> assertThat(((OpsKitException) e).fieldErrors()).extracting(FieldError::path)
                        .containsExactlyInAnyOrder("name", "defaultZone", "defaultLocale"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void suspendedAccountCannotOpenWorkspaces(TestDatabase db) {
        PlatformFixture f = new PlatformFixture(db);
        TenancyAppService svc = service(f);
        AccountId a = new AccountId(svc.createAccount(new CreateAccountRequest("A", AccountKind.PARTNER)).accountId());
        f.jdbc.sql("UPDATE plat_account SET status = 'SUSPENDED' WHERE account_id = ?").param(a.value()).update();

        assertThatThrownBy(() -> svc.createWorkspace(a, new CreateWorkspaceRequest("x", null, null, null)))
                .extracting(e -> ((OpsKitException) e).errorKey()).isEqualTo(PlatformErrors.TENANT_INACTIVE);
    }
}
