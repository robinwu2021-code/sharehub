package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.ActionDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.TaskView;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.core.support.ProcessFixture;
import java.time.Instant;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O2-4：候选键 · 领取 / 放回 / 指派 / 转派 · 领取超时（epoch）· 办理任务跟着状态走。 */
class TaskAppServiceTest {

    record Scheduled(String task, TimerId id, Instant at) {
    }

    record Env(ProcessFixture f, InstanceAppService instances, TaskAppService tasks, List<Scheduled> timers, List<DomainEvent> events) {
        Actor member(String id, String... groups) {
            return new Actor(f.tenant, Optional.of(PrincipalRef.member(id)), Optional.empty(), Set.of(groups), Set.of(), Set.of(), Set.of(), Set.of());
        }

        String taskOf(String instanceNo) {
            return f.jdbc.sql("SELECT task_no FROM proc_task WHERE instance_no = ? AND status IN ('OPEN','CLAIMED','ASSIGNED')")
                    .param(instanceNo).query(String.class).single();
        }
    }

    static LocalizedText t(String s) {
        return LocalizedText.of("zh", s);
    }

    /** submit 派到队列组 field-ops；direct 按字段直接指派；finish 由办理人走。 */
    static ProcessDef dispatching() {
        return new ProcessDef("dispatching", t("派单"), "new", List.of(
                new StatusDef("new", t("新建"), StatusCategory.NEW, null),
                new StatusDef("doing", t("处理中"), StatusCategory.IN_PROGRESS, null),
                new StatusDef("done", t("已完成"), StatusCategory.DONE, null)),
                List.of(new TransitionDef("submit", t("提交"), Set.of("new"), "doing", new Who(Set.of("REQUESTER"), null, null, null), null, null,
                                List.of(new ActionDef.AssignTo(new ApproverRef(ApproverKind.GROUP, List.of("field-ops")))), false),
                        new TransitionDef("direct", t("直派"), Set.of("new"), "doing", new Who(Set.of("REQUESTER"), null, null, null), null, null,
                                List.of(new ActionDef.AssignTo(new ApproverRef(ApproverKind.FIELD, List.of("assignTo")))), false),
                        new TransitionDef("finish", t("完成"), Set.of("doing"), "done", Who.ASSIGNEE, null, null, null, false)),
                Set.of());
    }

    private static Env env(TestDatabase db) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
        defs.publish(f.system(), f.workspace, dispatching(), null, "API");
        List<Scheduled> timers = new CopyOnWriteArrayList<>();
        List<DomainEvent> events = new CopyOnWriteArrayList<>();
        Directory dir = new Directory() {
            @Override
            public List<String> membersOf(TenantContext t, String g) {
                return List.of();
            }

            @Override
            public Optional<Integer> claimTimeoutSec(TenantContext t, String g) {
                return "field-ops".equals(g) ? Optional.of(600) : Optional.empty();
            }
        };
        BizNoGenerator ids = new BizNoGenerator(f.clock);
        TaskAppService tasks = new TaskAppService(f.store.tasks(), f.store.participants(), dir, (name, id, at) -> timers.add(new Scheduled(name, id, at)),
                events::add, ids, f.clock, f.tx);
        InstanceAppService inst = new InstanceAppService(defs, f.store.instances(), f.store.participants(), f.store.scopes(), f.store.logs(),
                List.of(tasks), Optional.of(tasks), events::add, ids, f.clock, f.tx);
        return new Env(f, inst, tasks, timers, events);
    }

    private static String submitted(Env e, Actor requester) {
        String no = e.instances.start(requester, new StartRequest("dispatching", null, Map.of(), Map.of(), Set.of())).instanceNo();
        e.instances.transition(requester, no, "submit", TransitionRequest.empty());
        return no;
    }

    private static Object key(Throwable ex) {
        return ((OpsKitException) ex).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void groupTasksAreClaimableOnlyByCandidates(TestDatabase db) {
        Env e = env(db);
        String no = submitted(e, e.member("mb_req"));
        String task = e.taskOf(no);
        assertThat(e.tasks.get(e.f.system(), task).status()).isEqualTo("OPEN");
        assertThat(e.instances.get(e.f.system(), no).participants()).doesNotContainKey("ASSIGNEE");

        assertThatThrownBy(() -> e.tasks.claim(e.member("mb_outsider", "finance"), task))
                .extracting(TaskAppServiceTest::key).isEqualTo(ProcessErrors.NOT_ALLOWED);
        TaskView claimed = e.tasks.claim(e.member("mb_a", "field-ops"), task);

        assertThat(claimed.status()).isEqualTo("CLAIMED");
        assertThat(claimed.assignee()).isEqualTo("member:mb_a");
        assertThat(claimed.claimExpiresAtMs()).isEqualTo(e.f.clock.millis() + 600_000);
        assertThat(e.instances.get(e.f.system(), no).participants()).containsEntry("ASSIGNEE", Set.of("member:mb_a"));
        assertThat(e.timers).singleElement().satisfies(s -> {
            assertThat(s.task()).isEqualTo(TaskAppService.CLAIM_TIMEOUT_TASK);
            assertThat(s.id().key()).isEqualTo(task);
            assertThat(s.at()).isEqualTo(Instant.ofEpochMilli(e.f.clock.millis() + 600_000));
        });
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void concurrentClaimsHaveExactlyOneWinner(TestDatabase db) throws Exception {
        Env e = env(db);
        String task = e.taskOf(submitted(e, e.member("mb_req")));
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Actor who = e.member("mb_" + i, "field-ops");
            results.add(pool.submit((Callable<String>) () -> {
                go.await();
                try {
                    return e.tasks.claim(who, task).assignee();
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
        assertThat(outcomes).filteredOn(o -> o.startsWith("member:")).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.startsWith("member:")).allMatch(CommonErrors.STATE_CHANGED.key()::equals);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void onlyTheClaimerReleasesAndThenOthersCanClaim(TestDatabase db) {
        Env e = env(db);
        String no = submitted(e, e.member("mb_req"));
        String task = e.taskOf(no);
        e.tasks.claim(e.member("mb_a", "field-ops"), task);

        assertThatThrownBy(() -> e.tasks.release(e.member("mb_b", "field-ops"), task))
                .extracting(TaskAppServiceTest::key).isEqualTo(ProcessErrors.NOT_ALLOWED);
        assertThat(e.tasks.release(e.member("mb_a", "field-ops"), task).status()).isEqualTo("OPEN");
        assertThat(e.instances.get(e.f.system(), no).participants()).doesNotContainKey("ASSIGNEE");
        assertThat(e.tasks.claim(e.member("mb_b", "field-ops"), task).assignee()).isEqualTo("member:mb_b");
    }

    /** 负对照语义：旧的超时任务在「放回 → 别人重领」之后触发，不能把新领取人踢掉。 */
    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void claimTimeoutReleasesOnlyTheClaimItWasScheduledFor(TestDatabase db) {
        Env e = env(db);
        String task = e.taskOf(submitted(e, e.member("mb_req")));
        e.tasks.claim(e.member("mb_a", "field-ops"), task);
        int firstEpoch = e.timers.get(0).id().epoch();

        assertThat(e.tasks.onClaimTimeout(e.f.tenant, task, firstEpoch)).isTrue();
        assertThat(e.tasks.get(e.f.system(), task).status()).isEqualTo("OPEN");

        e.tasks.claim(e.member("mb_b", "field-ops"), task);
        assertThat(e.tasks.onClaimTimeout(e.f.tenant, task, firstEpoch)).as("旧代次的超时作废").isFalse();
        assertThat(e.tasks.get(e.f.system(), task).assignee()).isEqualTo("member:mb_b");
        int secondEpoch = e.timers.get(1).id().epoch();
        assertThat(secondEpoch).isGreaterThan(firstEpoch);
        assertThat(e.tasks.onClaimTimeout(e.f.tenant, task, secondEpoch)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void directAssignmentFromAFieldAndTheTaskFinishesWithTheStatus(TestDatabase db) {
        Env e = env(db);
        Actor req = e.member("mb_req");
        String no = e.instances.start(req, new StartRequest("dispatching", null, Map.of("assignTo", "mb_agent"), Map.of(), Set.of())).instanceNo();
        e.instances.transition(req, no, "direct", TransitionRequest.empty());
        String task = e.taskOf(no);
        assertThat(e.tasks.get(e.f.system(), task).status()).isEqualTo("ASSIGNED");

        e.instances.transition(e.member("mb_agent"), no, "finish", TransitionRequest.empty());

        assertThat(e.tasks.get(e.f.system(), task).status()).as("离开状态时办理任务自动完成").isEqualTo("DONE");
        assertThat(e.f.jdbc.sql("SELECT COUNT(*) FROM proc_task_candidate WHERE task_no = ? AND is_open = 1").param(task).query(Integer.class).single())
                .as("结束的任务不再出现在任何人的待办里").isZero();
        assertThat(e.events).extracting(DomainEvent::eventType).contains("task.created", "task.completed");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void reassigningNeedsTheOwnerAndAReason(TestDatabase db) {
        Env e = env(db);
        Actor req = e.member("mb_req");
        String no = e.instances.start(req, new StartRequest("dispatching", null, Map.of("assignTo", "mb_agent"),
                Map.of("OWNER", List.of("member:mb_lead")), Set.of())).instanceNo();
        e.instances.transition(req, no, "direct", TransitionRequest.empty());
        String task = e.taskOf(no);

        assertThatThrownBy(() -> e.tasks.assignTo(e.member("mb_agent"), task, "member:mb_x", "想甩锅"))
                .extracting(TaskAppServiceTest::key).isEqualTo(ProcessErrors.NOT_ALLOWED);
        assertThatThrownBy(() -> e.tasks.assignTo(e.member("mb_lead"), task, "member:mb_x", " "))
                .extracting(TaskAppServiceTest::key).isEqualTo(ProcessErrors.REQUIRED_MISSING);
        assertThat(e.tasks.assignTo(e.member("mb_lead"), task, "member:mb_x", "代理停用").assignee()).isEqualTo("member:mb_x");
        assertThat(e.instances.get(e.f.system(), no).participants()).containsEntry("ASSIGNEE", Set.of("member:mb_x"));

        assertThat(e.tasks.pool(e.member("mb_lead"), task, "退回池").status()).isEqualTo("OPEN");
        assertThat(e.instances.get(e.f.system(), no).participants()).doesNotContainKey("ASSIGNEE");
    }
}
