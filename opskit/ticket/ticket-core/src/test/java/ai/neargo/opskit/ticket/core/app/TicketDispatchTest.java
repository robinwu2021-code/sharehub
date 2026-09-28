package ai.neargo.opskit.ticket.core.app;

import static ai.neargo.opskit.ticket.core.app.TicketFixture.T;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.app;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.member;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.t;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.ticket.api.TicketErrors;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef.Strategy;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.FieldType;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.api.event.AssignmentRequested;
import ai.neargo.opskit.ticket.api.model.CreateTicketRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O3-5：六种派单策略 · 轮转并发不重复 · 最少负载 · 外部指定回填与超时兜底 · 领取 / 改派 / 退回池。 */
class TicketDispatchTest {

    static final Actor BOB = member("mb_bob", Set.of(TicketAppService.CREATE), Set.of());
    static final Actor DISPATCHER = member("mb_lead", Set.of(TicketAppService.ASSIGN), Set.of());

    /** 与 repairType 同字段外加成员字段 fixer；不去重（便于同一设备连建多张）。 */
    static TicketTypeDef type(String code, AssignmentDef a) {
        TicketTypeDef base = TicketFixture.repairType();
        List<FieldDef> fields = new ArrayList<>(base.fields());
        fields.add(new FieldDef("fixer", t("维修员"), FieldType.MEMBER, false, true, true, null, false, null));
        return new TicketTypeDef(code, t(code), "desk", fields, base.priorityOptions(), base.resolutionOptions(), null, a, List.of(), List.of(), null, null);
    }

    static String create(TicketFixture f, String type, Map<String, Object> extra) {
        Map<String, Object> fields = new java.util.HashMap<>(TicketFixture.fields("S1", "D-" + System.nanoTime()));
        fields.putAll(extra);
        return f.tickets.create(BOB, new CreateTicketRequest(type, "报修", null, fields, "P2", null, Set.of(), null)).ticket().ticketNo();
    }

    static String assignee(TicketFixture f, String no) {
        return f.tickets.get(app(), no).assignee();
    }

    static Object key(Throwable ex) {
        return ((OpsKitException) ex).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void queueTicketsAreClaimedAndPooledBackWithAReason(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        f.definitions.publishType(app(), T.workspaceId(), type("q", new AssignmentDef(Strategy.QUEUE, "field-ops", null, null, null)), null, "API");
        String no = create(f, "q", Map.of());
        assertThat(assignee(f, no)).isNull();
        assertThat(f.jdbc.sql("SELECT queue_group FROM proc_task t JOIN tkt_ticket k ON k.current_task_no = t.task_no WHERE k.ticket_no = ?").param(no)
                .query(String.class).single()).isEqualTo("field-ops");

        Actor amy = new Actor(T, java.util.Optional.of(ai.neargo.opskit.commons.actor.PrincipalRef.member("mb_amy")), java.util.Optional.empty(),
                Set.of("field-ops"), Set.of(), Set.of(), Set.of(), Set.of());
        f.tickets.claim(amy, no);
        assertThat(assignee(f, no)).isEqualTo("member:mb_amy");
        assertThatThrownBy(() -> f.tickets.pool(DISPATCHER, no, " ")).satisfies(ex -> assertThat(key(ex)).isEqualTo(TicketErrors.FIELD_INVALID));
        f.tickets.pool(DISPATCHER, no, "休假");
        assertThat(assignee(f, no)).as("退回抢单池").isNull();
        f.assertSnapshotMatches(no);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void roundRobinRotatesAndConcurrentCreatesNeverDoubleUp(TestDatabase db) throws Exception {
        TicketFixture f = new TicketFixture(db);
        f.groups.put("field-ops", List.of("mb_a", "mb_b", "mb_c"));
        f.definitions.publishType(app(), T.workspaceId(), type("rr", new AssignmentDef(Strategy.ROUND_ROBIN, "field-ops", null, null, null)), null, "API");
        List<String> seq = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seq.add(assignee(f, create(f, "rr", Map.of())));
        }
        assertThat(seq).containsExactly("member:mb_a", "member:mb_b", "member:mb_c", "member:mb_a");

        int n = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<String> c = () -> {
                    go.await();
                    return create(f, "rr", Map.of());
                };
                fs.add(pool.submit(c));
            }
            go.countDown();
            List<String> who = new ArrayList<>();
            for (Future<String> x : fs) {
                who.add(assignee(f, x.get()));
            }
            assertThat(who.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())))
                    .as("游标行加锁：6 张并发单三人各 2 张").containsExactlyInAnyOrderEntriesOf(Map.of("member:mb_a", 2L, "member:mb_b", 2L, "member:mb_c", 2L));
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void leastLoadedPicksTheLightestAndBreaksTiesByRotation(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        f.groups.put("field-ops", List.of("mb_a", "mb_b"));
        f.definitions.publishType(app(), T.workspaceId(), type("ll", new AssignmentDef(Strategy.LEAST_LOADED, "field-ops", null, null, null)), null, "API");
        f.definitions.publishType(app(), T.workspaceId(), type("man", AssignmentDef.MANUAL), null, "API");
        f.tickets.assign(DISPATCHER, create(f, "man", Map.of()), "mb_a", null);
        f.tickets.assign(DISPATCHER, create(f, "man", Map.of()), "mb_a", null);

        assertThat(assignee(f, create(f, "ll", Map.of()))).as("a 名下 2 件、b 0 件").isEqualTo("member:mb_b");
        assertThat(assignee(f, create(f, "ll", Map.of()))).as("b 1 件仍更少").isEqualTo("member:mb_b");
        assertThat(assignee(f, create(f, "ll", Map.of()))).as("2 比 2 平手：按轮转轮到 b 之后的 a").isEqualTo("member:mb_a");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void fieldStrategyUsesTheMemberFieldOrLeavesItForManualDispatch(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        f.definitions.publishType(app(), T.workspaceId(), type("fld", new AssignmentDef(Strategy.FIELD, null, "fixer", null, null)), null, "API");
        assertThat(assignee(f, create(f, "fld", Map.of("fixer", "mb_zed")))).isEqualTo("member:mb_zed");
        String empty = create(f, "fld", Map.of());
        assertThat(assignee(f, empty)).isNull();
        assertThatThrownBy(() -> f.tickets.assign(BOB, empty, "mb_zed", null)).as("没有 ticket:assign").extracting(TicketDispatchTest::key)
                .isEqualTo(CommonErrors.PERMISSION_DENIED);
        f.tickets.assign(DISPATCHER, empty, "mb_zed", null);
        assertThat(assignee(f, empty)).isEqualTo("member:mb_zed");
        assertThatThrownBy(() -> f.tickets.assign(DISPATCHER, empty, "mb_other", " ")).as("改派须写原因").extracting(TicketDispatchTest::key)
                .isEqualTo(ProcessErrors.REQUIRED_MISSING);
        f.tickets.assign(DISPATCHER, empty, "mb_other", "换人");
        assertThat(assignee(f, empty)).isEqualTo("member:mb_other");
        f.assertSnapshotMatches(empty);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void externalAssignmentIsFilledByTheAppOrFallsBackOnTimeout(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        f.definitions.publishType(app(), T.workspaceId(), type("ext", new AssignmentDef(Strategy.EXTERNAL, "field-ops", null, 300, Strategy.QUEUE)), null,
                "API");
        String filled = create(f, "ext", Map.of());
        assertThat(f.events).filteredOn(e -> e instanceof AssignmentRequested a && a.ticketNo().equals(filled)).singleElement()
                .satisfies(e -> assertThat(((AssignmentRequested) e).timeoutAtMs()).isEqualTo(f.clock.millis() + 300_000));
        assertThat(f.scheduled).contains(TicketDispatcher.ASSIGN_TIMEOUT_TASK + "@" + T.workspaceId().value() + ":" + filled + ":0");
        assertThatThrownBy(() -> f.tickets.externalAssignment(BOB, filled, "mb_x")).extracting(TicketDispatchTest::key).isEqualTo(CommonErrors.PERMISSION_DENIED);
        f.tickets.externalAssignment(app(), filled, "mb_x");
        assertThat(assignee(f, filled)).isEqualTo("member:mb_x");
        assertThatThrownBy(() -> f.tickets.externalAssignment(app(), filled, "mb_y")).extracting(TicketDispatchTest::key).isEqualTo(CommonErrors.STATE_CHANGED);
        assertThat(f.tickets.onAssignTimeout(T, filled)).as("已回填：超时无操作").isFalse();

        String lapsed = create(f, "ext", Map.of());
        assertThat(f.tickets.onAssignTimeout(T, lapsed)).isTrue();
        assertThat(f.jdbc.sql("SELECT queue_group FROM proc_task t JOIN tkt_ticket k ON k.current_task_no = t.task_no WHERE k.ticket_no = ?").param(lapsed)
                .query(String.class).single()).as("兜底为队列").isEqualTo("field-ops");
        assertThat(f.tickets.onAssignTimeout(T, lapsed)).as("重复触发不再建第二条").isFalse();
        assertThatThrownBy(() -> f.tickets.externalAssignment(app(), lapsed, "mb_y")).as("兜底已派出，外部再回填 → 409")
                .extracting(TicketDispatchTest::key).isEqualTo(CommonErrors.STATE_CHANGED);
    }
}
