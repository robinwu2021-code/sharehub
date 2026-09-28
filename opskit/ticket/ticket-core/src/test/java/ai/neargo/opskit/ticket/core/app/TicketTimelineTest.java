package ai.neargo.opskit.ticket.core.app;

import static ai.neargo.opskit.ticket.core.app.TicketFixture.app;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.ticket.api.model.TimelineItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O3-7：公开 / 内部评论 · 附件引用 · 时间线合成（建单 · 派单 · 迁移 · SLA · 评论 · 去重 · 升级）；无内部评论权限看不到内部评论。 */
class TicketTimelineTest {

    static final Actor LEAD = member("mb_lead", Set.of(TicketAppService.ASSIGN, TicketAppService.WORK, TicketAppService.ESCALATE,
            TicketAppService.COMMENT_INTERNAL, TicketAppService.VIEW_ALL), Set.of());
    static final Actor BOB = TicketAppServiceTest.BOB;

    static Object key(Throwable ex) {
        return ((OpsKitException) ex).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void theTimelineMergesProcessHistoryAndCommentsInOrder(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        String no = f.tickets.create(BOB, TicketAppServiceTest.req("D-1", "P2")).ticket().ticketNo();
        f.clock.advance(java.time.Duration.ofMinutes(1));
        f.tickets.assign(LEAD, no, "mb_fixer", null);
        f.clock.advance(java.time.Duration.ofMinutes(1));
        f.tickets.transition(BOB, no, "accept", null, null);
        f.clock.advance(java.time.Duration.ofMinutes(1));
        f.tickets.comment(BOB, no, "什么时候能修好？", "PUBLIC", List.of(Map.of("id", "f1", "name", "现场.jpg", "size", 2048)));
        f.clock.advance(java.time.Duration.ofMinutes(1));
        f.tickets.comment(LEAD, no, "备件缺货，先别承诺时间", "INTERNAL", null);
        f.clock.advance(java.time.Duration.ofMinutes(1));
        f.tickets.escalate(LEAD, no, null, "客户第二次催");
        f.tickets.create(member("mb_amy", Set.of(TicketAppService.CREATE), Set.of()), TicketAppServiceTest.req("D-1", "P2"));

        List<TimelineItem> all = f.tickets.timeline(LEAD, no);
        assertThat(all).extracting(TimelineItem::kind).containsSubsequence("CREATED", "ASSIGNED", "TRANSITION", "COMMENT", "COMMENT", "ESCALATED", "DEDUPE");
        assertThat(all).extracting(TimelineItem::seq).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, all.size()).boxed().toList());
        assertThat(all).filteredOn(i -> "TRANSITION".equals(i.kind())).first().satisfies(i -> {
            assertThat(i.actionName().resolve("zh", "en")).isEqualTo("受理");
            assertThat(i.toName().resolve("zh", "en")).isEqualTo("处理中");
        });
        assertThat(all).filteredOn(i -> "COMMENT".equals(i.kind()) && "PUBLIC".equals(i.visibility())).singleElement()
                .satisfies(i -> assertThat(i.attachments()).singleElement().satisfies(a -> assertThat(a).containsEntry("name", "现场.jpg")));
        assertThat(all).filteredOn(i -> "ESCALATED".equals(i.kind())).singleElement().satisfies(i -> assertThat(i.hot()).isTrue());

        List<TimelineItem> bobs = f.tickets.timeline(BOB, no);
        assertThat(bobs).as("负对照：没有内部评论权限看不到内部评论").noneMatch(i -> "INTERNAL".equals(i.visibility()));
        assertThat(bobs.size()).isLessThan(all.size());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void commentPermissionsAndValidation(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        String no = f.tickets.create(BOB, TicketAppServiceTest.req("D-2", "P2")).ticket().ticketNo();
        assertThatThrownBy(() -> f.tickets.comment(BOB, no, "内部", "INTERNAL", null)).extracting(TicketTimelineTest::key)
                .isEqualTo(CommonErrors.PERMISSION_DENIED);
        assertThatThrownBy(() -> f.tickets.comment(member("mb_stranger", Set.of(), Set.of()), no, "路过", "PUBLIC", null))
                .as("看不见就是不存在").extracting(TicketTimelineTest::key).isEqualTo(CommonErrors.NOT_FOUND);
        assertThatThrownBy(() -> f.tickets.comment(member("mb_viewer", Set.of(TicketAppService.VIEW_ALL), Set.of()), no, "只读的人", "PUBLIC", null))
                .as("看得见但既非办理权限也非参与者").extracting(TicketTimelineTest::key).isEqualTo(CommonErrors.PERMISSION_DENIED);
        assertThatThrownBy(() -> f.tickets.comment(BOB, no, " ", "PUBLIC", List.of())).satisfies(ex ->
                assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("body"));
        assertThatThrownBy(() -> f.tickets.comment(BOB, no, "图", "PUBLIC", List.of(Map.of("name", "缺 id")))).satisfies(ex ->
                assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("attachments[0]"));
        assertThatThrownBy(() -> f.tickets.comment(BOB, no, "x", "SECRET", null)).satisfies(ex ->
                assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("visibility"));
        assertThat(f.tickets.comment(app(), no, "应用留言", "INTERNAL", null)).isEqualTo(1);
    }
}
