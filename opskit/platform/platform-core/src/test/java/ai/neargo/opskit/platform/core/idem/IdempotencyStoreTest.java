package ai.neargo.opskit.platform.core.idem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.core.support.PlatformFixture;
import ai.neargo.opskit.platform.store.dao.IdempotencyDao;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-10：占位 → 回填 → 重放；同键不同内容 409；并发同键只执行一次。 */
class IdempotencyStoreTest {

    private static IdempotencyStore store(PlatformFixture f) {
        return new IdempotencyStore(new IdempotencyDao(f.jdbc, f.db.dialect()), f.clock, f.tx, Duration.ofDays(7));
    }

    private static Object key(OpsKitException e) {
        return e.errorKey();
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void firstRunProceedsThenTheResultIsReplayedVerbatim(TestDatabase db) {
        IdempotencyStore s = store(new PlatformFixture(db));
        assertThat(s.begin("app:a", "k1", "h1")).isInstanceOf(IdempotencyStore.Outcome.Proceed.class);
        s.complete("app:a", "k1", 201, "{\"ticketNo\":\"TK1\"}");

        IdempotencyStore.Outcome again = s.begin("app:a", "k1", "h1");
        assertThat(again).isInstanceOf(IdempotencyStore.Outcome.Replay.class);
        IdempotencyStore.Outcome.Replay r = (IdempotencyStore.Outcome.Replay) again;
        assertThat(r.httpStatus()).isEqualTo(201);
        assertThat(r.body().replace(" ", "")).isEqualTo("{\"ticketNo\":\"TK1\"}");

        assertThat(s.begin("app:b", "k1", "h1")).as("别的调用方同名键互不相干").isInstanceOf(IdempotencyStore.Outcome.Proceed.class);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void sameKeyDifferentRequestIsRejected(TestDatabase db) {
        IdempotencyStore s = store(new PlatformFixture(db));
        s.begin("app:a", "k1", "h1");
        s.complete("app:a", "k1", 200, "{}");
        assertThatThrownBy(() -> s.begin("app:a", "k1", "h2"))
                .extracting(e -> key((OpsKitException) e)).isEqualTo(CommonErrors.IDEMPOTENCY_MISMATCH);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void concurrentSameKeyRunsTheBusinessExactlyOnce(TestDatabase db) throws Exception {
        IdempotencyStore s = store(new PlatformFixture(db));
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit((Callable<String>) () -> {
                go.await();
                try {
                    return s.begin("app:a", "k1", "h1") instanceof IdempotencyStore.Outcome.Proceed ? "proceed" : "replay";
                } catch (OpsKitException e) {
                    return e.errorKey().key();
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn("proceed"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("proceed")).allMatch("opskit.idempotency.in_progress"::equals);
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void abandonedRequestCanBeRetriedAndOldRecordsArePurged(TestDatabase db) {
        PlatformFixture f = new PlatformFixture(db);
        IdempotencyStore s = store(f);
        s.begin("app:a", "k1", "h1");
        s.abandon("app:a", "k1");
        assertThat(s.begin("app:a", "k1", "h1")).isInstanceOf(IdempotencyStore.Outcome.Proceed.class);
        s.complete("app:a", "k1", 200, "{}");

        f.clock.advanceMillis(Duration.ofDays(8).toMillis());
        assertThat(s.purgeExpired()).isEqualTo(1);
        assertThat(s.begin("app:a", "k1", "h2")).as("过了保留期同键可再用").isInstanceOf(IdempotencyStore.Outcome.Proceed.class);
    }
}
