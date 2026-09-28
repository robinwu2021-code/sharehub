package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.api.model.query.ApprovalQuery;
import ai.neargo.opskit.process.api.model.query.ApprovalRow;
import ai.neargo.opskit.process.api.model.query.QueueCount;
import ai.neargo.opskit.process.api.model.query.QueueTask;
import ai.neargo.opskit.process.api.model.query.TaskQuery;
import ai.neargo.opskit.process.api.model.query.TodoCount;
import ai.neargo.opskit.process.api.model.query.TodoItem;
import ai.neargo.opskit.process.api.model.query.TodoQuery;
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

/** 21/07 O2-9：待办（候选 ∩ 可见）· 队列与计数 · 审批列表与计数；计数与列表同口径；看不见的不出现（负对照）。 */
class ProcessQueryServiceTest {

    record Env(ProcessFixture f, InstanceAppService instances, TaskAppService tasks, ApprovalAppService approvals, ProcessQueryService q) {
        Actor member(String id, Set<String> groups, Set<String> scopes, Set<String> perms) {
            return new Actor(f.tenant, Optional.of(PrincipalRef.member(id)), Optional.empty(), groups, Set.of(), Set.of(), perms, scopes);
        }

        Actor fieldOps(String id, String... scopes) {
            return member(id, Set.of("field-ops"), Set.of(scopes), Set.of());
        }

        Actor plain(String id) {
            return member(id, Set.of(), Set.of(), Set.of());
        }

        /** 派到队列组 field-ops 的一张单；scopes 非空时打上范围标签。 */
        String dispatched(String requester, String subjectRef, Set<String> scopes) {
            Actor r = plain(requester);
            String no = instances.start(r, new StartRequest("dispatching", new SubjectRef("device", subjectRef, Map.of("title", "设备 " + subjectRef)),
                    Map.of(), Map.of(), scopes)).instanceNo();
            instances.transition(r, no, "submit", TransitionRequest.empty());
            return no;
        }

        String taskOf(String instanceNo) {
            return f.jdbc.sql("SELECT task_no FROM proc_task WHERE instance_no = ? AND status IN ('OPEN','CLAIMED','ASSIGNED')")
                    .param(instanceNo).query(String.class).single();
        }

        Page<TodoItem> todos(Actor a, String origin, Boolean claimable) {
            return q.todos(a, new TodoQuery(origin, claimable, 1, 50));
        }

        Page<QueueTask> tasks(Actor a, String group, List<String> status, Boolean claimed, String assignee) {
            return q.tasks(a, new TaskQuery(group, status, claimed, assignee, 1, 50));
        }
    }

    private static Env env(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
        defs.publish(f.system(), f.workspace, TaskAppServiceTest.dispatching(), null, "API");
        defs.publish(f.system(), f.workspace, SampleProcesses.contractApproval(), null, "API");
        Directory dir = new Directory() {
            @Override
            public List<String> membersOf(TenantContext t, String g) {
                return List.of();
            }

            @Override
            public Optional<Integer> claimTimeoutSec(TenantContext t, String g) {
                return "field-ops".equals(g) ? Optional.of(600) : Optional.empty();
            }

            @Override
            public boolean isQueue(TenantContext t, String g) {
                return g.equals("field-ops") || g.equals("night-ops");
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
        return new Env(f, inst, tasks, approvals, new ProcessQueryService(defs, f.store.queries(), dir));
    }

    private static List<FieldError> errors(Throwable ex) {
        assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.VALIDATION);
        return ((OpsKitException) ex).fieldErrors();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void todosAreCandidateMatchIntersectVisible(TestDatabase db) {
        Env e = env(db);
        String open = e.dispatched("mb_req", "D-1", Set.of());
        String siteA = e.dispatched("mb_req", "D-2", Set.of("site:A"));

        assertThat(e.todos(e.fieldOps("mb_a", "site:A"), "ALL", null).list()).extracting(TodoItem::instanceNo).containsExactlyInAnyOrder(open, siteA);
        // 负对照：同样是候选，但没有 site:A 的范围 —— 看不见那张单，也就不进待办
        assertThat(e.todos(e.fieldOps("mb_b"), "ALL", null).list()).extracting(TodoItem::instanceNo).containsExactly(open);
        assertThat(e.todos(e.plain("mb_outsider"), "ALL", null).total()).as("不是候选").isZero();
        assertThat(e.todos(e.f.system(), "ALL", null).total()).as("应用自身没有「我的」待办").isZero();
        TodoItem item = e.todos(e.fieldOps("mb_b"), "PROCESS", true).list().get(0);
        assertThat(item.claimable()).isTrue();
        assertThat(item.subject().ref()).isEqualTo("D-1");
        assertThat(item.processName().resolve("zh", "en")).isEqualTo("派单");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void todoCountsMatchTheListsAndFollowAClaim(TestDatabase db) {
        Env e = env(db);
        String n1 = e.dispatched("mb_req", "D-1", Set.of());
        e.dispatched("mb_req", "D-2", Set.of());
        e.dispatched("mb_req", "D-3", Set.of("site:B"));
        Actor a = e.fieldOps("mb_a");
        Actor b = e.fieldOps("mb_b");
        e.tasks.claim(a, e.taskOf(n1));

        assertThat(e.q.todoCount(a)).isEqualTo(new TodoCount(2, 2, 0, 1));
        assertThat(e.q.todoCount(b)).as("被 a 领走的那条从 b 的待办里消失").isEqualTo(new TodoCount(1, 1, 0, 1));
        for (Actor who : List.of(a, b)) {
            TodoCount c = e.q.todoCount(who);
            assertThat(e.todos(who, "ALL", null).total()).isEqualTo(c.all());
            assertThat(e.todos(who, "PROCESS", null).total()).isEqualTo(c.process());
            assertThat(e.todos(who, "TICKET", null).total()).isEqualTo(c.ticket());
            assertThat(e.todos(who, "ALL", true).total()).isEqualTo(c.claimable());
        }
        assertThat(e.todos(a, "ALL", false).list()).singleElement().satisfies(t -> {
            assertThat(t.instanceNo()).isEqualTo(n1);
            assertThat(t.claimable()).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void queueListAndCountsShareOneDefinition(TestDatabase db) {
        Env e = env(db);
        String n1 = e.dispatched("mb_req", "D-1", Set.of());
        e.dispatched("mb_req", "D-2", Set.of());
        e.dispatched("mb_req", "D-3", Set.of());
        Actor a = e.member("mb_a", Set.of("field-ops", "night-ops"), Set.of(), Set.of());
        e.tasks.claim(e.fieldOps("mb_other"), e.taskOf(n1));

        List<QueueCount> counts = e.q.queueCounts(a);
        assertThat(counts).containsExactly(new QueueCount("field-ops", 2, 0), new QueueCount("night-ops", 0, 0));
        Page<QueueTask> open = e.tasks(a, "field-ops", List.of("OPEN"), false, null);
        assertThat(open.total()).as("待领数 = 队列列表条数").isEqualTo(2);
        assertThat(open.list()).allSatisfy(t -> assertThat(t.claimedBy()).isNull());

        e.tasks.claim(a, open.list().get(0).taskNo());
        assertThat(e.q.queueCounts(a)).startsWith(new QueueCount("field-ops", 1, 1));
        Page<QueueTask> mine = e.tasks(a, null, null, true, "me");
        assertThat(mine.list()).singleElement().satisfies(t -> {
            assertThat(t.claimedBy()).isEqualTo("member:mb_a");
            assertThat(t.claimExpiresAtMs()).isEqualTo(e.f.clock.millis() + 600_000);
        });
        assertThat(e.tasks(e.plain("mb_outsider"), "field-ops", null, null, null).total()).as("不是候选也不在名下").isZero();
        assertThat(e.tasks(e.f.system(), "field-ops", null, null, null).total()).as("应用自身不限").isEqualTo(3);
        assertThat(e.tasks(e.f.system(), null, null, null, "me").total()).as("应用自身问「我的」").isZero();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void approvalListIsLimitedToThoseInvolved(TestDatabase db) {
        Env e = env(db);
        Actor bd = e.plain("mb_bd");
        String no = e.instances.start(bd, new StartRequest("contract_approval", new SubjectRef("contract", "C-9", Map.of()),
                Map.of("needsCosign", false), Map.of(), Set.of())).instanceNo();
        e.instances.transition(bd, no, "submit", TransitionRequest.empty());
        Actor ops = new Actor(e.f.tenant, Optional.of(PrincipalRef.member("mb_ops")), Optional.empty(), Set.of(), Set.of("contract-approver"),
                Set.of(), Set.of(), Set.of());

        ApprovalQuery all = new ApprovalQuery(null, null, null, null, null, 1, 20);
        ApprovalRow row = e.q.approvals(bd, all).list().get(0);
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.currentStep()).isEqualTo("ops");
        assertThat(row.currentStepName().resolve("zh", "en")).isEqualTo("运营审批");
        assertThat(row.processName().resolve("zh", "en")).isEqualTo("合同审批");
        assertThat(row.stepStartedAtMs()).isEqualTo(e.f.clock.millis());
        assertThat(row.finishedAtMs()).isNull();
        assertThat(e.q.approvals(ops, all).total()).as("审批候选").isEqualTo(1);
        assertThat(e.q.approvals(e.plain("mb_stranger"), all).total()).as("负对照：无关的人").isZero();
        assertThat(e.q.approvals(e.member("mb_auditor", Set.of(), Set.of(), Set.of(ProcessQueryService.APPROVAL_VIEW_ALL)), all).total()).isEqualTo(1);
        assertThat(e.q.approvals(ops, new ApprovalQuery(null, null, null, null, "C-9", 1, 20)).total()).as("关键词按业务编号").isEqualTo(1);
        assertThat(e.q.approvals(ops, new ApprovalQuery(null, null, "mb_someone_else", null, null, 1, 20)).total()).isZero();

        String task = e.f.jdbc.sql("SELECT task_no FROM proc_task WHERE approval_no = ?").param(row.approvalNo()).query(String.class).single();
        e.approvals.decide(ops, row.approvalNo(), task, true, null);
        ApprovalRow done = e.q.approvals(ops, all).list().get(0);
        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(done.finishedAtMs()).isEqualTo(e.f.clock.millis());
        assertThat(done.currentStep()).isNull();
        assertThat(e.q.approvals(ops, all).total()).as("处理过的人事后仍看得见").isEqualTo(1);

        Map<String, Long> counts = e.q.approvalCount(bd, all);
        assertThat(counts).containsExactly(Map.entry("PENDING", 0L), Map.entry("APPROVED", 1L), Map.entry("REJECTED", 0L), Map.entry("WITHDRAWN", 0L));
        assertThat(e.q.approvals(bd, new ApprovalQuery(List.of("APPROVED"), null, null, null, null, 1, 20)).total()).isEqualTo(counts.get("APPROVED"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void outOfRangeInputIsA422NotASilentTruncation(TestDatabase db) {
        Env e = env(db);
        Actor a = e.fieldOps("mb_a");
        assertThatThrownBy(() -> e.q.todos(a, new TodoQuery(null, null, 1, 201))).satisfies(ex ->
                assertThat(errors(ex)).extracting(FieldError::path).containsExactly("size"));
        assertThatThrownBy(() -> e.q.tasks(a, new TaskQuery(null, List.of("OPEN", "DOING"), null, null, 1, 20))).satisfies(ex ->
                assertThat(errors(ex)).extracting(FieldError::path).containsExactly("status[1]"));
        assertThatThrownBy(() -> e.q.todos(a, new TodoQuery("EVERYTHING", null, 1, 20))).satisfies(ex ->
                assertThat(errors(ex)).extracting(FieldError::path).containsExactly("origin"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void approvalsOutsideMyScopeAreHiddenFromListDetailAndDecision(TestDatabase db) {
        Env e = env(db);
        Actor bd = e.plain("mb_bd");
        String no = e.instances.start(bd, new StartRequest("contract_approval", new SubjectRef("contract", "C-B", Map.of()),
                Map.of("needsCosign", false), Map.of(), Set.of("site:B"))).instanceNo();
        e.instances.transition(bd, no, "submit", TransitionRequest.empty());
        String ap = e.f.jdbc.sql("SELECT approval_no FROM proc_approval WHERE instance_no = ?").param(no).query(String.class).single();
        String task = e.f.jdbc.sql("SELECT task_no FROM proc_task WHERE approval_no = ?").param(ap).query(String.class).single();
        Actor opsA = new Actor(e.f.tenant, Optional.of(PrincipalRef.member("mb_ops")), Optional.empty(), Set.of(), Set.of("contract-approver"),
                Set.of(), Set.of(), Set.of("site:A"));
        Actor opsB = new Actor(e.f.tenant, Optional.of(PrincipalRef.member("mb_ops")), Optional.empty(), Set.of(), Set.of("contract-approver"),
                Set.of(), Set.of(), Set.of("site:B"));
        ApprovalQuery all = new ApprovalQuery(null, null, null, null, null, 1, 20);

        // 负对照：是候选，但范围不对 —— 列表、详情、决定三处一致按不存在
        assertThat(e.q.approvals(opsA, all).total()).isZero();
        assertThat(e.q.approvalCount(opsA, all).get("PENDING")).isZero();
        assertThatThrownBy(() -> e.approvals.get(opsA, ap)).satisfies(ex -> assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.NOT_FOUND));
        assertThatThrownBy(() -> e.approvals.decide(opsA, ap, task, true, null))
                .satisfies(ex -> assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.NOT_FOUND));
        assertThat(e.todos(opsA, "ALL", null).total()).as("与待办同口径").isZero();

        Actor auditorB = e.member("mb_aud", Set.of(), Set.of("site:B"), Set.of(ProcessQueryService.APPROVAL_VIEW_ALL));
        Actor auditorA = e.member("mb_aud", Set.of(), Set.of("site:A"), Set.of(ProcessQueryService.APPROVAL_VIEW_ALL));
        assertThat(e.q.approvals(auditorB, all).total()).as("approval:view:all：范围内的全部").isEqualTo(1);
        assertThat(e.q.approvals(auditorA, all).total()).as("approval:view:all 不越过数据范围").isZero();
        assertThatThrownBy(() -> e.approvals.get(auditorA, ap)).extracting(x -> ((OpsKitException) x).errorKey()).isEqualTo(CommonErrors.NOT_FOUND);
        assertThat(e.approvals.get(auditorB, ap).approvalNo()).isEqualTo(ap);
        Actor opsAny = new Actor(e.f.tenant, Optional.of(PrincipalRef.member("mb_ops")), Optional.empty(), Set.of(), Set.of("contract-approver"),
                Set.of(), Set.of(), Set.of("*"));
        assertThat(e.q.approvals(opsAny, all).total()).as("scopes:[\"*\"] = 数据范围不设限").isEqualTo(1);
        assertThat(e.todos(opsAny, "ALL", null).total()).isEqualTo(1);
        assertThat(e.approvals.get(opsAny, ap).approvalNo()).isEqualTo(ap);
        assertThat(e.q.approvals(e.member("mb_stranger", Set.of(), Set.of("*"), Set.of()), all).total()).as("但 * 不放开候选 / 参与者条件").isZero();
        assertThat(e.q.approvals(bd, all).total()).as("提交人不受范围限制").isEqualTo(1);
        assertThat(e.q.approvals(opsB, all).total()).isEqualTo(1);
        assertThat(e.approvals.decide(opsB, ap, task, true, null).status()).isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void claimableMeansMustClaimFirstSoPlainApprovalsAreNot(TestDatabase db) {
        Env e = env(db);
        e.dispatched("mb_req", "D-1", Set.of()); // 队列里的办理任务：要先领
        Actor bd = e.plain("mb_bd");
        String no = e.instances.start(bd, new StartRequest("contract_approval", null, Map.of("needsCosign", false), Map.of(), Set.of())).instanceNo();
        e.instances.transition(bd, no, "submit", TransitionRequest.empty());
        Actor both = new Actor(e.f.tenant, Optional.of(PrincipalRef.member("mb_x")), Optional.empty(), Set.of("field-ops"), Set.of("contract-approver"),
                Set.of(), Set.of(), Set.of());

        TodoCount c = e.q.todoCount(both);
        assertThat(c).isEqualTo(new TodoCount(2, 2, 0, 1));
        assertThat(e.todos(both, "ALL", true).list()).singleElement().satisfies(t -> assertThat(t.kind()).isEqualTo("WORK"));
        assertThat(e.todos(both, "ALL", false).list()).as("普通审批可直接决定：在「待我处理」页").singleElement()
                .satisfies(t -> {
                    assertThat(t.kind()).isEqualTo("APPROVAL");
                    assertThat(t.claimable()).isFalse();
                });
        assertThat(e.todos(both, "ALL", true).total() + e.todos(both, "ALL", false).total()).as("两页不重不漏").isEqualTo(c.all());
    }
}
