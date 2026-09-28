package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.platform.api.calendar.BusinessCalendar;
import ai.neargo.opskit.platform.api.calendar.CalendarDef;
import ai.neargo.opskit.platform.api.calendar.WorkingSlot;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import ai.neargo.opskit.process.api.definition.sla.SlaPolicyDef;
import ai.neargo.opskit.process.api.event.SlaChanged;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.core.port.Calendars;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.core.support.ProcessFixture;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O2-8：起算 · 停止（按迁移 / 按类别）· 暂停不计时 · 改优先级重算扣除暂停 · 预警 · 逾期 · epoch 防旧 · 兜底 · 日历 · 版本绑定。 */
class SlaTrackerTest {

    /** 可拨的时钟：测试里推进时间。 */
    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    record Scheduled(String task, TimerId id, Instant at) {
    }

    record Env(ProcessFixture f, MovableClock clock, InstanceAppService instances, SlaTracker sla, SlaPolicyAppService policies,
            List<Scheduled> timers, List<DomainEvent> events) {
        Actor app() {
            return f.system();
        }

        String start(Map<String, Object> fields) {
            return instances.start(app(), new StartRequest("desk", null, fields, Map.of(), Set.of(), List.of("desk_sla"))).instanceNo();
        }

        void go(String no, String code) {
            instances.transition(app(), no, code, TransitionRequest.empty());
        }

        SlaTracker.MetricView metric(String no, String code) {
            return sla.metrics(f.tenant, no).stream().filter(m -> m.metricCode().equals(code)).findFirst().orElseThrow();
        }

        int epoch(String no, String metric) {
            return f.jdbc.sql("SELECT epoch FROM proc_sla_clock WHERE instance_no = ? AND metric_code = ?").params(no, metric).query(Integer.class).single();
        }

        List<String> kinds() {
            return events.stream().filter(e -> e instanceof SlaChanged).map(e -> ((SlaChanged) e).kind() + ":" + ((SlaChanged) e).metricCode()).toList();
        }
    }

    static final Instant MONDAY_9 = Instant.parse("2026-09-28T09:00:00Z"); // 周一

    static LocalizedText t(String s) {
        return LocalizedText.of("zh", s);
    }

    /** new →accept→ doing ⇄(ask / reply) waiting；doing →finish→ done；任意 →cancel→ cancelled。 */
    static ProcessDef desk() {
        Who any = new Who(Set.of(), Set.of(), Set.of(), Set.of("*"));
        return new ProcessDef("desk", t("服务台"), "new", List.of(
                new StatusDef("new", t("新建"), StatusCategory.NEW, null),
                new StatusDef("doing", t("处理中"), StatusCategory.IN_PROGRESS, null),
                new StatusDef("waiting", t("待客户回复"), StatusCategory.WAITING, null),
                new StatusDef("done", t("已解决"), StatusCategory.DONE, null),
                new StatusDef("cancelled", t("已作废"), StatusCategory.CANCELLED, null)),
                List.of(new TransitionDef("accept", t("受理"), Set.of("new"), "doing", any, null, null, null, false),
                        new TransitionDef("ask", t("追问"), Set.of("doing"), "waiting", any, null, null, null, false),
                        new TransitionDef("reply", t("客户回复"), Set.of("waiting"), "doing", any, null, null, null, false),
                        new TransitionDef("finish", t("解决"), Set.of("doing"), "done", any, null, null, null, false),
                        new TransitionDef("cancel", t("作废"), Set.of("*"), "cancelled", any, null, null, null, false)),
                Set.of());
    }

    /** 首次响应：创建即起算、受理即停；解决：创建即起算、WAITING 暂停、DONE/CANCELLED 停。P1 30 分 / 4 小时，其余 2 小时 / 24 小时；80% 预警。 */
    static SlaPolicyDef policy(String calendar) {
        return new SlaPolicyDef("desk_sla", t("服务台 SLA"), List.of(
                new SlaPolicyDef.Metric("FIRST_RESPONSE", t("首次响应"), null, null, new SlaPolicyDef.Trigger(false, Set.of(), Set.of("accept"))),
                new SlaPolicyDef.Metric("RESOLUTION", t("解决"), null, null, null)),
                List.of(new SlaPolicyDef.Target(new Condition.Compare("priority", Op.EQ, "P1"),
                                Map.of("FIRST_RESPONSE", Duration.ofMinutes(30), "RESOLUTION", Duration.ofHours(4)), calendar),
                        new SlaPolicyDef.Target(null, Map.of("FIRST_RESPONSE", Duration.ofHours(2), "RESOLUTION", Duration.ofHours(24)), calendar)),
                80);
    }

    private static Env env(TestDatabase db, String calendar) {
        ProcessFixture f = new ProcessFixture(db);
        MovableClock clock = new MovableClock(MONDAY_9);
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, clock, f.tx);
        defs.publish(f.system(), f.workspace, desk(), null, "API");
        SlaPolicyAppService policies = new SlaPolicyAppService(f.store.definitions(), f.audit, clock, f.tx);
        policies.publish(f.system(), f.workspace, policy(calendar), null, "API");
        List<Scheduled> timers = new CopyOnWriteArrayList<>();
        List<DomainEvent> events = new CopyOnWriteArrayList<>();
        // 周一至周五 9:00–18:00（UTC）
        BusinessCalendar office = new BusinessCalendar(new CalendarDef("office", "UTC", List.of(DayOfWeek.values()).stream()
                .filter(d -> d.getValue() <= 5).map(d -> new WorkingSlot(d, 9 * 60, 18 * 60)).toList(), Set.of()));
        Calendars cals = (tc, code) -> "office".equals(code) ? Optional.of(office) : Optional.empty();
        BizNoGenerator ids = new BizNoGenerator(clock);
        SlaTracker sla = new SlaTracker(policies, f.store.slaClocks(), f.store.logs(), cals, (n, id, at) -> timers.add(new Scheduled(n, id, at)),
                events::add, ids, clock, f.tx);
        Directory dir = new Directory() {
            @Override
            public List<String> membersOf(TenantContext tc, String g) {
                return List.of();
            }

            @Override
            public Optional<Integer> claimTimeoutSec(TenantContext tc, String g) {
                return Optional.empty();
            }
        };
        TaskAppService tasks = new TaskAppService(f.store.tasks(), f.store.participants(), dir, (n, id, at) -> { }, events::add, ids, clock, f.tx);
        InstanceAppService inst = new InstanceAppService(defs, f.store.instances(), f.store.participants(), f.store.scopes(), f.store.logs(),
                List.of(tasks, sla), Optional.of(tasks), events::add, ids, clock, f.tx);
        return new Env(f, clock, inst, sla, policies, timers, events);
    }

    private static Env env(TestDatabase db) {
        return env(db, null);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void bothMetricsStartOnCreateAndFirstResponseStopsOnAccept(TestDatabase db) {
        Env e = env(db);
        String no = e.start(Map.of("priority", "P2"));
        assertThat(e.metric(no, "FIRST_RESPONSE").dueAtMs()).isEqualTo(MONDAY_9.plus(Duration.ofHours(2)).toEpochMilli());
        assertThat(e.metric(no, "RESOLUTION").dueAtMs()).isEqualTo(MONDAY_9.plus(Duration.ofHours(24)).toEpochMilli());
        assertThat(e.timers).filteredOn(s -> s.task().equals(SlaTracker.WARN_TASK)).extracting(Scheduled::at)
                .contains(MONDAY_9.plus(Duration.ofMinutes(96))); // 2 小时的 80%

        e.clock.advance(Duration.ofMinutes(40));
        e.go(no, "accept");
        assertThat(e.metric(no, "FIRST_RESPONSE").state()).isEqualTo("MET");
        assertThat(e.metric(no, "FIRST_RESPONSE").stoppedAtMs()).isEqualTo(e.clock.millis());
        assertThat(e.metric(no, "RESOLUTION").state()).as("受理不影响解决时限").isEqualTo("RUNNING");
        assertThat(e.kinds()).containsExactly("met:FIRST_RESPONSE");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void waitingDoesNotCountAndAStaleDueTimerIsANoop(TestDatabase db) {
        Env e = env(db);
        String no = e.start(Map.of("priority", "P2"));
        e.go(no, "accept");
        int before = e.epoch(no, "RESOLUTION");
        e.clock.advance(Duration.ofHours(1));
        e.go(no, "ask");
        assertThat(e.metric(no, "RESOLUTION").state()).isEqualTo("PAUSED");
        e.clock.advance(Duration.ofHours(3));
        e.go(no, "reply");

        SlaTracker.MetricView m = e.metric(no, "RESOLUTION");
        assertThat(m.state()).isEqualTo("RUNNING");
        assertThat(m.pausedMs()).isEqualTo(Duration.ofHours(3).toMillis());
        assertThat(m.dueAtMs()).as("到期顺延暂停的 3 小时").isEqualTo(MONDAY_9.plus(Duration.ofHours(27)).toEpochMilli());
        assertThat(e.sla.onDue(e.f.tenant, clockId(e, no), before)).as("暂停前排的到期定时已作废").isFalse();
        assertThat(e.metric(no, "RESOLUTION").state()).isEqualTo("RUNNING");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void aPriorityChangeRetargetsFromTheOriginalStartMinusPauses(TestDatabase db) {
        Env e = env(db);
        String no = e.start(Map.of("priority", "P2"));
        e.go(no, "accept");
        e.go(no, "ask");
        e.clock.advance(Duration.ofHours(2));
        e.go(no, "reply");
        e.clock.advance(Duration.ofHours(1));

        e.instances.updateFields(e.app(), no, Map.of("priority", "P1"));
        SlaTracker.MetricView m = e.metric(no, "RESOLUTION");
        assertThat(m.dueAtMs()).as("起算点 + 暂停 2 小时 + 新目标 4 小时").isEqualTo(MONDAY_9.plus(Duration.ofHours(6)).toEpochMilli());
        assertThat(m.state()).as("已用 1 小时，还没到 80%").isEqualTo("RUNNING");

        e.clock.advance(Duration.ofHours(4));
        e.instances.updateFields(e.app(), no, Map.of("priority", "P2"));
        e.instances.updateFields(e.app(), no, Map.of("priority", "P1"));
        assertThat(e.metric(no, "RESOLUTION").state()).as("改成更紧的目标时已经超了：当场逾期").isEqualTo("BREACHED");
        assertThat(e.kinds()).contains("breached:RESOLUTION");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void warnThenBreachThenStopKeepsBreached(TestDatabase db) {
        Env e = env(db);
        String no = e.start(Map.of("priority", "P1"));
        e.go(no, "accept");
        String id = clockId(e, no);
        int epoch = e.epoch(no, "RESOLUTION");
        e.clock.advance(Duration.ofMinutes(200));
        assertThat(e.sla.onWarn(e.f.tenant, id, epoch)).isTrue();
        assertThat(e.metric(no, "RESOLUTION").state()).isEqualTo("WARNING");
        e.clock.advance(Duration.ofMinutes(41));
        assertThat(e.sla.onDue(e.f.tenant, id, epoch)).as("预警不改 epoch，同代次的到期定时仍有效").isTrue();
        assertThat(e.metric(no, "RESOLUTION").state()).isEqualTo("BREACHED");
        e.clock.advance(Duration.ofHours(1));
        e.go(no, "finish");
        SlaTracker.MetricView m = e.metric(no, "RESOLUTION");
        assertThat(m.state()).isEqualTo("BREACHED");
        assertThat(m.stoppedAtMs()).isEqualTo(e.clock.millis());
        assertThat(e.kinds()).containsSubsequence("warning:RESOLUTION", "breached:RESOLUTION").doesNotContain("met:RESOLUTION");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void cancellingBeforeTheDeadlineIsNotAMet(TestDatabase db) {
        Env e = env(db);
        String no = e.start(Map.of("priority", "P2"));
        e.go(no, "cancel");
        assertThat(e.sla.metrics(e.f.tenant, no)).extracting(SlaTracker.MetricView::state).containsOnly("CANCELLED");
        assertThat(e.kinds()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void reconcileCatchesALostDueTimer(TestDatabase db) {
        Env e = env(db);
        String no = e.start(Map.of("priority", "P1"));
        e.go(no, "accept");
        e.clock.advance(Duration.ofHours(5));
        assertThat(e.sla.reconcile(100)).isGreaterThanOrEqualTo(1);
        assertThat(e.metric(no, "RESOLUTION").state()).isEqualTo("BREACHED");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void businessCalendarSkipsTheNightAndTheWeekend(TestDatabase db) {
        Env e = env(db, "office");
        e.clock.advance(Duration.ofDays(4).plusHours(8)); // 周五 17:00
        String no = e.start(Map.of("priority", "P1"));
        // 周五还剩 1 小时营业，余下 3 小时落到下周一 9:00–12:00
        assertThat(e.metric(no, "RESOLUTION").dueAtMs()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z").toEpochMilli());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void runningClocksKeepThePolicyVersionTheyStartedWith(TestDatabase db) {
        Env e = env(db);
        String no = e.start(Map.of("priority", "P2"));
        SlaPolicyDef tighter = new SlaPolicyDef("desk_sla", t("服务台 SLA"), policy(null).metrics(),
                List.of(new SlaPolicyDef.Target(null, Map.of("FIRST_RESPONSE", Duration.ofMinutes(10), "RESOLUTION", Duration.ofHours(1)), null)), null);
        assertThat(e.policies.publish(e.app(), e.f.workspace, tighter, "收紧", "API").created()).isTrue();
        e.instances.updateFields(e.app(), no, Map.of("note", "改个无关字段也会重新匹配"));
        assertThat(e.metric(no, "RESOLUTION").dueAtMs()).as("在途计时器仍按起算时的版本").isEqualTo(MONDAY_9.plus(Duration.ofHours(24)).toEpochMilli());
        String fresh = e.start(Map.of("priority", "P2"));
        assertThat(e.metric(fresh, "RESOLUTION").dueAtMs()).isEqualTo(MONDAY_9.plus(Duration.ofHours(1)).toEpochMilli());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void policyErrorsPointAtPolicyFields(TestDatabase db) {
        Env e = env(db);
        SlaPolicyDef bad = new SlaPolicyDef("bad_sla", t("坏"), List.of(
                new SlaPolicyDef.Metric("RESOLUTION", t("解决"), null, null, new SlaPolicyDef.Trigger(false, Set.of(), Set.of()))),
                List.of(new SlaPolicyDef.Target(null, Map.of("RESOLUTION", Duration.ofHours(1)), null),
                        new SlaPolicyDef.Target(null, Map.of("FIRST_RESPONSE", Duration.ofHours(1)), null)), 100);
        assertThatThrownBy(() -> e.policies.publish(e.app(), e.f.workspace, bad, null, "API")).satisfies(ex -> {
            assertThat(((OpsKitException) ex).errorKey()).isEqualTo(CommonErrors.VALIDATION);
            assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path)
                    .containsExactly("metrics[0].stop", "targets[1]", "targets[1].targets.FIRST_RESPONSE", "warnAtPercent");
        });
        assertThatThrownBy(() -> e.instances.start(e.app(), new StartRequest("desk", null, Map.of(), Map.of(), Set.of(), List.of("nope"))))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("slaPolicies"));
    }

    private static String clockId(Env e, String no) {
        return e.metric(no, "RESOLUTION").clockId();
    }
}
