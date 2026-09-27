package ai.neargo.opskit.platform.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.app.AppSecrets;
import ai.neargo.opskit.platform.api.app.CreateAppRequest;
import ai.neargo.opskit.platform.api.directory.RoleBinding;
import ai.neargo.opskit.platform.api.directory.RoleUpsert;
import ai.neargo.opskit.platform.core.support.DirectoryEnv;
import ai.neargo.opskit.platform.store.dao.AuditDao;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-11：定义发布 · 角色变更 · 凭证轮换各留一条；审计与变更同成同败；不记凭证。 */
class AuditTrailTest {

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void sensitiveChangesLeaveAnAuditEntryWithoutSecrets(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        e.directory.upsertRole(e.system(), WorkspaceId.TEMPLATE, new RoleUpsert("agent", Map.of(), Set.of("ticket:work")));
        e.directory.bindRole(e.system(), new RoleBinding("agent", "group:field-ops"));
        AppSecrets app = e.apps.createApp(e.account, new CreateAppRequest("x", true, List.of(), null));
        AppSecrets rotated = e.apps.rotate(e.account, app.app().appId());

        List<AuditDao.Entry> accountLevel = e.audit.query(e.account, "", 0, Long.MAX_VALUE, 50);
        assertThat(accountLevel).extracting(AuditDao.Entry::action)
                .containsExactlyInAnyOrder("role.upserted", "credential.issued", "credential.rotated");
        assertThat(e.audit.query(e.account, e.workspace.value(), 0, Long.MAX_VALUE, 50)).extracting(AuditDao.Entry::action)
                .containsExactly("role.bound");
        assertThat(accountLevel).allSatisfy(en -> {
            assertThat(en.detail()).doesNotContain(app.credential(), rotated.credential(), app.signingSecret(), rotated.signingSecret());
        });
        assertThat(accountLevel).filteredOn(en -> en.action().equals("role.upserted"))
                .singleElement().satisfies(en -> assertThat(en.actor()).isEqualTo("app:app_test"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void failedChangeLeavesNoAuditAndAuditOutsideATransactionIsRefused(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        assertThatThrownBy(() -> e.apps.rotate(e.account, "app_missing")).isInstanceOf(RuntimeException.class);
        assertThat(e.audit.query(e.account, "", 0, Long.MAX_VALUE, 50)).isEmpty();
        assertThatThrownBy(() -> e.audit.record(e.system(), "x", "y", Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
