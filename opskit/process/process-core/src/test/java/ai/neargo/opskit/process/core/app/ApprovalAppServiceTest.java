package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.ApprovalMode;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.event.ApprovalCompleted;
import ai.neargo.opskit.process.api.model.ApprovalView;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.core.domain.definition.SampleProcesses;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.core.support.ProcessFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O2-5：plan · 或签 / 会签（组员快照）· 五条规则各一条负对照 · 轮次 · 撤回 · 校验顺序 409 → 403 → 422。 */
class ApprovalAppServiceTest {

    record Env(ProcessFixture f, InstanceAppService instances, ApprovalAppService approvals, List<DomainEvent> events, Map<String, List<String>> groups) {
        Actor member(String id, Set<String> roles, String... groups) {
            return new Actor(f.tenant, Optional.of(PrincipalRef.member(id)), Optional.empty(), Set.of(groups), roles, Set.of(), Set.of(), Set.of());
        }

        Actor requester() {
            return member("mb_bd", Set.of());
        }

        Actor ops(String id) {
            return member(id, Set.of("contract-approver"));
        }

        String submit(Map<String, Object> fields) {
            String no = instances.start(requester(), new StartRequest("contract_approval", null, fields, Map.of(), Set.of())).instanceNo();
            instances.transition(requester(), no, "submit", TransitionRequest.empty());
            return no;
        }

        String approvalOf(String instanceNo) {
            return f.jdbc.sql("SELECT approval_no FROM proc_approval WHERE instance_no = ?").param(instanceNo).query(String.class).single();
        }

        List<String> openTasks(String approvalNo) {
            return f.jdbc.sql("SELECT task_no FROM proc_task WHERE approval_no = ? AND status IN ('OPEN','CLAIMED','ASSIGNED') ORDER BY task_no")
                    .param(approvalNo).query(String.class).list();
        }

        String taskOf(String approvalNo, String assignee) {
            return f.jdbc.sql("SELECT task_no FROM proc_task WHERE approval_no = ? AND assignee = ? AND status = 'ASSIGNED'")
                    .params(approvalNo, assignee).query(String.class).single();
        }

        String status(String instanceNo) {
            return instances.get(f.system(), instanceNo).status();
        }
    }

    private static Env env(TestDatabase db, ProcessDef def) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
        defs.publish(f.system(), f.workspace, def, null, "API");
        List<DomainEvent> events = new CopyOnWriteArrayList<>();
        Map<String, List<String>> groups = new java.util.concurrent.ConcurrentHashMap<>(Map.of("finance", List.of("mb_f1", "mb_f2")));
        Directory dir = new Directory() {
            @Override
            public List<String> membersOf(TenantContext t, String g) {
                return groups.getOrDefault(g, List.of());
            }

            @Override
            public Optional<Integer> claimTimeoutSec(TenantContext t, String g) {
                return Optional.empty();
            }
        };
        BizNoGenerator ids = new BizNoGenerator(f.clock);
        TaskAppService tasks = new TaskAppService(f.store.tasks(), f.store.participants(), dir, (n, id, at) -> { }, events::add, ids, f.clock, f.tx);
        AtomicReference<InstanceAppService> ref = new AtomicReference<>();
        ApprovalAppService approvals = new ApprovalAppService(defs, f.store.approvals(), f.store.tasks(), f.store.instances(), f.store.participants(),
                f.store.logs(), tasks, ref::get, dir, events::add, ids, f.clock, f.tx);
        InstanceAppService inst = new InstanceAppService(defs, f.store.instances(), f.store.participants(), f.store.scopes(), f.store.logs(),
                List.of(tasks, approvals), Optional.of(tasks), events::add, ids, f.clock, f.tx);
        ref.set(inst);
        return new Env(f, inst, approvals, events, groups);
    }

    private static Env env(TestDatabase db) {
        return env(db, SampleProcesses.contractApproval());
    }

    private static Object key(Throwable ex) {
        return ((OpsKitException) ex).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void twoStepsWithACountersignThatNeedsEveryone(TestDatabase db) {
        Env e = env(db);
        String no = e.submit(Map.of("needsCosign", true));
        String ap = e.approvalOf(no);
        assertThat(e.status(no)).isEqualTo("reviewing");

        String opsTask = e.openTasks(ap).get(0);
        e.approvals.decide(e.ops("mb_ops"), ap, opsTask, true, null);
        assertThat(e.openTasks(ap)).as("会签：组员每人一个任务（进入该步时快照）").hasSize(2);

        e.approvals.decide(e.member("mb_f1", Set.of()), ap, e.taskOf(ap, "member:mb_f1"), true, null);
        assertThat(e.status(no)).as("会签没齐，还在审批中").isEqualTo("reviewing");
        ApprovalView v = e.approvals.decide(e.member("mb_f2", Set.of()), ap, e.taskOf(ap, "member:mb_f2"), true, null);

        assertThat(v.status()).isEqualTo("APPROVED");
        assertThat(e.status(no)).as("通过 → 门出口 approved → 生效确认中").isEqualTo("activating");
        assertThat(e.events).filteredOn(ev -> ev instanceof ApprovalCompleted).singleElement()
                .satisfies(ev -> assertThat(ev.idemKey()).isEqualTo(ap + ":1"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void conditionalStepIsSkippedWhenItsConditionDoesNotHold(TestDatabase db) {
        Env e = env(db);
        String no = e.submit(Map.of("needsCosign", false));
        String ap = e.approvalOf(no);
        ApprovalView v = e.approvals.decide(e.ops("mb_ops"), ap, e.openTasks(ap).get(0), true, null);
        assertThat(v.status()).isEqualTo("APPROVED");
        assertThat(v.rounds().get(0).steps()).extracting(ApprovalView.StepView::state).containsExactly("DONE", "SKIPPED");
        assertThat(e.status(no)).isEqualTo("activating");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void rejectNeedsAReasonAndAStaleTaskIsAConflictFirst(TestDatabase db) {
        Env e = env(db);
        String no = e.submit(Map.of("needsCosign", true));
        String ap = e.approvalOf(no);
        String task = e.openTasks(ap).get(0);

        assertThatThrownBy(() -> e.approvals.decide(e.ops("mb_ops"), ap, task, false, " "))
                .extracting(ApprovalAppServiceTest::key).isEqualTo(ProcessErrors.REASON_REQUIRED);
        e.approvals.decide(e.ops("mb_ops"), ap, task, false, "分成比例不合规");
        assertThat(e.status(no)).isEqualTo("rejected");
        // 409 先于 422：任务已处理，即使这次又没写原因，也先告诉你「请刷新」
        assertThatThrownBy(() -> e.approvals.decide(e.ops("mb_ops2"), ap, task, false, null))
                .extracting(ApprovalAppServiceTest::key).isEqualTo(CommonErrors.STATE_CHANGED);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void requesterCannotApproveTheirOwnAndTheViewSaysWhy(TestDatabase db) {
        Env e = env(db);
        String no = e.submit(Map.of("needsCosign", false));
        String ap = e.approvalOf(no);
        Actor selfApprover = e.member("mb_bd", Set.of("contract-approver"));

        assertThatThrownBy(() -> e.approvals.decide(selfApprover, ap, e.openTasks(ap).get(0), true, null))
                .extracting(ApprovalAppServiceTest::key).isEqualTo(ProcessErrors.SELF_REVIEW);
        ApprovalView.Mine mine = e.approvals.get(selfApprover, ap).mine();
        assertThat(mine.canDecide()).isFalse();
        assertThat(mine.whyNot()).isEqualTo(ProcessErrors.SELF_REVIEW.key());
        assertThat(mine.canWithdraw()).isTrue();
        assertThat(e.approvals.get(e.ops("mb_ops"), ap).mine().canDecide()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void theSamePersonCannotDecideTwoStepsOfOneRound(TestDatabase db) {
        Env e = env(db);
        e.groups.put("finance", List.of("mb_both", "mb_f2"));
        String no = e.submit(Map.of("needsCosign", true));
        String ap = e.approvalOf(no);
        Actor both = e.member("mb_both", Set.of("contract-approver"));
        e.approvals.decide(both, ap, e.openTasks(ap).get(0), true, null);

        assertThatThrownBy(() -> e.approvals.decide(both, ap, e.taskOf(ap, "member:mb_both"), true, null))
                .extracting(ApprovalAppServiceTest::key).isEqualTo(ProcessErrors.CROSS_STEP);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void onlyTheRequesterWithdrawsAndResubmittingStartsRoundTwoKeepingRoundOne(TestDatabase db) {
        Env e = env(db);
        String no = e.submit(Map.of("needsCosign", false));
        String ap = e.approvalOf(no);
        assertThatThrownBy(() -> e.approvals.withdraw(e.ops("mb_ops"), ap))
                .extracting(ApprovalAppServiceTest::key).isEqualTo(ProcessErrors.WITHDRAW_FORBIDDEN);

        e.approvals.withdraw(e.requester(), ap);
        assertThat(e.status(no)).isEqualTo("draft");
        assertThatThrownBy(() -> e.approvals.withdraw(e.requester(), ap))
                .extracting(ApprovalAppServiceTest::key).as("已撤回再撤：409").isEqualTo(CommonErrors.STATE_CHANGED);

        e.instances.transition(e.requester(), no, "submit", TransitionRequest.empty());
        ApprovalView v = e.approvals.get(e.requester(), ap);
        assertThat(e.approvalOf(no)).as("同一个门：仍是同一张审批单").isEqualTo(ap);
        assertThat(v.currentRound()).isEqualTo(2);
        assertThat(v.rounds()).extracting(ApprovalView.RoundView::result).containsExactly("WITHDRAWN", null);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void concurrentDecisionsOnAnAnyStepHaveOneWinner(TestDatabase db) throws Exception {
        Env e = env(db);
        String no = e.submit(Map.of("needsCosign", false));
        String ap = e.approvalOf(no);
        String task = e.openTasks(ap).get(0);
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Actor who = e.ops("mb_ops" + i);
            results.add(pool.submit((Callable<String>) () -> {
                go.await();
                try {
                    e.approvals.decide(who, ap, task, true, null);
                    return "ok";
                } catch (OpsKitException ex) {
                    return ex.errorKey().key();
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> r : results) {
            outcomes.add(r.get());
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn("ok"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("ok")).allMatch(CommonErrors.STATE_CHANGED.key()::equals);
        assertThat(e.status(no)).isEqualTo("activating");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void allStepsSkippedApprovesOnTheSpotAfterTheEnteringTransitionCompletes(TestDatabase db) {
        ProcessDef base = SampleProcesses.contractApproval();
        GateDef.Approval g = (GateDef.Approval) base.statuses().get(1).gate();
        GateDef.Approval allConditional = new GateDef.Approval(List.of(new ApprovalStepDef("ops", null, new Condition.Compare("needsOps", Op.EQ, true),
                new ApproverRef(ApproverKind.ROLE, List.of("contract-approver")), ApprovalMode.ANY, null)), g.rules(), g.onApproved(), g.onRejected(), g.onWithdrawn());
        List<StatusDef> statuses = new ArrayList<>(base.statuses());
        statuses.set(1, new StatusDef("reviewing", statuses.get(1).name(), statuses.get(1).category(), allConditional));
        Env e = env(db, new ProcessDef(base.code(), base.name(), base.initialStatus(), statuses, base.transitions(), base.participantRoles()));

        String no = e.submit(Map.of("needsOps", false));

        assertThat(e.status(no)).isEqualTo("activating");
        List<String> actions = e.f.jdbc.sql("SELECT action FROM proc_transition_log WHERE entity_kind = 'INSTANCE' AND instance_no = ? ORDER BY id")
                .param(no).query(String.class).list();
        assertThat(actions).as("先完整走完 submit，再执行门出口 approved").containsExactly("created", "submit", "approved");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void emptyCountersignGroupFailsLoudlyAndRollsBack(TestDatabase db) {
        Env e = env(db);
        e.groups.put("finance", List.of());
        String no = e.submit(Map.of("needsCosign", true));
        String ap = e.approvalOf(no);
        String opsTask = e.openTasks(ap).get(0);
        assertThatThrownBy(() -> e.approvals.decide(e.ops("mb_ops"), ap, opsTask, true, null))
                .extracting(ApprovalAppServiceTest::key).isEqualTo(ProcessErrors.NO_APPROVER);
        assertThat(e.openTasks(ap)).as("整个决定回滚：运营任务仍待办").containsExactly(opsTask);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void leavingTheGateByCancelWithdrawsTheApproval(TestDatabase db) {
        Env e = env(db);
        String no = e.submit(Map.of("needsCosign", false));
        String ap = e.approvalOf(no);
        e.instances.transition(e.requester(), no, "cancel", new TransitionRequest(Map.of(), "不签了"));
        assertThat(e.approvals.get(e.requester(), ap).status()).isEqualTo("WITHDRAWN");
        assertThat(e.openTasks(ap)).isEmpty();
    }
}
