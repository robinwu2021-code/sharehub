package ai.neargo.opskit.ticket.core.app;

import static ai.neargo.opskit.ticket.core.app.TicketFixture.T;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.app;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.fields;
import static ai.neargo.opskit.ticket.core.app.TicketFixture.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.ticket.api.TicketErrors;
import ai.neargo.opskit.ticket.api.model.CreateTicketRequest;
import ai.neargo.opskit.ticket.api.model.CreateTicketResult;
import ai.neargo.opskit.ticket.api.model.TicketView;
import ai.neargo.opskit.ticket.api.model.UpdateTicketRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O3-3 · O3-4：建单（去重占位 · 标签派生 · 启动实例 · 索引）· 并发同键只一张 · 快照与实例始终一致（含定时触发的 SLA 变化）。 */
class TicketAppServiceTest {

    static final Actor BOB = member("mb_bob", Set.of(TicketAppService.CREATE), Set.of());

    static CreateTicketRequest req(String device, String priority) {
        return new CreateTicketRequest("repair", "扫码没反应", "柜机屏幕无响应", fields("S1", device), priority, null, Set.of("region:east"), null);
    }

    static Object key(Throwable ex) {
        return ((OpsKitException) ex).errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void createStartsTheInstanceIndexesFieldsAndDerivesScopes(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        CreateTicketResult r = f.tickets.create(BOB, req("D-1", "P1"));
        TicketView v = r.ticket();

        assertThat(r.attached()).isFalse();
        assertThat(v.ticketNo()).startsWith("RP");
        assertThat(v.instanceNo()).startsWith("PI");
        assertThat(v.requester()).isEqualTo("member:mb_bob");
        assertThat(v.priorityOrd()).isZero();
        assertThat(v.status()).isEqualTo("new");
        assertThat(v.scopes()).containsExactlyInAnyOrder("site:S1", "region:east");
        assertThat(v.slaState()).isEqualTo("RUNNING");
        assertThat(v.slaNextDueAtMs() - v.slaStartedAtMs()).as("P1 按 4 小时").isEqualTo(Duration.ofHours(4).toMillis());
        assertThat(f.jdbc.sql("SELECT field_code FROM tkt_field_value WHERE ticket_no = ? ORDER BY field_code").param(v.ticketNo()).query(String.class).list())
                .containsExactly("device", "site");
        f.assertSnapshotMatches(v.ticketNo());
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void theSameDeviceAttachesUntilTheTicketIsClosed(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        String first = f.tickets.create(BOB, req("D-7", "P2")).ticket().ticketNo();
        CreateTicketResult again = f.tickets.create(member("mb_amy", Set.of(TicketAppService.CREATE), Set.of()), req("D-7", "P2"));
        assertThat(again.attached()).isTrue();
        assertThat(again.ticket().ticketNo()).isEqualTo(first);
        assertThat(f.jdbc.sql("SELECT kind FROM tkt_comment WHERE ticket_no = ?").param(first).query(String.class).list()).containsExactly("DEDUPE_HIT");
        assertThat(f.jdbc.sql("SELECT COUNT(*) FROM proc_instance").query(Integer.class).single()).as("命中去重不启动实例").isEqualTo(1);

        f.tickets.transition(app(), first, "cancel", "误报", null);
        assertThat(f.tickets.get(app(), first).open()).isFalse();
        CreateTicketResult fresh = f.tickets.create(BOB, req("D-7", "P2"));
        assertThat(fresh.attached()).as("旧单关了，同键可以再建").isFalse();
        assertThat(fresh.ticket().ticketNo()).isNotEqualTo(first);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void concurrentCreatesWithTheSameKeyYieldOneTicket(TestDatabase db) throws Exception {
        TicketFixture f = new TicketFixture(db);
        int n = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<CreateTicketResult>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<CreateTicketResult> c = () -> {
                    go.await();
                    return f.tickets.create(BOB, req("D-RACE", "P2"));
                };
                fs.add(pool.submit(c));
            }
            go.countDown();
            List<CreateTicketResult> rs = new java.util.ArrayList<>();
            for (Future<CreateTicketResult> x : fs) {
                rs.add(x.get());
            }
            assertThat(rs.stream().filter(r -> !r.attached()).count()).as("只有一张是新建的").isEqualTo(1);
            assertThat(rs.stream().map(r -> r.ticket().ticketNo()).distinct()).hasSize(1);
            assertThat(f.jdbc.sql("SELECT COUNT(*) FROM tkt_ticket").query(Integer.class).single()).isEqualTo(1);
            assertThat(f.jdbc.sql("SELECT COUNT(*) FROM proc_instance").query(Integer.class).single()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void theSnapshotFollowsEveryInstanceChangeIncludingTimerDrivenSla(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        TicketView v = f.tickets.create(BOB, req("D-2", "P1")).ticket();
        String no = v.ticketNo();

        f.tickets.transition(BOB, no, "accept", null, null);
        f.assertSnapshotMatches(no);
        // 绕过工单直接迁移实例：快照也得跟上（观察者，同事务）
        f.processes.transition(BOB, v.instanceNo(), "ask", TransitionRequest.empty());
        assertThat(f.tickets.get(app(), no).status()).isEqualTo("waiting");
        assertThat(f.tickets.get(app(), no).slaState()).isEqualTo("PAUSED");
        f.assertSnapshotMatches(no);
        f.processes.transition(BOB, v.instanceNo(), "reply", TransitionRequest.empty());

        f.clock.advance(Duration.ofHours(5));
        String clockId = f.jdbc.sql("SELECT clock_id FROM proc_sla_clock WHERE instance_no = ?").param(v.instanceNo()).query(String.class).single();
        int epoch = f.jdbc.sql("SELECT epoch FROM proc_sla_clock WHERE instance_no = ?").param(v.instanceNo()).query(Integer.class).single();
        assertThat(f.sla.onDue(T, clockId, epoch)).isTrue();
        assertThat(f.tickets.get(app(), no).slaState()).as("定时触发的逾期也同步进快照").isEqualTo("BREACHED");

        f.tickets.transition(BOB, no, "finish", "换了屏", "FIXED");
        TicketView done = f.tickets.get(app(), no);
        assertThat(done.open()).isFalse();
        assertThat(done.closedAtMs()).isEqualTo(f.clock.millis());
        assertThat(done.resolution()).isEqualTo("FIXED");
        assertThat(done.slaState()).isEqualTo("BREACHED");
        f.assertSnapshotMatches(no);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void editingReindexesRetargetsSlaAndKeepsExplicitScopes(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        TicketView v = f.tickets.create(BOB, req("D-3", "P2")).ticket();
        Actor worker = member("mb_fixer", Set.of(TicketAppService.WORK), Set.of());

        TicketView edited = f.tickets.update(worker, v.ticketNo(), new UpdateTicketRequest(null, null, "P1", Map.of("site", "S2")));
        assertThat(edited.priority()).isEqualTo("P1");
        assertThat(edited.scopes()).as("派生标签换了，显式标签保留").containsExactlyInAnyOrder("site:S2", "region:east");
        assertThat(edited.slaNextDueAtMs() - edited.slaStartedAtMs()).as("改成 P1 后按 4 小时重算").isEqualTo(Duration.ofHours(4).toMillis());
        assertThat(f.jdbc.sql("SELECT v_str FROM tkt_field_value WHERE ticket_no = ? AND field_code = 'site'").param(v.ticketNo()).query(String.class).single())
                .isEqualTo("S2");
        f.assertSnapshotMatches(v.ticketNo());

        Map<String, Object> clear = new HashMap<>();
        clear.put("device", null);
        assertThatThrownBy(() -> f.tickets.update(worker, v.ticketNo(), new UpdateTicketRequest(null, null, null, clear)))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("fields.device"));
        assertThatThrownBy(() -> f.tickets.update(BOB, v.ticketNo(), new UpdateTicketRequest("改标题", null, null, null)))
                .as("没有 ticket:work").extracting(TicketAppServiceTest::key).isEqualTo(CommonErrors.PERMISSION_DENIED);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void createValidatesAndVisibilityIsFailClosed(TestDatabase db) {
        TicketFixture f = new TicketFixture(db);
        assertThatThrownBy(() -> f.tickets.create(BOB, new CreateTicketRequest("repair", " ", null, Map.of("site", "S9"), "P7", null, Set.of(), null)))
                .satisfies(ex -> {
                    assertThat(key(ex)).isEqualTo(TicketErrors.FIELD_INVALID);
                    assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path)
                            .containsExactlyInAnyOrder("title", "priority", "fields.site", "fields.device");
                });
        assertThatThrownBy(() -> f.tickets.create(member("mb_x", Set.of(), Set.of()), req("D-4", "P2")))
                .extracting(TicketAppServiceTest::key).isEqualTo(CommonErrors.PERMISSION_DENIED);
        assertThatThrownBy(() -> f.tickets.create(BOB, new CreateTicketRequest("ghost", "t", null, Map.of(), null, null, Set.of(), null)))
                .satisfies(ex -> assertThat(((OpsKitException) ex).fieldErrors()).extracting(FieldError::path).containsExactly("typeCode"));

        String no = f.tickets.create(BOB, req("D-5", "P2")).ticket().ticketNo();
        assertThat(f.tickets.get(BOB, no).ticketNo()).as("提出人看得见").isEqualTo(no);
        assertThatThrownBy(() -> f.tickets.get(member("mb_stranger", Set.of(), Set.of()), no)).extracting(TicketAppServiceTest::key)
                .isEqualTo(CommonErrors.NOT_FOUND);
        assertThat(f.tickets.get(member("mb_ops", Set.of(TicketAppService.VIEW_SCOPED), Set.of("site:S1")), no)).isNotNull();
        assertThatThrownBy(() -> f.tickets.get(member("mb_ops", Set.of(TicketAppService.VIEW_SCOPED), Set.of("site:S2")), no))
                .as("负对照：范围不相交").extracting(TicketAppServiceTest::key).isEqualTo(CommonErrors.NOT_FOUND);
    }
}
