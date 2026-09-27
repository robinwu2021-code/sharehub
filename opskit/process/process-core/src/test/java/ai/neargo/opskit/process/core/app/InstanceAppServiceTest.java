package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.ActionDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.Requires;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import ai.neargo.opskit.process.api.event.InstanceTransitioned;
import ai.neargo.opskit.process.api.model.ActionView;
import ai.neargo.opskit.process.api.model.InstanceView;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.api.model.TransitionRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O2-3：启动 · 迁移（who · requires · when · 条件更新 · 日志 · 动作）· 终态 · 钩子 · 事件。 */
class InstanceAppServiceTest {

    static LocalizedText t(String s) {
        return LocalizedText.of("zh", s);
    }

    private static TransitionDef tr(String code, String from, String to, Who who, Condition when, Requires req, List<ActionDef> actions) {
        return new TransitionDef(code, t(code), Set.of(from), to, who, when, req, actions, false);
    }

    /** 类工单的小流程：受理 → 处理（可挂起等待）→ 完成；发起人随时可撤销。 */
    static ProcessDef repair() {
        Who assignee = Who.ASSIGNEE;
        return new ProcessDef("repair", t("维修"), "new", List.of(
                new StatusDef("new", t("新建"), StatusCategory.NEW, null),
                new StatusDef("doing", t("处理中"), StatusCategory.IN_PROGRESS, null),
                new StatusDef("waiting", t("等待配件"), StatusCategory.WAITING, null),
                new StatusDef("done", t("已完成"), StatusCategory.DONE, null),
                new StatusDef("cancelled", t("已撤销"), StatusCategory.CANCELLED, null)),
                List.of(tr("accept", "new", "doing", assignee, null, null, null),
                        tr("wait", "doing", "waiting", assignee, null, new Requires(Set.of(), true), null),
                        tr("resume", "waiting", "doing", assignee, null, null, null),
                        tr("finish", "doing", "done", assignee, new Condition.Compare("risk", Op.NE, "HIGH"), new Requires(Set.of("resolution"), false),
                                List.of(new ActionDef.SetField("closedBy", "assignee"))),
                        tr("escalate", "doing", "doing", assignee, null, new Requires(Set.of("escalateTo"), true),
                                List.of(new ActionDef.AddParticipant("OWNER", "escalateTo"))),
                        new TransitionDef("cancel", t("撤销"), Set.of("*"), "cancelled", new Who(Set.of("REQUESTER"), null, null, null), null, null,
                                List.of(new ActionDef.ClearAssignee()), false)),
                Set.of());
    }

    record Env(ProcessFixture f, InstanceAppService svc, ProcessDefinitionAppService defs, List<DomainEvent> events, List<String> hookCalls) {
    }

    private static Env env(TestDatabase db, ProcessDef def, Optional<AssignmentHandler> assign) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
        defs.publish(f.system(), f.workspace, def, null, "API");
        List<DomainEvent> events = new CopyOnWriteArrayList<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        StatusHook recorder = new StatusHook() {
            @Override
            public void onEnter(HookContext c, StatusDef s) {
                calls.add("enter:" + s.code());
            }

            @Override
            public void onLeave(HookContext c, StatusDef s) {
                calls.add("leave:" + s.code());
            }

            @Override
            public void onClosed(HookContext c) {
                calls.add("closed");
            }
        };
        InstanceAppService svc = new InstanceAppService(defs, f.store.instances(), f.store.participants(), f.store.scopes(), f.store.logs(),
                List.of(recorder), assign, events::add, new BizNoGenerator(f.clock), f.clock, f.tx);
        return new Env(f, svc, defs, events, calls);
    }

    private static Env env(TestDatabase db) {
        return env(db, repair(), Optional.empty());
    }

    private static String startWithAssignee(Env e, Actor requester) {
        return e.svc.start(requester, new StartRequest("repair", new SubjectRef("cabinet", "CAB1001", Map.of("title", "机柜离线")),
                Map.of("risk", "LOW"), Map.of("ASSIGNEE", List.of("member:mb_agent")), Set.of("site:ST0311"))).instanceNo();
    }

    private static Object key(Throwable e) {
        return ((OpsKitException) e).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void startMakesTheCallerTheRequesterAndPublishesCreated(TestDatabase db) {
        Env e = env(db);
        Actor req = e.f.member("mb_req", Set.of());
        InstanceView v = e.svc.get(req, startWithAssignee(e, req));

        assertThat(v.status()).isEqualTo("new");
        assertThat(v.statusCategory()).isEqualTo(StatusCategory.NEW);
        assertThat(v.participants()).containsEntry("REQUESTER", Set.of("member:mb_req")).containsEntry("ASSIGNEE", Set.of("member:mb_agent"));
        assertThat(v.scopes()).containsExactly("site:ST0311");
        assertThat(v.seq()).isEqualTo(1);
        assertThat(e.events).singleElement().satisfies(ev -> {
            assertThat(ev.eventType()).isEqualTo("instance.created");
            assertThat(ev.idemKey()).isEqualTo(v.instanceNo() + ":1");
        });
        assertThat(e.hookCalls).containsExactly("enter:new");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void onlyWhoTheDefinitionAllowsCanFire(TestDatabase db) {
        Env e = env(db);
        String no = startWithAssignee(e, e.f.member("mb_req", Set.of()));
        assertThatThrownBy(() -> e.svc.transition(e.f.member("mb_stranger", Set.of("ticket:work")), no, "accept", TransitionRequest.empty()))
                .extracting(InstanceAppServiceTest::key).as("有产品权限但不是办理人，也不能走").isEqualTo(ProcessErrors.NOT_ALLOWED);
        assertThatThrownBy(() -> e.svc.transition(e.f.member("mb_req", Set.of()), no, "accept", TransitionRequest.empty()))
                .extracting(InstanceAppServiceTest::key).isEqualTo(ProcessErrors.NOT_ALLOWED);
        assertThat(e.svc.transition(e.f.member("mb_agent", Set.of()), no, "accept", TransitionRequest.empty()).status()).isEqualTo("doing");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void requiredInputIsReportedWithPathsThenMergedIntoFields(TestDatabase db) {
        Env e = env(db);
        Actor agent = e.f.member("mb_agent", Set.of());
        String no = startWithAssignee(e, e.f.member("mb_req", Set.of()));
        e.svc.transition(agent, no, "accept", TransitionRequest.empty());

        assertThatThrownBy(() -> e.svc.transition(agent, no, "wait", new TransitionRequest(Map.of(), "  ")))
                .satisfies(ex -> {
                    assertThat(key(ex)).isEqualTo(ProcessErrors.REQUIRED_MISSING);
                    assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("comment");
                });
        assertThatThrownBy(() -> e.svc.transition(agent, no, "finish", TransitionRequest.empty()))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("fields.resolution"));

        InstanceView done = e.svc.transition(agent, no, "finish", new TransitionRequest(Map.of("resolution", "换主板"), null));
        assertThat(done.fields()).containsEntry("resolution", "换主板").containsEntry("closedBy", "assignee").containsEntry("risk", "LOW");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void conditionsAndIllegalTransitionsAreRejected(TestDatabase db) {
        Env e = env(db);
        Actor agent = e.f.member("mb_agent", Set.of());
        String no = startWithAssignee(e, e.f.member("mb_req", Set.of()));
        assertThatThrownBy(() -> e.svc.transition(agent, no, "finish", new TransitionRequest(Map.of("resolution", "x"), null)))
                .extracting(InstanceAppServiceTest::key).as("new 状态没有 finish").isEqualTo(ProcessErrors.ILLEGAL_TRANSITION);
        e.svc.transition(agent, no, "accept", TransitionRequest.empty());
        assertThatThrownBy(() -> e.svc.transition(agent, no, "finish", new TransitionRequest(Map.of("resolution", "x", "risk", "HIGH"), null)))
                .extracting(InstanceAppServiceTest::key).isEqualTo(ProcessErrors.CONDITION_UNMET);
        assertThat(e.svc.get(agent, no).fields()).as("被拒的迁移不留下字段改动").containsEntry("risk", "LOW");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void concurrentFiringOfTheSameTransitionSucceedsOnce(TestDatabase db) throws Exception {
        Env e = env(db);
        Actor agent = e.f.member("mb_agent", Set.of());
        String no = startWithAssignee(e, e.f.member("mb_req", Set.of()));
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit((Callable<String>) () -> {
                go.await();
                try {
                    e.svc.transition(agent, no, "accept", TransitionRequest.empty());
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
        // 输家：读到旧状态时条件更新落空（409），读到新状态时边已不存在（422）—— 两者都对
        assertThat(outcomes).filteredOn(o -> !o.equals("ok"))
                .allMatch(o -> o.equals(CommonErrors.STATE_CHANGED.key()) || o.equals(ProcessErrors.ILLEGAL_TRANSITION.key()));
        assertThat(e.f.jdbc.sql("SELECT COUNT(*) FROM proc_transition_log WHERE action = 'accept'").query(Integer.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void terminalClosesTheInstanceRunsActionsAndHooksInOrder(TestDatabase db) {
        Env e = env(db);
        Actor req = e.f.member("mb_req", Set.of());
        Actor agent = e.f.member("mb_agent", Set.of());
        String no = startWithAssignee(e, req);
        e.svc.transition(agent, no, "accept", TransitionRequest.empty());
        e.hookCalls.clear();

        InstanceView v = e.svc.transition(req, no, "cancel", TransitionRequest.empty());

        assertThat(v.open()).isFalse();
        assertThat(v.statusCategory()).isEqualTo(StatusCategory.CANCELLED);
        assertThat(v.participants()).as("ClearAssignee 动作").doesNotContainKey("ASSIGNEE");
        assertThat(v.actions()).isEmpty();
        assertThat(e.hookCalls).containsExactly("leave:doing", "enter:cancelled", "closed");
        assertThatThrownBy(() -> e.svc.transition(req, no, "cancel", TransitionRequest.empty()))
                .extracting(InstanceAppServiceTest::key).isEqualTo(ProcessErrors.INSTANCE_CLOSED);
        assertThat(e.events).extracting(ev -> ((InstanceTransitioned) ev).seq()).containsExactly(1, 2, 3);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void actionsListDependsOnWhoIsLooking(TestDatabase db) {
        Env e = env(db);
        Actor req = e.f.member("mb_req", Set.of());
        Actor agent = e.f.member("mb_agent", Set.of());
        String no = startWithAssignee(e, req);
        assertThat(e.svc.get(agent, no).actions()).extracting(ActionView::code).containsExactly("accept");
        assertThat(e.svc.get(req, no).actions()).extracting(ActionView::code).containsExactly("cancel");
        e.svc.transition(agent, no, "accept", TransitionRequest.empty());
        assertThat(e.svc.get(agent, no).actions()).extracting(ActionView::code).containsExactlyInAnyOrder("wait", "finish", "escalate");
        assertThat(e.svc.get(agent, no).actions()).filteredOn(a -> a.code().equals("finish"))
                .singleElement().satisfies(a -> {
                    assertThat(a.toCategory()).isEqualTo(StatusCategory.DONE);
                    assertThat(a.requiresFields()).containsExactly("resolution");
                });
        assertThatThrownBy(() -> e.svc.get(e.f.member("mb_stranger", Set.of()), no))
                .extracting(InstanceAppServiceTest::key).as("非参与者看不见").isEqualTo(CommonErrors.NOT_FOUND);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void escalationAddsTheOwnerFromAField(TestDatabase db) {
        Env e = env(db);
        Actor agent = e.f.member("mb_agent", Set.of());
        String no = startWithAssignee(e, e.f.member("mb_req", Set.of()));
        e.svc.transition(agent, no, "accept", TransitionRequest.empty());
        InstanceView v = e.svc.transition(agent, no, "escalate", new TransitionRequest(Map.of("escalateTo", "member:mb_lead"), "超出能力"));
        assertThat(v.status()).isEqualTo("doing");
        assertThat(v.participants()).containsEntry("OWNER", Set.of("member:mb_lead"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void disabledProcessCannotStartAndMissingTaskModuleFailsLoudly(TestDatabase db) {
        Env e = env(db);
        e.defs.setEnabled(e.f.system(), e.f.workspace, "repair", false);
        assertThatThrownBy(() -> startWithAssignee(e, e.f.member("mb_req", Set.of())))
                .extracting(InstanceAppServiceTest::key).isEqualTo(ProcessErrors.DEFINITION_DISABLED);

        ProcessDef withAssign = new ProcessDef("assigning", t("派单"), "new", List.of(
                new StatusDef("new", t("新建"), StatusCategory.NEW, null),
                new StatusDef("doing", t("处理中"), StatusCategory.IN_PROGRESS, null),
                new StatusDef("done", t("已完成"), StatusCategory.DONE, null)),
                List.of(tr("accept", "new", "doing", null, null, null, List.of(new ActionDef.AssignTo(new ApproverRef(ApproverKind.GROUP, List.of("ops"))))),
                        tr("finish", "doing", "done", null, null, null, null)), Set.of());
        Env e2 = env(db, withAssign, Optional.empty());
        String no = e2.svc.start(e2.f.system(), new StartRequest("assigning", null, Map.of(), Map.of(), Set.of())).instanceNo();
        assertThatThrownBy(() -> e2.svc.transition(e2.f.system(), no, "accept", TransitionRequest.empty()))
                .as("配了 AssignTo 却没接任务模块：明确报错，不静默跳过").isInstanceOf(IllegalStateException.class);
        assertThat(e2.svc.get(e2.f.system(), no).status()).as("整个迁移回滚").isEqualTo("new");
    }
}
