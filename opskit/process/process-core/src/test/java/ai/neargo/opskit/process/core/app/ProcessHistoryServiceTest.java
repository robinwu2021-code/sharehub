package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.api.model.query.HistoryEntry;
import ai.neargo.opskit.process.api.model.query.InstanceQuery;
import ai.neargo.opskit.process.api.model.query.ProcessStats;
import ai.neargo.opskit.process.api.model.query.SubjectHistory;
import ai.neargo.opskit.process.core.domain.definition.SampleProcesses;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.core.support.ProcessFixture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O2-10：历史（含任务 / 审批流转）· 实例查询 · 按业务对象对账 · 流程统计；看不见的一律按不存在（负对照）。 */
class ProcessHistoryServiceTest {

    record Env(ProcessFixture f, InstanceAppService instances, ApprovalAppService approvals, ProcessHistoryService history) {
        Actor member(String id, Set<String> roles, Set<String> scopes, Set<String> perms) {
            return new Actor(f.tenant, Optional.of(PrincipalRef.member(id)), Optional.empty(), Set.of(), roles, Set.of(), perms, scopes);
        }

        Actor bd() {
            return member("mb_bd", Set.of(), Set.of(), Set.of());
        }

        Actor ops() {
            return member("mb_ops", Set.of("contract-approver"), Set.of(), Set.of());
        }

        String submit(String ref, Set<String> scopes) {
            String no = instances.start(bd(), new StartRequest("contract_approval", new SubjectRef("contract", ref, Map.of("title", "合同 " + ref)),
                    Map.of("needsCosign", false), Map.of(), scopes)).instanceNo();
            instances.transition(bd(), no, "submit", TransitionRequest.empty());
            return no;
        }

        void decide(String no, boolean approve) {
            String ap = f.jdbc.sql("SELECT approval_no FROM proc_approval WHERE instance_no = ? AND status = 'PENDING'").param(no).query(String.class).single();
            String task = f.jdbc.sql("SELECT task_no FROM proc_task WHERE approval_no = ? AND status = 'OPEN'").param(ap).query(String.class).single();
            approvals.decide(ops(), ap, task, approve, approve ? null : "不合规");
        }
    }

    private static Env env(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
        defs.publish(f.system(), f.workspace, SampleProcesses.contractApproval(), null, "API");
        Directory dir = new Directory() {
            @Override
            public List<String> membersOf(TenantContext t, String g) {
                return List.of();
            }

            @Override
            public Optional<Integer> claimTimeoutSec(TenantContext t, String g) {
                return Optional.empty();
            }
        };
        BizNoGenerator ids = new BizNoGenerator(f.clock);
        TaskAppService tasks = new TaskAppService(f.store.tasks(), f.store.participants(), dir, (n, id, at) -> { }, ev -> { }, ids, f.clock, f.tx);
        AtomicReference<InstanceAppService> ref = new AtomicReference<>();
        ApprovalAppService approvals = new ApprovalAppService(defs, f.store.approvals(), f.store.tasks(), f.store.instances(), f.store.participants(),
                f.store.logs(), tasks, ref::get, dir, ev -> { }, ids, f.clock, f.tx);
        InstanceAppService inst = new InstanceAppService(defs, f.store.instances(), f.store.participants(), f.store.scopes(), f.store.logs(),
                List.of(tasks, approvals), Optional.of(tasks), ev -> { }, ids, f.clock, f.tx);
        ref.set(inst);
        return new Env(f, inst, approvals, new ProcessHistoryService(defs, f.store.queries(), f.store.logs()));
    }

    private static Object key(Throwable ex) {
        return ((OpsKitException) ex).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void historyCoversInstanceTaskAndApprovalMovesAndHidesFromStrangers(TestDatabase db) {
        Env e = env(db);
        String no = e.submit("C-1", Set.of());
        e.decide(no, true);

        List<HistoryEntry> h = e.history.history(e.bd(), no);
        assertThat(h).extracting(HistoryEntry::entityKind).contains("INSTANCE", "APPROVAL");
        assertThat(h).filteredOn(x -> "INSTANCE".equals(x.entityKind())).extracting(HistoryEntry::action).containsSubsequence("created", "submit", "approved");
        assertThat(e.history.history(e.ops(), no)).as("审批过的人看得见").isNotEmpty();
        assertThat(e.history.history(e.f.system(), no)).hasSameSizeAs(h);
        assertThatThrownBy(() -> e.history.history(e.member("mb_stranger", Set.of(), Set.of(), Set.of()), no))
                .as("负对照：无关的人").extracting(ProcessHistoryServiceTest::key).isEqualTo(CommonErrors.NOT_FOUND);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void aCandidateOutsideTheScopeCannotReadTheInstance(TestDatabase db) {
        Env e = env(db);
        String no = e.submit("C-B", Set.of("site:B"));
        assertThatThrownBy(() -> e.history.history(e.member("mb_ops", Set.of("contract-approver"), Set.of("site:A"), Set.of()), no))
                .extracting(ProcessHistoryServiceTest::key).isEqualTo(CommonErrors.NOT_FOUND);
        assertThat(e.history.history(e.member("mb_ops", Set.of("contract-approver"), Set.of("site:B"), Set.of()), no)).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void instanceQueryFiltersAndValidates(TestDatabase db) {
        Env e = env(db);
        String a = e.submit("C-1", Set.of());
        String b = e.submit("C-2", Set.of());
        e.decide(b, false);

        assertThat(e.history.instances(e.f.system(), new InstanceQuery("contract_approval", null, null, true, null, null, null, null, 1, 20)).list())
                .extracting(r -> r.instanceNo()).containsExactly(a);
        assertThat(e.history.instances(e.f.system(), new InstanceQuery(null, List.of("CANCELLED"), null, null, null, null, null, null, 1, 20)).list())
                .singleElement().satisfies(r -> {
                    assertThat(r.instanceNo()).isEqualTo(b);
                    assertThat(r.statusName().resolve("zh", "en")).isEqualTo("已驳回");
                    assertThat(r.closedAtMs()).isNotNull();
                });
        assertThat(e.history.instances(e.member("mb_stranger", Set.of(), Set.of(), Set.of()),
                new InstanceQuery(null, null, null, null, null, null, null, null, 1, 20)).total()).isZero();
        assertThatThrownBy(() -> e.history.instances(e.f.system(), new InstanceQuery(null, List.of("DONE", "CLOSED"), null, null, null, null, null, null, 1, 20)))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("statusCategory[1]"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void reconcileBySubjectListsEveryAttemptWithItsApprovalAndMoves(TestDatabase db) {
        Env e = env(db);
        String first = e.submit("C-9", Set.of());
        e.decide(first, false);
        String second = e.submit("C-9", Set.of());
        e.decide(second, true);
        e.submit("C-OTHER", Set.of());

        SubjectHistory s = e.history.subject(e.f.system(), "contract", "C-9");
        assertThat(s.total()).isEqualTo(2);
        assertThat(s.truncated()).isFalse();
        assertThat(s.instances()).extracting(i -> i.instance().instanceNo()).containsExactlyInAnyOrder(first, second);
        assertThat(s.instances()).allSatisfy(i -> {
            assertThat(i.approvals()).hasSize(1);
            assertThat(i.history()).isNotEmpty().allSatisfy(h -> assertThat(h.instanceNo()).isEqualTo(i.instance().instanceNo()));
        });
        assertThat(s.instances()).extracting(i -> i.approvals().get(0).status()).containsExactlyInAnyOrder("REJECTED", "APPROVED");
        assertThat(e.history.subject(e.member("mb_stranger", Set.of(), Set.of(), Set.of()), "contract", "C-9").total()).as("成员只看自己可见的").isZero();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void statsCountByCategoryAndApprovalOutcomeAndNeedReportView(TestDatabase db) {
        Env e = env(db);
        e.submit("C-1", Set.of());
        e.decide(e.submit("C-2", Set.of()), true);
        e.decide(e.submit("C-3", Set.of()), false);

        List<ProcessStats> stats = e.history.stats(e.member("mb_boss", Set.of(), Set.of(), Set.of(ProcessHistoryService.REPORT_VIEW)), null, null, null);
        assertThat(stats).singleElement().satisfies(s -> {
            assertThat(s.processCode()).isEqualTo("contract_approval");
            assertThat(s.total()).isEqualTo(3);
            assertThat(s.open()).isEqualTo(2); // 审批中 1 + 已通过进入「生效确认中」（WAITING）1
            assertThat(s.byCategory()).containsEntry("CANCELLED", 1L).containsEntry("IN_PROGRESS", 1L).containsEntry("WAITING", 1L).containsEntry("DONE", 0L);
            assertThat(s.avgCycleMs()).as("固定时钟下关闭即刻发生").isEqualTo(0L);
            assertThat(s.approvals()).containsEntry("PENDING", 1L).containsEntry("APPROVED", 1L).containsEntry("REJECTED", 1L).containsEntry("WITHDRAWN", 0L);
            assertThat(s.processName().resolve("zh", "en")).isEqualTo("合同审批");
        });
        assertThatThrownBy(() -> e.history.stats(e.bd(), null, null, null)).extracting(ProcessHistoryServiceTest::key).isEqualTo(CommonErrors.PERMISSION_DENIED);
        assertThat(e.history.stats(e.f.system(), "nope", null, null)).isEmpty();
    }
}
