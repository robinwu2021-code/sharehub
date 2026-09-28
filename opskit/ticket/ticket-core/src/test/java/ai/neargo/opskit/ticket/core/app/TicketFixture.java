package ai.neargo.opskit.ticket.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.audit.AuditRecorder;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.directory.DirectorySource;
import ai.neargo.opskit.platform.api.directory.GroupKind;
import ai.neargo.opskit.platform.api.directory.GroupView;
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import ai.neargo.opskit.process.api.definition.sla.SlaPolicyDef;
import ai.neargo.opskit.process.api.model.InstanceView;
import ai.neargo.opskit.process.client.InstanceObserver;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.process.core.app.ApprovalAppService;
import ai.neargo.opskit.process.core.app.GateTimersAndConfirmations;
import ai.neargo.opskit.process.core.app.InstanceAppService;
import ai.neargo.opskit.process.core.app.ObserverHook;
import ai.neargo.opskit.process.core.app.ProcessDefinitionAppService;
import ai.neargo.opskit.process.core.app.SlaPolicyAppService;
import ai.neargo.opskit.process.core.app.SlaTracker;
import ai.neargo.opskit.process.core.app.TaskAppService;
import ai.neargo.opskit.process.core.embedded.EmbeddedProcessClient;
import ai.neargo.opskit.process.core.port.Calendars;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.store.ProcessMigrations;
import ai.neargo.opskit.process.store.ProcessStore;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.FieldType;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.Options;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.api.model.TicketView;
import ai.neargo.opskit.ticket.store.TicketMigrations;
import ai.neargo.opskit.ticket.store.TicketStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 工单集成测试的装配：真流程引擎（经 EmbeddedProcessClient）+ 工单服务 + 快照观察者；可拨的时钟。 */
final class TicketFixture {

    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-28T09:00:00Z");

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

    static final TenantContext T = new TenantContext(new AccountId("ac_t"), new WorkspaceId("ws_t"));

    final MovableClock clock = new MovableClock();
    final JdbcClient jdbc;
    final TransactionTemplate tx;
    final ProcessClient processes;
    final InstanceAppService instances;
    final SlaTracker sla;
    final TicketDefinitionAppService definitions;
    final TicketAppService tickets;

    TicketFixture(TestDatabase db) {
        ProcessMigrations.migrate(db.dataSource());
        TicketMigrations.migrate(db.dataSource());
        jdbc = JdbcClient.create(db.dataSource());
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        ProcessStore ps = ProcessStore.create(db.dataSource());
        TicketStore ts = TicketStore.create(db.dataSource());
        AuditRecorder audit = (a, ws, action, target, detail) -> { };
        BizNoGenerator ids = new BizNoGenerator(clock);
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
        AtomicReference<TicketAppService> ticketRef = new AtomicReference<>();
        AtomicReference<InstanceAppService> instRef = new AtomicReference<>();
        List<InstanceObserver> observers = List.of((t, no) -> ticketRef.get().syncByInstance(t, no));
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(ps.definitions(), audit, clock, tx);
        SlaPolicyAppService policies = new SlaPolicyAppService(ps.definitions(), audit, clock, tx);
        TaskAppService tasks = new TaskAppService(ps.tasks(), ps.participants(), dir, (n, id, at) -> { }, e -> { }, ids, clock, tx, ps.logs());
        sla = new SlaTracker(policies, ps.slaClocks(), ps.logs(), Calendars.NONE, (n, id, at) -> { }, e -> { }, ids, clock, tx, observers);
        ApprovalAppService approvals = new ApprovalAppService(defs, ps.approvals(), ps.tasks(), ps.instances(), ps.participants(), ps.logs(), tasks,
                instRef::get, dir, e -> { }, ids, clock, tx);
        GateTimersAndConfirmations gates = new GateTimersAndConfirmations(defs, ps.instances(), instRef::get, (n, id, at) -> { }, e -> { }, clock, tx);
        instances = new InstanceAppService(defs, ps.instances(), ps.participants(), ps.scopes(), ps.logs(),
                List.of(tasks, approvals, gates, sla, new ObserverHook(observers)), Optional.of(tasks), e -> { }, ids, clock, tx);
        instRef.set(instances);
        processes = new EmbeddedProcessClient(instances, defs, policies, sla, ps.logs());
        DirectoryClient directory = new DirectoryClient() {
            @Override
            public Optional<GroupView> findGroup(TenantContext t, String code) {
                return Optional.of(new GroupView(t.workspaceId().value(), code, Map.of(), GroupKind.QUEUE, null, null, DirectorySource.SYNC));
            }

            @Override
            public List<String> membersOf(TenantContext t, String g) {
                return List.of();
            }

            @Override
            public Map<String, String> displayNames(TenantContext t, Collection<String> ids2) {
                return Map.of();
            }
        };
        definitions = new TicketDefinitionAppService(ts.definitions(), processes, directory, audit, clock, tx);
        tickets = new TicketAppService(definitions, ts.tickets(), ts.fieldIndex(), ts.scopes(), ts.comments(), processes, ids, clock, tx);
        ticketRef.set(tickets);

        defs.publish(app(), T.workspaceId(), desk(), null, "API");
        policies.publish(app(), T.workspaceId(), deskSla(), null, "API");
        definitions.publishType(app(), T.workspaceId(), repairType(), null, "API");
    }

    static LocalizedText t(String s) {
        return LocalizedText.of("zh", s);
    }

    static Actor app() {
        return new Actor(T, Optional.empty(), Optional.of("app_test"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    static Actor member(String id, Set<String> perms, Set<String> scopes) {
        return new Actor(T, Optional.of(PrincipalRef.member(id)), Optional.empty(), Set.of(), Set.of(), Set.of(), perms, scopes);
    }

    /** new →accept→ doing ⇄(ask / reply) waiting；doing →finish→ done；任意 →cancel→ cancelled。 */
    static ProcessDef desk() {
        Who any = new Who(Set.of("REQUESTER"), Set.of(), Set.of(), Set.of());
        return new ProcessDef("desk", t("服务台"), "new", List.of(
                new StatusDef("new", t("新建"), StatusCategory.NEW, null),
                new StatusDef("doing", t("处理中"), StatusCategory.IN_PROGRESS, null),
                new StatusDef("waiting", t("待回复"), StatusCategory.WAITING, null),
                new StatusDef("done", t("已解决"), StatusCategory.DONE, null),
                new StatusDef("cancelled", t("已作废"), StatusCategory.CANCELLED, null)),
                List.of(new TransitionDef("accept", t("受理"), Set.of("new"), "doing", any, null, null, null, false),
                        new TransitionDef("ask", t("追问"), Set.of("doing"), "waiting", any, null, null, null, false),
                        new TransitionDef("reply", t("回复"), Set.of("waiting"), "doing", any, null, null, null, false),
                        new TransitionDef("finish", t("解决"), Set.of("doing"), "done", any, null, null, null, false),
                        new TransitionDef("cancel", t("作废"), Set.of("*"), "cancelled", any, null, null, null, false)),
                Set.of());
    }

    /** 解决时限：P1 4 小时，其余 24 小时。 */
    static SlaPolicyDef deskSla() {
        return new SlaPolicyDef("desk_sla", t("服务台 SLA"), List.of(new SlaPolicyDef.Metric("RESOLUTION", t("解决"), null, null, null)),
                List.of(new SlaPolicyDef.Target(new ai.neargo.opskit.commons.condition.Condition.Compare("priority",
                                ai.neargo.opskit.commons.condition.Op.EQ, "P1"), Map.of("RESOLUTION", Duration.ofHours(4)), null),
                        new SlaPolicyDef.Target(null, Map.of("RESOLUTION", Duration.ofHours(24)), null)), 80);
    }

    static TicketTypeDef repairType() {
        return new TicketTypeDef("repair", t("报修"), "desk", List.of(
                new FieldDef("site", t("站点"), FieldType.SELECT, true, true, true,
                        Options.of(List.of(new OptionDef("S1", t("一号店"), true), new OptionDef("S2", t("二号店"), true))), false, null),
                new FieldDef("device", t("设备"), FieldType.REF, true, true, true, null, false, "device"),
                new FieldDef("note", t("备注"), FieldType.LONG_TEXT, false, true, false, null, false, null)),
                Options.of(List.of(new OptionDef("P1", t("紧急"), true), new OptionDef("P2", t("普通"), true))),
                Options.of(List.of(new OptionDef("FIXED", t("已修复"), true))), "RP", AssignmentDef.MANUAL, List.of("desk_sla"),
                List.of(new TicketTypeDef.ScopeFrom("site", "site")), new TicketTypeDef.Dedupe(List.of("device")), null);
    }

    static Map<String, Object> fields(String site, String device) {
        return Map.of("site", site, "device", Map.of("type", "device", "ref", device));
    }

    /** 快照不变式：工单的状态 / 未结 / 办理人与流程实例一致。 */
    void assertSnapshotMatches(String ticketNo) {
        TicketView v = tickets.get(app(), ticketNo);
        InstanceView inst = processes.snapshot(T, v.instanceNo()).orElseThrow();
        assertThat(v.status()).as("快照状态 = 实例状态").isEqualTo(inst.status());
        assertThat(v.statusCategory()).isEqualTo(inst.statusCategory().name());
        assertThat(v.open()).isEqualTo(inst.open());
        assertThat(v.scopes()).as("工单标签与实例标签一致").isEqualTo(inst.scopes());
    }
}
