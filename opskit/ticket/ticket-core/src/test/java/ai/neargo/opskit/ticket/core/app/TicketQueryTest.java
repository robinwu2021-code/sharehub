package ai.neargo.opskit.ticket.core.app;

import static ai.neargo.opskit.ticket.core.app.TicketFixture.app;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.ticket.api.TicketErrors;
import ai.neargo.opskit.ticket.api.model.CreateTicketRequest;
import ai.neargo.opskit.ticket.api.model.TicketQuery;
import ai.neargo.opskit.ticket.api.model.TicketQuery.FieldFilter;
import ai.neargo.opskit.ticket.api.model.TicketView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O3-8：每个筛选参数一条 · 排序 · 计数与列表同口径 · 可见性 fail-closed（负对照）。 */
class TicketQueryTest {

    static final Actor ALL = member("mb_a", Set.of(TicketAppService.VIEW_ALL, TicketAppService.ASSIGN), Set.of());

    /** t1：S1 · D-1 · P1 · 派给 mb_a；t2：S2 · D-2 · P2；t3：S1 · D-3 · P2 · 已作废。提出人都是 bob。 */
    record Env(TicketFixture f, String t1, String t2, String t3, QueryHelper q) {
    }

    record QueryHelper(TicketQueryService s) {
        List<String> nos(Actor a, TicketQuery q) {
            return s.query(a, q).list().stream().map(TicketView::ticketNo).toList();
        }
    }

    static TicketQuery q() {
        return new TicketQuery(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 1, 50);
    }

    /** 以 q() 为底改一项：用 Map 描述，免得每条用例都写 21 个参数。 */
    static TicketQuery with(Map<String, Object> m) {
        return new TicketQuery((List<String>) m.get("typeCode"), (List<String>) m.get("statusCategory"), (List<String>) m.get("status"),
                (List<String>) m.get("priority"), (String) m.get("assignee"), (String) m.get("requester"), (String) m.get("group"),
                (TicketQuery.AssigneeAttr) m.get("assigneeAttr"), (List<String>) m.get("slaState"), (List<String>) m.get("scopes"),
                (List<FieldFilter>) m.get("fieldFilters"), (String) m.get("keyword"), (Boolean) m.get("open"), null, null, null,
                (Long) m.get("closedFromMs"), null, (String) m.get("sort"), 1, 50);
    }

    static Env env(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        String t1 = create(f, "S1", "D-1", "P1", "屏幕碎了");
        String t2 = create(f, "S2", "D-2", "P2", "扫码没反应");
        String t3 = create(f, "S1", "D-3", "P2", "误报");
        f.tickets.assign(ALL, t1, "mb_a", null);
        f.tickets.transition(app(), t3, "cancel", "误报", null);
        return new Env(f, t1, t2, t3, new QueryHelper(new TicketQueryService(ticketQueries(f), f.definitions, f.tickets, directoryOf(f))));
    }

    static ai.neargo.opskit.ticket.store.dao.TicketQueryDao ticketQueries(TicketFixture f) {
        return f.ticketStore.queries();
    }

    static ai.neargo.opskit.platform.client.DirectoryClient directoryOf(TicketFixture f) {
        return f.directory;
    }

    static String create(TicketFixture f, String site, String device, String priority, String title) {
        return f.tickets.create(TicketAppServiceTest.BOB, new CreateTicketRequest("repair", title, null, TicketFixture.fields(site, device), priority, null,
                Set.of(), null)).ticket().ticketNo();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void eachFilterNarrowsAsDocumented(TestDatabase db) {
        Env e = env(db);
        QueryHelper q = e.q();
        assertThat(q.nos(ALL, with(Map.of("typeCode", List.of("repair"))))).containsExactlyInAnyOrder(e.t1(), e.t2(), e.t3());
        assertThat(q.nos(ALL, with(Map.of("statusCategory", List.of("CANCELLED"))))).containsExactly(e.t3());
        assertThat(q.nos(ALL, with(Map.of("status", List.of("new"))))).containsExactlyInAnyOrder(e.t1(), e.t2());
        assertThat(q.nos(ALL, with(Map.of("priority", List.of("P1"))))).containsExactly(e.t1());
        assertThat(q.nos(ALL, with(Map.of("assignee", "me")))).containsExactly(e.t1());
        assertThat(q.nos(ALL, with(Map.of("assignee", "none", "open", true)))).containsExactly(e.t2());
        assertThat(q.nos(ALL, with(Map.of("requester", "mb_bob")))).hasSize(3);
        assertThat(q.nos(ALL, with(Map.of("slaState", List.of("RUNNING"))))).containsExactlyInAnyOrder(e.t1(), e.t2());
        assertThat(q.nos(ALL, with(Map.of("scopes", List.of("site:S1"))))).containsExactlyInAnyOrder(e.t1(), e.t3());
        assertThat(q.nos(ALL, with(Map.of("keyword", "扫码")))).containsExactly(e.t2());
        assertThat(q.nos(ALL, with(Map.of("keyword", e.t3())))).containsExactly(e.t3());
        assertThat(q.nos(ALL, with(Map.of("open", false, "closedFromMs", 0L)))).containsExactly(e.t3());

        e.f().groups.put("field-ops", List.of("mb_a"));
        assertThat(q.nos(ALL, with(Map.of("group", "field-ops")))).containsExactly(e.t1());
        e.f().attrMembers = k -> "partyType=STAFF".equals(k) ? List.of("mb_a") : List.of();
        assertThat(q.nos(ALL, with(Map.of("assigneeAttr", new TicketQuery.AssigneeAttr("partyType", List.of("STAFF")))))).containsExactly(e.t1());
        assertThat(q.nos(ALL, with(Map.of("assigneeAttr", new TicketQuery.AssigneeAttr("partyType", List.of("AGENT")))))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void fieldFiltersUseTheIndexAndAreValidated(TestDatabase db) {
        Env e = env(db);
        QueryHelper q = e.q();
        List<String> repair = List.of("repair");
        assertThat(q.nos(ALL, with(Map.of("typeCode", repair, "fieldFilters", List.of(new FieldFilter("site", null, "S2")))))).containsExactly(e.t2());
        assertThat(q.nos(ALL, with(Map.of("typeCode", repair, "fieldFilters", List.of(new FieldFilter("device", "IN",
                List.of(Map.of("type", "device", "ref", "D-1"), "device:D-3"))))))).containsExactlyInAnyOrder(e.t1(), e.t3());

        assertThatThrownBy(() -> q.nos(ALL, with(Map.of("fieldFilters", List.of(new FieldFilter("site", null, "S2"))))))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("fieldFilters"));
        assertThatThrownBy(() -> q.nos(ALL, with(Map.of("typeCode", repair, "fieldFilters", List.of(new FieldFilter("note", null, "x"),
                new FieldFilter("site", "GT", "S1"))))))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path)
                        .containsExactly("fieldFilters[0].field", "fieldFilters[1].op"));
        assertThatThrownBy(() -> q.nos(ALL, with(Map.of("statusCategory", List.of("CLOSED"), "sort", "title"))))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("statusCategory[0]", "sort"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void sortingCountsAndTheTooBroadGuard(TestDatabase db) {
        Env e = env(db);
        assertThat(e.q().nos(ALL, with(Map.of("sort", "priority"))).get(0)).as("P1 排最前").isEqualTo(e.t1());
        assertThat(e.q().s().query(ALL, with(Map.of("status", List.of("new")))).total()).isEqualTo(2);
        List<String> many = new ArrayList<>(IntStream.rangeClosed(0, TicketQueryService.ATTR_LIMIT).mapToObj(i -> "mb_" + i).toList());
        e.f().attrMembers = k -> many;
        assertThatThrownBy(() -> e.q().nos(ALL, with(Map.of("assigneeAttr", new TicketQuery.AssigneeAttr("partyType", List.of("AGENT"))))))
                .satisfies(ex -> assertThat(((OpsKitException) ex).errorKey()).isEqualTo(TicketErrors.FILTER_TOO_BROAD));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void visibilityIsFailClosed(TestDatabase db) {
        Env e = env(db);
        QueryHelper q = e.q();
        assertThat(q.nos(member("mb_stranger", Set.of(), Set.of()), q())).as("负对照：无权限、非参与者 → 一张也不出").isEmpty();
        assertThat(q.nos(member("mb_stranger", Set.of(TicketAppService.VIEW_SCOPED), Set.of()), q())).as("有 scoped 权限但没有范围").isEmpty();
        assertThat(q.nos(TicketAppServiceTest.BOB, q())).as("提出人看自己的").hasSize(3);
        assertThat(q.nos(member("mb_a", Set.of(), Set.of()), q())).as("办理人看自己的").containsExactly(e.t1());
        assertThat(q.nos(member("mb_s1", Set.of(TicketAppService.VIEW_SCOPED), Set.of("site:S1")), q())).containsExactlyInAnyOrder(e.t1(), e.t3());
        assertThat(q.nos(member("mb_any", Set.of(TicketAppService.VIEW_SCOPED), Set.of("*")), q())).hasSize(3);
        assertThat(q.nos(member("mb_any", Set.of(), Set.of("*")), q())).as("* 不代替 view:scoped 权限").isEmpty();
    }
}
