package ai.neargo.opskit.platform.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.directory.DirectorySource;
import ai.neargo.opskit.platform.api.directory.GroupKind;
import ai.neargo.opskit.platform.api.directory.GroupUpsert;
import ai.neargo.opskit.platform.api.directory.MemberUpsert;
import ai.neargo.opskit.platform.api.directory.MemberView;
import ai.neargo.opskit.platform.api.directory.RoleBinding;
import ai.neargo.opskit.platform.api.directory.RoleUpsert;
import ai.neargo.opskit.platform.api.directory.SyncBatch;
import ai.neargo.opskit.platform.core.support.DirectoryEnv;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-4 · O1-5：目录、即时开通、来源分管、模板角色、同步。 */
class DirectoryAppServiceTest {

    /**
     * 并发首次登录只开通一个成员。MySQL 系默认可重复读：撞唯一键后的再查若是普通读，读的是事务开头的快照，
     * 看不到对方刚提交的行 —— 这条用例专门钉住这个坑（PG 默认读已提交，不会暴露）。
     */
    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void concurrentFirstLoginsProvisionExactlyOneMember(TestDatabase db) throws Exception {
        DirectoryEnv e = new DirectoryEnv(db);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit((Callable<String>) () -> {
                start.await();
                return e.directory.provision(e.tenant, MemberUpsert.of("E107"), DirectorySource.JIT).memberId();
            }));
        }
        start.countDown();
        List<String> ids = new ArrayList<>();
        for (Future<String> r : results) {
            ids.add(r.get());
        }
        pool.shutdown();
        assertThat(Set.copyOf(ids)).hasSize(1);
        assertThat(e.f.jdbc.sql("SELECT COUNT(*) FROM plat_member").query(Integer.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void claimedGroupsNeverTouchConsoleMemberships(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        String m = e.directory.provision(e.tenant, MemberUpsert.of("E107"), DirectorySource.JIT).memberId();
        e.directory.sync(e.system(), new SyncBatch(SyncBatch.Mode.MERGE, List.of(), List.of(), Map.of("finance", List.of("E107")), List.of()));

        e.directory.reconcileClaimedGroups(e.tenant, m, Set.of("field-ops", "night"));
        assertThat(e.directory.effective(e.tenant, m, Set.of(), Set.of()).groups()).containsExactlyInAnyOrder("finance", "field-ops", "night");

        e.directory.reconcileClaimedGroups(e.tenant, m, Set.of("field-ops"));
        assertThat(e.directory.effective(e.tenant, m, Set.of(), Set.of()).groups())
                .as("令牌不再带 night 就移除；SYNC 来源的 finance 不受影响").containsExactlyInAnyOrder("finance", "field-ops");
        assertThat(e.directory.membersOf(e.tenant, "field-ops")).containsExactly(m);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void workspaceRoleOverridesTheAccountTemplateAndGroupsGrantRoles(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        e.directory.upsertRole(e.system(), WorkspaceId.TEMPLATE, new RoleUpsert("agent", Map.of("zh", "办理人"), Set.of("ticket:work")));
        e.directory.upsertRole(e.system(), e.workspace, new RoleUpsert("agent", Map.of("zh", "办理人"), Set.of("ticket:work", "ticket:assign")));
        e.directory.upsertRole(e.system(), WorkspaceId.TEMPLATE, new RoleUpsert("viewer", Map.of(), Set.of("report:view")));
        String m = e.directory.provision(e.tenant, MemberUpsert.of("E107"), DirectorySource.JIT).memberId();
        e.directory.reconcileClaimedGroups(e.tenant, m, Set.of("field-ops"));
        e.directory.bindRole(e.system(), new RoleBinding("agent", "group:field-ops"));
        e.directory.bindRole(e.system(), new RoleBinding("viewer", PrincipalRef.member(m).key()));

        DirectoryAppService.Effective eff = e.directory.effective(e.tenant, m, Set.of(), Set.of());
        assertThat(eff.roles()).containsExactlyInAnyOrder("agent", "viewer");
        assertThat(eff.permissions()).containsExactlyInAnyOrder("ticket:work", "ticket:assign", "report:view");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void rolesOnlyContainCatalogPermissionsAndManagementNeedsPermission(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        assertThatThrownBy(() -> e.directory.upsertRole(e.system(), e.workspace, new RoleUpsert("x", Map.of(), Set.of("location:contract:audit"))))
                .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.VALIDATION);

        Actor plainMember = new Actor(e.tenant, Optional.of(PrincipalRef.member("mb_x")), Optional.empty(),
                Set.of(), Set.of(), Set.of(), Set.of("ticket:work"), Set.of());
        assertThatThrownBy(() -> e.directory.upsertGroup(plainMember, new GroupUpsert("g", Map.of(), GroupKind.TEAM, null, null)))
                .extracting(ex -> ((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.PERMISSION_DENIED);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void replaceSyncOnlyReplacesSyncSourcedData(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        e.directory.upsertRole(e.system(), WorkspaceId.TEMPLATE, new RoleUpsert("agent", Map.of(), Set.of("ticket:work")));
        e.directory.upsertRole(e.system(), WorkspaceId.TEMPLATE, new RoleUpsert("lead", Map.of(), Set.of("ticket:assign")));
        e.directory.sync(e.system(), new SyncBatch(SyncBatch.Mode.MERGE,
                List.of(new MemberUpsert("E1", "甲", Map.of("partyType", "STAFF"), Set.of("site:ST1")), MemberUpsert.of("E2")),
                List.of(new GroupUpsert("repair", Map.of("zh", "维修组"), GroupKind.QUEUE, 1800, null)),
                Map.of("repair", List.of("E1", "E2")),
                List.of(new RoleBinding("agent", "member:@E1"), new RoleBinding("lead", "member:@E1"))));
        MemberView e1 = e.directory.provision(e.tenant, MemberUpsert.of("E1"), DirectorySource.JIT);
        e.directory.reconcileClaimedGroups(e.tenant, e1.memberId(), Set.of("repair-claimed"));

        e.directory.sync(e.system(), new SyncBatch(SyncBatch.Mode.REPLACE, List.of(), List.of(),
                Map.of("repair", List.of("E2")), List.of(new RoleBinding("agent", "member:@E1"))));

        DirectoryAppService.Effective eff = e.directory.effective(e.tenant, e1.memberId(), Set.of(), Set.of());
        assertThat(eff.groups()).as("SYNC 的 repair 被替换掉，CLAIM 的不动").containsExactly("repair-claimed");
        assertThat(eff.roles()).as("本批给了 E1 的绑定 → 按本批替换").containsExactly("agent");
        assertThat(e1.attrs()).containsEntry("partyType", "STAFF");
        assertThat(e1.scopes()).containsExactly("site:ST1");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void syncIsAllOrNothing(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        assertThatThrownBy(() -> e.directory.sync(e.system(), new SyncBatch(SyncBatch.Mode.MERGE,
                List.of(MemberUpsert.of("E1")), List.of(), Map.of(), List.of(new RoleBinding("agent", "member:@NOBODY")))))
                .isInstanceOf(OpsKitException.class);
        assertThat(e.f.jdbc.sql("SELECT COUNT(*) FROM plat_member").query(Integer.class).single()).as("前面已开通的 E1 一起回滚").isZero();
    }
}
