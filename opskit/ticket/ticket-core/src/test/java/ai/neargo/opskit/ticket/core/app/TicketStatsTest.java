package ai.neargo.opskit.ticket.core.app;

import static ai.neargo.opskit.ticket.core.app.TicketFixture.app;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.member;
import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.ticket.api.model.TicketQuery;
import ai.neargo.opskit.ticket.api.model.TicketStats;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O3-9：汇总卡与列表同口径（每张卡的数 = 用它的 queryParams 查出的条数）· 按人 · 按组 · 未结数 · 可见性同列表。 */
class TicketStatsTest {

    static final Actor ALL = TicketQueryTest.ALL;

    record Env(TicketFixture f, TicketQueryService q, TicketStatsService stats) {
    }

    static Env env(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        TicketQueryService q = new TicketQueryService(f.ticketStore.queries(), f.definitions, f.tickets, f.directory);
        return new Env(f, q, new TicketStatsService(q, f.ticketStore.queries(), f.directory, t -> ZoneId.of("UTC"), f.clock));
    }

    static TicketQuery none() {
        return new TicketQuery(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void everyCardEqualsTheListItLinksTo(TestDatabase db) {
        Env e = env(db);
        TicketFixture f = e.f();
        String a = TicketQueryTest.create(f, "S1", "D-1", "P1", "一");
        String b = TicketQueryTest.create(f, "S2", "D-2", "P2", "二");
        String c = TicketQueryTest.create(f, "S1", "D-3", "P2", "三");
        String d = TicketQueryTest.create(f, "S1", "D-4", "P2", "四");
        f.tickets.assign(ALL, a, "mb_a", null);
        f.tickets.assign(ALL, b, "mb_b", null);
        f.tickets.transition(TicketAppServiceTest.BOB, a, "accept", null, null);
        f.tickets.escalate(member("mb_lead", Set.of(TicketAppService.ESCALATE), Set.of()), c, null, "催");
        f.tickets.transition(TicketAppServiceTest.BOB, b, "accept", null, null);
        f.tickets.transition(TicketAppServiceTest.BOB, b, "finish", null, null);
        f.clock.advance(Duration.ofHours(30)); // d 的 24 小时解决时限已过，但还没有定时触发 → 快照仍是 RUNNING

        TicketStats.Summary s = e.stats().summary(ALL, none());
        Map<String, Long> byKey = new java.util.LinkedHashMap<>();
        s.cards().forEach(card -> byKey.put(card.key(), card.value()));
        assertThat(byKey).containsEntry("open", 3L).containsEntry("inProgress", 1L).containsEntry("unassigned", 2L).containsEntry("escalated", 1L)
                .containsEntry("closedToday", 0L);
        for (TicketStats.Card card : s.cards()) {
            TicketQuery q = card.queryParams();
            TicketQuery paged = new TicketQuery(q.typeCode(), q.statusCategory(), q.status(), q.priority(), q.assignee(), q.requester(), q.group(),
                    q.assigneeAttr(), q.slaState(), q.scopes(), q.fieldFilters(), q.keyword(), q.open(), q.escalated(), q.createdFromMs(), q.createdToMs(),
                    q.closedFromMs(), q.closedToMs(), null, 1, 200);
            assertThat(e.q().query(ALL, paged).total()).as("卡「%s」与它链接的列表同口径", card.key()).isEqualTo(card.value());
        }
        assertThat(e.stats().openCount(ALL, none())).isEqualTo(3);
        assertThat(e.stats().summary(member("mb_stranger", Set.of(), Set.of()), none()).cards()).as("看不见的不计").allMatch(card -> card.value() == 0);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void closedTodayUsesTheWorkspaceDay(TestDatabase db) {
        Env e = env(db);
        String a = TicketQueryTest.create(e.f(), "S1", "D-1", "P2", "一");
        e.f().tickets.assign(ALL, a, "mb_a", null);
        e.f().tickets.transition(TicketAppServiceTest.BOB, a, "accept", null, null);
        e.f().tickets.transition(TicketAppServiceTest.BOB, a, "finish", null, null);
        TicketStats.Summary s = e.stats().summary(ALL, none());
        assertThat(s.cards().stream().filter(c -> c.key().equals("closedToday")).findFirst().orElseThrow().value()).isEqualTo(1);
        assertThat(s.zone()).isEqualTo("UTC");
        assertThat(s.todayStartMs()).isEqualTo(java.time.Instant.parse("2026-09-28T00:00:00Z").toEpochMilli());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void byAssigneeAndByGroup(TestDatabase db) {
        Env e = env(db);
        TicketFixture f = e.f();
        String a1 = TicketQueryTest.create(f, "S1", "D-1", "P2", "一");
        String a2 = TicketQueryTest.create(f, "S1", "D-2", "P2", "二");
        String b1 = TicketQueryTest.create(f, "S2", "D-3", "P2", "三");
        TicketQueryTest.create(f, "S2", "D-4", "P2", "四");
        f.tickets.assign(ALL, a1, "mb_a", null);
        f.tickets.assign(ALL, a2, "mb_a", null);
        f.tickets.assign(ALL, b1, "mb_b", null);
        f.clock.advance(Duration.ofHours(2));
        f.tickets.transition(TicketAppServiceTest.BOB, a1, "accept", null, null);
        f.tickets.transition(TicketAppServiceTest.BOB, a1, "finish", null, null);

        List<TicketStats.Row> rows = e.stats().byAssignee(ALL, none());
        assertThat(rows).filteredOn(r -> "mb_a".equals(r.key())).singleElement().satisfies(r -> {
            assertThat(r.total()).isEqualTo(2);
            assertThat(r.open()).isEqualTo(1);
            assertThat(r.closed()).isEqualTo(1);
            assertThat(r.metRate()).as("24 小时内解决 → 按时").isEqualTo(1.0);
            assertThat(r.avgResolutionMs()).isEqualTo(Duration.ofHours(2).toMillis());
            assertThat(r.queryParams().assignee()).isEqualTo("mb_a");
        });
        assertThat(rows).filteredOn(r -> r.key() == null).singleElement().satisfies(r -> {
            assertThat(r.open()).as("未派的一行").isEqualTo(1);
            assertThat(r.queryParams().assignee()).isEqualTo("none");
        });

        f.groups.put("east", List.of("mb_a", "mb_b"));
        f.groups.put("west", List.of("mb_c"));
        assertThat(e.stats().byGroup(ALL, none(), List.of("east", "west"))).extracting(TicketStats.Row::key, TicketStats.Row::total)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("east", 3L), org.assertj.core.groups.Tuple.tuple("west", 0L));
    }
}
