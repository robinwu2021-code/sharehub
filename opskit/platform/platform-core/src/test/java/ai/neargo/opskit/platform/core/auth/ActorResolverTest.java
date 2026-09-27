package ai.neargo.opskit.platform.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.CandidateKeys;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.api.app.AuthenticatedApp;
import ai.neargo.opskit.platform.api.app.CreateAppRequest;
import ai.neargo.opskit.platform.api.directory.OnBehalfOf;
import ai.neargo.opskit.platform.api.directory.RoleBinding;
import ai.neargo.opskit.platform.api.directory.RoleUpsert;
import ai.neargo.opskit.platform.core.support.DirectoryEnv;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-6：应用调用 → Actor。 */
class ActorResolverTest {

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void appWithoutOnBehalfOfIsTheAppItself(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        AuthenticatedApp app = e.apps.authenticate(e.apps.createApp(e.account, new CreateAppRequest("x", true, List.of(), null)).credential());

        Actor a = e.actors.forApp(app, WorkspaceRef.parse("ext:T0001"), null);

        assertThat(a.isAppItself()).isTrue();
        assertThat(a.tenant().workspaceId()).isEqualTo(e.workspace);
        assertThat(a.app()).contains(app.appId());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void onBehalfOfProvisionsTheMemberAndMergesDirectoryWithClaims(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        e.directory.upsertRole(e.system(), WorkspaceId.TEMPLATE, new RoleUpsert("agent", Map.of(), Set.of("ticket:work")));
        e.directory.bindRole(e.system(), new RoleBinding("agent", "group:field-ops"));
        AuthenticatedApp app = e.apps.authenticate(e.apps.createApp(e.account, new CreateAppRequest("x", true, List.of(), null)).credential());

        Actor a = e.actors.forApp(app, WorkspaceRef.parse("ext:T0001"),
                new OnBehalfOf("E107", "Ali", Set.of("field-ops"), Set.of("site:ST0311"), Set.of("location:contract:audit")));

        assertThat(a.member()).isPresent();
        assertThat(a.groups()).containsExactly("field-ops");
        assertThat(a.productPermissions()).as("组绑定的角色展开成产品权限").containsExactly("ticket:work");
        assertThat(a.scopes()).containsExactly("site:ST0311");
        assertThat(CandidateKeys.of(a)).contains("group:field-ops", "role:agent", "perm:location:contract:audit");

        Actor again = e.actors.forApp(app, WorkspaceRef.parse("ext:T0001"), OnBehalfOf.of("E107"));
        assertThat(again.member()).isEqualTo(a.member());
        assertThat(again.groups()).as("没给 groups = 不改目录里令牌来源的组").containsExactly("field-ops");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void workspaceOutsideTheGrantIsNotFound(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        String other = e.tenancy.createWorkspace(e.account, new CreateWorkspaceRequest("另一个", "T0002", null, null)).workspaceId();
        AuthenticatedApp limited = e.apps.authenticate(
                e.apps.createApp(e.account, new CreateAppRequest("x", false, List.of(e.workspace.value()), null)).credential());

        assertThat(e.actors.forApp(limited, WorkspaceRef.parse("ext:T0001"), null).tenant().workspaceId()).isEqualTo(e.workspace);
        assertThatThrownBy(() -> e.actors.forApp(limited, WorkspaceRef.parse(other), OnBehalfOf.of("E1")))
                .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.NOT_FOUND);
        assertThat(e.f.jdbc.sql("SELECT COUNT(*) FROM plat_member").query(Integer.class).single())
                .as("被拒的调用不留下任何开通痕迹").isZero();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void disabledMemberCannotAct(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        AuthenticatedApp app = e.apps.authenticate(e.apps.createApp(e.account, new CreateAppRequest("x", true, List.of(), null)).credential());
        e.actors.forApp(app, WorkspaceRef.parse("ext:T0001"), OnBehalfOf.of("E107"));
        e.f.jdbc.sql("UPDATE plat_member SET status = 'DISABLED'").update();

        assertThatThrownBy(() -> e.actors.forApp(app, WorkspaceRef.parse("ext:T0001"), OnBehalfOf.of("E107")))
                .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.UNAUTHENTICATED);
    }
}
