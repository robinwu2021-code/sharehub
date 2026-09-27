package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import ai.neargo.opskit.process.api.event.AwaitingConfirmation;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.core.app.GateTimersAndConfirmations.Reply;
import ai.neargo.opskit.process.core.app.GateTimersAndConfirmations.ReplyResult;
import ai.neargo.opskit.process.core.domain.definition.SampleProcesses;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.core.support.ProcessFixture;
import java.time.Duration;
import java.time.Instant;
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

/** 21/07 O2-6 外部确认门 · O2-7 定时门：一次性令牌、只存哈希、重复回复拿首次结果、超时、代次防旧。 */
class GateTimersAndConfirmationsTest {

    record Scheduled(String task, TimerId id, Instant at) {
    }

    record Env(ProcessFixture f, InstanceAppService instances, ApprovalAppService approvals, GateTimersAndConfirmations gates,
            List<DomainEvent> events, List<Scheduled> timers) {
        Actor member(String id, Set<String> roles) {
            return new Actor(f.tenant, Optional.of(PrincipalRef.member(id)), Optional.empty(), Set.of(), roles, Set.of(), Set.of(), Set.of());
        }

        String status(String no) {
            return instances.get(f.system(), no).status();
        }

        int epoch(String no) {
            return f.jdbc.sql("SELECT gate_epoch FROM proc_instance WHERE instance_no = ?").param(no).query(Integer.class).single();
        }

        /** 走到「生效确认中」，返回实例号。 */
        String toActivating() {
            Actor bd = member("mb_bd", Set.of());
            String no = instances.start(bd, new StartRequest("contract_approval", null, Map.of("needsCosign", false), Map.of(), Set.of())).instanceNo();
            instances.transition(bd, no, "submit", TransitionRequest.empty());
            String ap = f.jdbc.sql("SELECT approval_no FROM proc_approval WHERE instance_no = ?").param(no).query(String.class).single();
            String task = f.jdbc.sql("SELECT task_no FROM proc_task WHERE approval_no = ?").param(ap).query(String.class).single();
            approvals.decide(member("mb_ops", Set.of("contract-approver")), ap, task, true, null);
            assertThat(status(no)).isEqualTo("activating");
            return no;
        }

        AwaitingConfirmation awaiting(String no) {
            return events.stream().filter(ev -> ev instanceof AwaitingConfirmation a && a.instanceNo().equals(no))
                    .map(AwaitingConfirmation.class::cast).reduce((a, b) -> b).orElseThrow();
        }
    }

    private static Env env(TestDatabase db, ProcessDef def) {
        ProcessFixture f = new ProcessFixture(db);
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
        defs.publish(f.system(), f.workspace, def, null, "API");
        List<DomainEvent> events = new CopyOnWriteArrayList<>();
        List<Scheduled> timers = new CopyOnWriteArrayList<>();
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
        TaskAppService tasks = new TaskAppService(f.store.tasks(), f.store.participants(), dir, (n, id, at) -> { }, events::add, ids, f.clock, f.tx);
        AtomicReference<InstanceAppService> ref = new AtomicReference<>();
        ApprovalAppService approvals = new ApprovalAppService(defs, f.store.approvals(), f.store.tasks(), f.store.instances(), f.store.participants(),
                f.store.logs(), tasks, ref::get, dir, events::add, ids, f.clock, f.tx);
        GateTimersAndConfirmations gates = new GateTimersAndConfirmations(defs, f.store.instances(), ref::get,
                (n, id, at) -> timers.add(new Scheduled(n, id, at)), events::add, f.clock, f.tx);
        InstanceAppService inst = new InstanceAppService(defs, f.store.instances(), f.store.participants(), f.store.scopes(), f.store.logs(),
                List.of(tasks, approvals, gates), Optional.of(tasks), events::add, ids, f.clock, f.tx);
        ref.set(inst);
        return new Env(f, inst, approvals, gates, events, timers);
    }

    private static Object key(Throwable ex) {
        return ((OpsKitException) ex).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void confirmMovesOnAndTheTokenIsStoredOnlyAsAHash(TestDatabase db) {
        Env e = env(db, SampleProcesses.contractApproval());
        String no = e.toActivating();
        AwaitingConfirmation ev = e.awaiting(no);

        assertThat(ev.topic()).isEqualTo("contract.activate");
        assertThat(ev.idemKey()).isEqualTo(no + ":" + e.epoch(no));
        assertThat(ev.expiresAtMs()).isEqualTo(e.f.clock.millis() + Duration.ofHours(24).toMillis());
        String stored = e.f.jdbc.sql("SELECT confirm_token_hash FROM proc_instance WHERE instance_no = ?").param(no).query(String.class).single();
        assertThat(stored).as("库里只有哈希").isNotEqualTo(ev.token()).isEqualTo(GateTimersAndConfirmations.sha256(ev.token()));
        assertThat(e.timers).filteredOn(s -> s.task().equals(GateTimersAndConfirmations.CONFIRM_TIMEOUT_TASK)).singleElement()
                .satisfies(s -> assertThat(s.id()).isEqualTo(new TimerId(e.f.workspace, no, e.epoch(no))));

        assertThatThrownBy(() -> e.gates.reply(e.member("mb_ops", Set.of()), ev.token(), Reply.CONFIRM, null))
                .as("只有接入方（应用自身）能回复").extracting(GateTimersAndConfirmationsTest::key).isEqualTo(ProcessErrors.NOT_ALLOWED);

        assertThat(e.gates.reply(e.f.system(), ev.token(), Reply.CONFIRM, null)).isEqualTo(new ReplyResult(no, "CONFIRM", true));
        assertThat(e.status(no)).isEqualTo("active");
        // 接入方网络重试：拿回首次结果，且不再迁移 —— 哪怕这次说的是 REJECT
        assertThat(e.gates.reply(e.f.system(), ev.token(), Reply.REJECT, "重试")).isEqualTo(new ReplyResult(no, "CONFIRM", false));
        assertThat(e.status(no)).isEqualTo("active");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void rejectNeedsAReasonAndTakesTheRejectedExit(TestDatabase db) {
        Env e = env(db, SampleProcesses.contractApproval());
        String no = e.toActivating();
        String token = e.awaiting(no).token();
        assertThatThrownBy(() -> e.gates.reply(e.f.system(), token, Reply.REJECT, " "))
                .extracting(GateTimersAndConfirmationsTest::key).isEqualTo(ProcessErrors.REASON_REQUIRED);
        e.gates.reply(e.f.system(), token, Reply.REJECT, "时间段与现行合同重叠");
        assertThat(e.status(no)).isEqualTo("rejected");
        assertThatThrownBy(() -> e.gates.reply(e.f.system(), "okc_forged", Reply.CONFIRM, null))
                .extracting(GateTimersAndConfirmationsTest::key).isEqualTo(GateTimersAndConfirmations.CONFIRMATION_EXPIRED);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void timeoutComparesTheEpochAndALateReplyIsExpired(TestDatabase db) {
        Env e = env(db, SampleProcesses.contractApproval());
        String no = e.toActivating();
        String token = e.awaiting(no).token();
        int epoch = e.epoch(no);

        assertThat(e.gates.onConfirmTimeout(e.f.tenant, no, epoch - 1)).as("旧代次的定时：无操作").isFalse();
        assertThat(e.status(no)).isEqualTo("activating");
        assertThat(e.gates.onConfirmTimeout(e.f.tenant, no, epoch)).isTrue();
        assertThat(e.status(no)).isEqualTo("rejected");
        assertThat(e.gates.onConfirmTimeout(e.f.tenant, no, epoch)).as("重复触发").isFalse();
        assertThatThrownBy(() -> e.gates.reply(e.f.system(), token, Reply.CONFIRM, null))
                .as("超时后才到的回复").extracting(GateTimersAndConfirmationsTest::key).isEqualTo(GateTimersAndConfirmations.CONFIRMATION_EXPIRED);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void leavingTheStatusVoidsAnUnansweredToken(TestDatabase db) {
        Env e = env(db, SampleProcesses.contractApproval());
        String no = e.toActivating();
        String token = e.awaiting(no).token();
        e.instances.transition(e.member("mb_bd", Set.of()), no, "cancel", new TransitionRequest(Map.of(), "不签了"));

        assertThatThrownBy(() -> e.gates.reply(e.f.system(), token, Reply.CONFIRM, null))
                .extracting(GateTimersAndConfirmationsTest::key).isEqualTo(GateTimersAndConfirmations.CONFIRMATION_EXPIRED);
        assertThat(e.gates.onConfirmTimeout(e.f.tenant, no, e.epoch(no))).isFalse();
        assertThat(e.status(no)).isEqualTo("rejected");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void concurrentRepliesTransitionExactlyOnce(TestDatabase db) throws Exception {
        Env e = env(db, SampleProcesses.contractApproval());
        String no = e.toActivating();
        String token = e.awaiting(no).token();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ReplyResult> confirm = () -> {
                go.await();
                return e.gates.reply(e.f.system(), token, Reply.CONFIRM, null);
            };
            Callable<ReplyResult> reject = () -> {
                go.await();
                return e.gates.reply(e.f.system(), token, Reply.REJECT, "并发");
            };
            Future<ReplyResult> a = pool.submit(confirm);
            Future<ReplyResult> b = pool.submit(reject);
            go.countDown();
            List<ReplyResult> rs = List.of(a.get(), b.get());
            assertThat(rs).filteredOn(ReplyResult::first).hasSize(1);
            assertThat(rs).extracting(ReplyResult::result).as("两个都拿到同一个结论").containsOnly(rs.stream().filter(ReplyResult::first).findFirst().orElseThrow().result());
            int moves = e.f.jdbc.sql("SELECT COUNT(*) FROM proc_transition_log WHERE instance_no = ? AND entity_kind = 'INSTANCE' AND from_value = 'activating'").param(no)
                    .query(Integer.class).single();
            assertThat(moves).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── 定时门 ──────────────────────────────────────────────

    static ProcessDef cooling() {
        LocalizedText n = LocalizedText.of("zh", "冷静期");
        return new ProcessDef("cooling", n, "draft",
                List.of(new StatusDef("draft", n, StatusCategory.NEW, null),
                        new StatusDef("cooling", n, StatusCategory.WAITING, new GateDef.Timer(Duration.ofHours(2), "elapsed")),
                        new StatusDef("done", n, StatusCategory.DONE, null)),
                List.of(new TransitionDef("start", n, Set.of("draft"), "cooling", new Who(Set.of("REQUESTER"), null, null, null), null, null, null, false),
                        new TransitionDef("back", n, Set.of("cooling"), "draft", new Who(Set.of("REQUESTER"), null, null, null), null, null, null, false),
                        new TransitionDef("elapsed", n, Set.of("cooling"), "done", null, null, null, null, true)),
                Set.of());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void timerFiresOnlyForTheVisitThatScheduledIt(TestDatabase db) {
        Env e = env(db, cooling());
        Actor me = e.member("mb_me", Set.of());
        String no = e.instances.start(me, new StartRequest("cooling", null, Map.of(), Map.of(), Set.of())).instanceNo();
        e.instances.transition(me, no, "start", TransitionRequest.empty());
        int first = e.epoch(no);
        assertThat(e.timers).singleElement().satisfies(s -> {
            assertThat(s.task()).isEqualTo(GateTimersAndConfirmations.GATE_TIMER_TASK);
            assertThat(s.at()).isEqualTo(e.f.clock.instant().plus(Duration.ofHours(2)));
        });

        e.instances.transition(me, no, "back", TransitionRequest.empty());
        e.instances.transition(me, no, "start", TransitionRequest.empty());
        assertThat(e.gates.onGateTimer(e.f.tenant, no, first)).as("第一次进入时排的定时：期间离开过，作废").isFalse();
        assertThat(e.status(no)).isEqualTo("cooling");

        assertThat(e.gates.onGateTimer(e.f.tenant, no, e.epoch(no))).isTrue();
        assertThat(e.status(no)).isEqualTo("done");
    }
}
