package ai.neargo.opskit.platform.core.support;

import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.store.PlatformMigrations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 平台用例测试的公共装配：迁移好的库 + 真事务 + 可控时钟。不起 Spring 容器。 */
public final class PlatformFixture {

    public final TestDatabase db;
    public final JdbcClient jdbc;
    public final TransactionTemplate tx;
    public final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T08:00:00Z"));
    public final BizNoGenerator ids = new BizNoGenerator(clock);

    public PlatformFixture(TestDatabase db) {
        this.db = db;
        PlatformMigrations.migrate(db.dataSource());
        this.jdbc = JdbcClient.create(db.dataSource());
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
    }

    /** 可拨动的时钟：过期、超时类用例靠它，不 sleep。 */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void advanceMillis(long ms) {
            now = now.plusMillis(ms);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
