package ai.neargo.opskit.ticket.core.app;

import static ai.neargo.opskit.ticket.core.app.TicketFixture.T;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.app;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.ticket.api.TicketErrors;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.api.model.BulkReassignRequest;
import ai.neargo.opskit.ticket.api.model.BulkReassignResult;
import ai.neargo.opskit.ticket.api.model.TicketView;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O3-6：升级（接管）· 合并（不能并入自己 · 被并单走合并迁移）· 批量改派只动未结、单张失败不回滚其余 · 关注人可见。 */
class TicketOpsTest {

    static final Actor LEAD = member("mb_lead", Set.of(TicketAppService.ASSIGN, TicketAppService.ESCALATE, TicketAppService.MERGE, TicketAppService.WORK),
            Set.of());

    /** 手工派单、支持合并（合并迁移 = cancel）。 */
    static TicketTypeDef mergeable() {
        TicketTypeDef b = TicketDispatchTest.type("mm", AssignmentDef.MANUAL);
        return new TicketTypeDef(b.code(), b.name(), b.processCode(), b.fields(), b.priorityOptions(), b.resolutionOptions(), null, b.assignment(),
                List.of(), List.of(), null, "cancel");
    }

    static TicketFixture fixture(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        f.definitions.publishType(app(), T.workspaceId(), mergeable(), null, "API");
        return f;
    }

    static String create(TicketFixture f) {
        return TicketDispatchTest.create(f, "mm", Map.of());
    }

    static List<String> commentKinds(TicketFixture f, String no) {
        return f.jdbc.sql("SELECT kind FROM tkt_comment WHERE ticket_no = ? ORDER BY seq").param(no).query(String.class).list();
    }

    static Object key(Throwable ex) {
        return ((OpsKitException) ex).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void escalationFlagsNotesAndOptionallyTakesOver(TestDatabase db) {
        TicketFixture f = fixture(db);
        String no = create(f);
        assertThatThrownBy(() -> f.tickets.escalate(TicketDispatchTest.BOB, no, null, "急")).extracting(TicketOpsTest::key).isEqualTo(CommonErrors.PERMISSION_DENIED);
        assertThatThrownBy(() -> f.tickets.escalate(LEAD, no, null, " ")).extracting(TicketOpsTest::key).isEqualTo(TicketErrors.FIELD_INVALID);

        TicketView v = f.tickets.escalate(LEAD, no, "mb_senior", "客户投诉三次");
        assertThat(v.escalated()).isTrue();
        assertThat(v.assignee()).isEqualTo("member:mb_senior");
        assertThat(commentKinds(f, no)).containsExactly("SYSTEM");
        f.assertSnapshotMatches(no);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void mergeClosesTheSourceRecordsWhereItWentAndRefusesSelfOrClosed(TestDatabase db) {
        TicketFixture f = fixture(db);
        String a = create(f);
        String b = create(f);
        assertThatThrownBy(() -> f.tickets.merge(LEAD, a, a, null)).extracting(TicketOpsTest::key).isEqualTo(TicketErrors.MERGE_INTO_SELF);

        TicketView merged = f.tickets.merge(LEAD, a, b, "同一台设备");
        assertThat(merged.open()).isFalse();
        assertThat(merged.statusCategory()).isEqualTo("CANCELLED");
        assertThat(merged.mergedInto()).isEqualTo(b);
        assertThat(f.tickets.get(app(), b).open()).as("目标单不受影响").isTrue();
        assertThat(commentKinds(f, a)).containsExactly("MERGE");
        assertThat(commentKinds(f, b)).containsExactly("MERGE");
        f.assertSnapshotMatches(a);

        String c = create(f);
        assertThatThrownBy(() -> f.tickets.merge(LEAD, c, a, null)).as("不能并入已关的单").extracting(TicketOpsTest::key).isEqualTo(TicketErrors.TICKET_CLOSED);
        String plain = f.tickets.create(TicketDispatchTest.BOB, TicketAppServiceTest.req("D-X", "P2")).ticket().ticketNo();
        assertThatThrownBy(() -> f.tickets.merge(LEAD, plain, c, null)).as("类型没配合并迁移")
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::error).containsExactly("opskit.ticket.merge_not_supported"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void bulkReassignOnlyTouchesOpenTicketsAndReportsPerTicket(TestDatabase db) {
        TicketFixture f = fixture(db);
        String t1 = create(f);
        String t2 = create(f);
        String t3 = create(f);
        for (String no : List.of(t1, t2, t3)) {
            f.tickets.assign(LEAD, no, "mb_a", null);
        }
        f.tickets.transition(app(), t3, "cancel", "作废", null);

        BulkReassignResult picked = f.tickets.bulkReassign(LEAD, new BulkReassignRequest(List.of(t1, t3), null, "mb_b", "a 请假"));
        assertThat(picked.succeeded()).containsExactly(t1);
        assertThat(picked.failed()).singleElement().satisfies(x -> {
            assertThat(x.ticketNo()).isEqualTo(t3);
            assertThat(x.error()).isEqualTo(TicketErrors.TICKET_CLOSED.key());
        });
        assertThat(f.tickets.get(app(), t1).assignee()).isEqualTo("member:mb_b");

        BulkReassignResult byFrom = f.tickets.bulkReassign(LEAD, new BulkReassignRequest(null, "mb_a", "mb_c", "a 离职"));
        assertThat(byFrom.succeeded()).as("a 名下只剩 t2 未结").containsExactly(t2);
        assertThat(byFrom.remaining()).isZero();
        assertThat(f.tickets.get(app(), t3).assignee()).as("已关的单不动").isEqualTo("member:mb_a");

        assertThatThrownBy(() -> f.tickets.bulkReassign(LEAD, new BulkReassignRequest(List.of(t1), "mb_a", "mb_c", "x")))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("ticketNos"));
        assertThatThrownBy(() -> f.tickets.bulkReassign(LEAD, new BulkReassignRequest(null, null, null, null)))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("ticketNos", "to", "reason"));
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void watchersCanSeeTheTicketUntilRemoved(TestDatabase db) {
        TicketFixture f = fixture(db);
        String no = create(f);
        Actor watcher = member("mb_w", Set.of(), Set.of());
        assertThatThrownBy(() -> f.tickets.get(watcher, no)).extracting(TicketOpsTest::key).isEqualTo(CommonErrors.NOT_FOUND);
        f.tickets.setWatchers(LEAD, no, List.of("mb_w"));
        assertThat(f.tickets.get(watcher, no).ticketNo()).isEqualTo(no);
        f.tickets.setWatchers(LEAD, no, List.of());
        assertThatThrownBy(() -> f.tickets.get(watcher, no)).as("移除后又看不见").extracting(TicketOpsTest::key).isEqualTo(CommonErrors.NOT_FOUND);
    }
}
