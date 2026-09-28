package ai.neargo.opskit.process.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.jdbc.Dialects;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.api.model.StartRequest;
import ai.neargo.opskit.process.api.model.query.TodoQuery;
import ai.neargo.opskit.process.api.model.TransitionRequest;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.core.support.ProcessFixture;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * 21/07 O4-5 性能基线（手动跑，不进默认 verify）：
 * <pre>scripts/verify.sh -pl process/process-core test -Dtest=ProcessPerfBaselineTest -Dopskit.perf=true [-Dopskit.perf.tasks=1000000]</pre>
 *
 * <p>MariaDB 11.4（生产同系），缓冲池 512M；直接用 SQL 造数：任务 N 条（默认 100 万，其中 5% 未结）、每条 2 个候选（成员 + 组）、
 * 实例 N/4 条（一半打范围标签）、5000 成员 · 40 组 · 200 个范围值。然后经应用服务（与线上同一条路径）量：
 * <ul>
 *   <li>待办列表（首页 20 条）与角标：目标 P95 &lt; 100 ms；</li>
 *   <li>迁移（submit：建办理任务 + 候选 + 流转日志 + 事件）：目标 P95 &lt; 20 ms。</li>
 * </ul>
 * 并对待办 SQL 跑 {@code ANALYZE FORMAT=JSON}（实际读行数）。结果写 target/perf/baseline.md。
 */
@EnabledIfSystemProperty(named = "opskit.perf", matches = "true")
class ProcessPerfBaselineTest {

    private static final int MEMBERS = 5000;
    private static final int GROUPS = 40;
    private static final int SCOPES = 200;
    private static final int RUNS = 200;

    /** 记下最近一条待办查询的 SQL 与参数，供 ANALYZE 复现。 */
    static final AtomicReference<Object[]> lastTodoSql = new AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicLong statements = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong commits = new java.util.concurrent.atomic.AtomicLong();

    @Test
    void baseline() throws Exception {
        int tasks = Integer.getInteger("opskit.perf.tasks", 1_000_000);
        try (MariaDBContainer db = new MariaDBContainer("mariadb:11.4").withUsername("root").withPassword("test")
                .withCommand("--innodb-buffer-pool-size=512M", "--performance-schema=OFF", "--max-connections=200",
                        // 诊断用：=2 时提交不逐次落盘，用来区分「引擎耗时」与「容器虚拟机的 fsync」；基线本身按生产默认 1 跑
                        "--innodb-flush-log-at-trx-commit=" + Integer.getInteger("opskit.perf.flush", 1))) {
            db.start();
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(db.getJdbcUrl());
            pool.setUsername("root");
            pool.setPassword("test");
            pool.setMaximumPoolSize(10);
            DataSource ds = recording(pool);
            ProcessFixture f = new ProcessFixture(new TestDatabase("mariadb", ds, Dialects.detect(ds)));
            Services s = services(f);

            long[] rtt = new long[RUNS];
            try (Connection c = pool.getConnection(); PreparedStatement ping = c.prepareStatement("SELECT 1")) {
                for (int i = 0; i < RUNS; i++) {
                    long b = System.nanoTime();
                    try (ResultSet rs = ping.executeQuery()) {
                        rs.next();
                    }
                    rtt[i] = System.nanoTime() - b;
                }
            }
            // 对照：空库上的迁移耗时 —— 区分「数据量带来的」与「环境本身的」（容器虚拟机里的提交落盘）
            long[] transitionEmpty = transitions(s, f, 0);

            long t0 = System.nanoTime();
            long definitionId = f.jdbc.sql("SELECT id FROM proc_definition WHERE code = 'dispatching'").query(Long.class).single();
            seed(pool, f, definitionId, tasks);
            long seedMs = (System.nanoTime() - t0) / 1_000_000;
            try (Connection c = pool.getConnection()) {
                c.createStatement().execute("ANALYZE TABLE proc_task, proc_task_candidate, proc_instance, proc_instance_scope, proc_participant");
            }

            Random rnd = new Random(7);
            // 预热
            for (int i = 0; i < 30; i++) {
                s.q.todos(viewer(f, rnd), new TodoQuery("ALL", null, 1, 20));
            }
            sqlTime.clear();
            long[] todo = new long[RUNS];
            long[] counts = new long[RUNS];
            long sampleTotal = 0;
            for (int i = 0; i < RUNS; i++) {
                Actor a = viewer(f, rnd);
                long b = System.nanoTime();
                sampleTotal += s.q.todos(a, new TodoQuery("ALL", null, 1, 20)).total();
                todo[i] = System.nanoTime() - b;
                b = System.nanoTime();
                s.q.todoCount(a);
                counts[i] = System.nanoTime() - b;
            }
            StringBuilder hot = new StringBuilder();
            sqlTime.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(6)
                    .forEach(e -> hot.append("| ").append(String.format("%.1f", e.getValue() / 1e6 / RUNS)).append(" | `")
                            .append(e.getKey().replace("|", "\\|")).append("` |\n"));
            Object[] captured = lastTodoSql.get();
            String analyze = captured == null ? "(未捕获到待办 SQL)" : analyze(pool, (String) captured[0], (Object[]) captured[1]);

            long[] transition = transitions(s, f, 1);

            String report = """
                    # OpsKit 性能基线（21/07 O4-5）

                    | 项 | 值 |
                    |---|---|
                    | 数据库 | MariaDB 11.4（容器，innodb_buffer_pool_size=512M，innodb_flush_log_at_trx_commit=%d） |
                    | 数据量 | 任务 %,d（未结 5%%）· 候选 %,d · 实例 %,d（一半带范围标签）· 成员 %d · 组 %d · 范围值 %d |
                    | 造数耗时 | %,d ms |
                    | 采样 | 各 %d 次（随机成员，1 组 · 3 个范围值）；平均每人待办 %.1f 条 |

                    | 操作 | P50 ms | P95 ms | P99 ms | 目标 P95 |
                    |---|--:|--:|--:|--:|
                    | 待办列表（首页 20 条 + 总数） | %s | %s | %s | 100 |
                    | 待办角标（四个页签） | %s | %s | %s | 100 |
                    | 迁移（submit，建任务） | %s | %s | %s | 20 |
                    | 对照：空库上的同一迁移 | %s | %s | %s | — |
                    | 对照：`SELECT 1` 往返 | %s | %s | %s | — |

                    每轮（发起 + 迁移）：%s。迁移只提交一次；它的耗时 ≈ 语句数 × 往返 + 一次提交落盘，
                    本机容器虚拟机的往返与 fsync 都远慢于同机部署，所以迁移的绝对值以生产机为准，这里守的是「不随数据量变慢」。

                    ## 待办采样期间各语句的平均耗时（每次「列表 + 角标」合计，ms）

                    | ms / 次 | 语句 |
                    |--:|---|
                    %s
                    ## 待办 SQL 的 ANALYZE（最后一次采样）

                    ```json
                    %s
                    ```
                    """.formatted(Integer.getInteger("opskit.perf.flush", 1), tasks, tasks * 2L, tasks / 4, MEMBERS, GROUPS, SCOPES, seedMs, RUNS, (double) sampleTotal / RUNS,
                    p(todo, 50), p(todo, 95), p(todo, 99), p(counts, 50), p(counts, 95), p(counts, 99),
                    p(transition, 50), p(transition, 95), p(transition, 99),
                    p(transitionEmpty, 50), p(transitionEmpty, 95), p(transitionEmpty, 99), p(rtt, 50), p(rtt, 95), p(rtt, 99), perRound, hot, analyze);
            Path out = Path.of("target", "perf", "baseline.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report, StandardCharsets.UTF_8);
            System.out.println(report);
            pool.close();

            assertThat(ms(todo, 95)).as("待办 P95（见 target/perf/baseline.md）").isLessThan(100);
            assertThat(ms(counts, 95)).as("角标 P95").isLessThan(100);
            // 迁移：绝对值受环境支配（见报告），守「百万数据下不比空库慢」—— 允许 1.5 倍 + 5 ms 的抖动
            assertThat(ms(transition, 95)).as("迁移 P95 不随数据量变慢（空库 P95 = %d ms）", ms(transitionEmpty, 95))
                    .isLessThanOrEqualTo(ms(transitionEmpty, 95) * 3 / 2 + 5);
        }
    }

    static String perRound = "";
    static final java.util.concurrent.ConcurrentHashMap<String, Long> sqlTime = new java.util.concurrent.ConcurrentHashMap<>();

    private static String shorten(String sql) {
        String one = sql.replaceAll("\\s+", " ");
        return one.length() > 90 ? one.substring(0, 90) + "…" : one;
    }

    private static long[] transitions(Services s, ProcessFixture f, int round) {
        long[] out = new long[RUNS];
        long st0 = 0;
        long cm0 = 0;
        Actor requester = new Actor(f.tenant, Optional.of(PrincipalRef.member("mb_perf_req" + round)), Optional.empty(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of());
        for (int i = 0; i < 20 + RUNS; i++) {
            String no = s.instances.start(requester, new StartRequest("dispatching", null, Map.of(), Map.of(), Set.of("site:S" + (i % SCOPES))))
                    .instanceNo();
            if (i == 20) {
                st0 = statements.get();
                cm0 = commits.get();
            }
            long b = System.nanoTime();
            s.instances.transition(requester, no, "submit", TransitionRequest.empty());
            if (i >= 20) {
                out[i - 20] = System.nanoTime() - b;
            }
        }
        // 注意：区间里也含每轮的 start —— 两者分开统计意义不大，这里报「start + submit」一轮的平均
        perRound = String.format("%.1f 条语句 · %.1f 次提交", (statements.get() - st0) / (double) RUNS, (commits.get() - cm0) / (double) RUNS);
        return out;
    }

    // ── 造数 ────────────────────────────────────────────────

    private static void seed(DataSource ds, ProcessFixture f, long definitionId, int tasks) throws Exception {
        Random rnd = new Random(42);
        int instances = tasks / 4;
        String ws = f.workspace.value();
        String ac = f.account.value();
        long now = f.clock.millis();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            Batch inst = new Batch(c, "INSERT INTO proc_instance (account_id, workspace_id, instance_no, definition_id, process_code, process_version, "
                    + "subject_type, subject_ref, status, status_category, is_open, event_seq, created_by, created_at_ms, updated_at_ms) VALUES ");
            Batch scope = new Batch(c, "INSERT INTO proc_instance_scope (workspace_id, instance_no, scope_tag) VALUES ");
            Batch part = new Batch(c, "INSERT INTO proc_participant (workspace_id, instance_no, role, principal, added_at_ms) VALUES ");
            Batch task = new Batch(c, "INSERT INTO proc_task (account_id, workspace_id, task_no, kind, instance_no, queue_group, title, priority_ord, status, "
                    + "assignee, due_at_ms, created_at_ms, updated_at_ms) VALUES ");
            Batch cand = new Batch(c, "INSERT INTO proc_task_candidate (workspace_id, task_no, instance_no, candidate_key, is_open, task_kind, origin, "
                    + "priority_ord, due_at_ms, created_at_ms) VALUES ");
            int taskSeq = 0;
            for (int i = 0; i < instances; i++) {
                String no = "PIPERF" + i;
                boolean openInst = i % 5 == 0; // 20% 实例未结，其中的任务约 1/4 未结 → 任务未结 ≈ 5%
                String requester = "member:mb_" + rnd.nextInt(MEMBERS);
                inst.row(q(ac), q(ws), q(no), definitionId, q("dispatching"), q("perf"), q("device"), q("D" + i), q(openInst ? "dispatched" : "done"),
                        q(openInst ? "IN_PROGRESS" : "DONE"), openInst ? 1 : 0, 3, q(requester), now - i, now - i);
                if (i % 2 == 0) {
                    scope.row(q(ws), q(no), q("site:S" + rnd.nextInt(SCOPES)));
                }
                part.row(q(ws), q(no), q("REQUESTER"), q(requester), now - i);
                for (int k = 0; k < 4; k++) {
                    String tno = "TKPERF" + (taskSeq++);
                    boolean open = openInst && k == 3;
                    String group = "g_" + rnd.nextInt(GROUPS);
                    String member = "mb_" + rnd.nextInt(MEMBERS);
                    boolean assigned = open && rnd.nextInt(4) == 0;
                    Long due = rnd.nextInt(3) == 0 ? null : now + rnd.nextInt(86_400_000);
                    task.row(q(ac), q(ws), q(tno), q("WORK"), q(no), rnd.nextBoolean() ? q(group) : "NULL", q("任务 " + tno), rnd.nextInt(3),
                            q(open ? (assigned ? "ASSIGNED" : "OPEN") : "DONE"), assigned ? q("member:" + member) : "NULL", due == null ? "NULL" : due,
                            now - i, now - i);
                    cand.row(q(ws), q(tno), q(no), q("member:" + member), open ? 1 : 0, q("WORK"), q("PROCESS"), 1, due == null ? "NULL" : due, now - i);
                    cand.row(q(ws), q(tno), q(no), q("group:" + group), open ? 1 : 0, q("WORK"), q("PROCESS"), 1, due == null ? "NULL" : due, now - i);
                }
            }
            for (Batch b : List.of(inst, scope, part, task, cand)) {
                b.flush();
            }
            c.commit();
        }
    }

    /** 多行 INSERT，每 1000 行一发（字面量拼接：造数用，值全是本类生成的）。 */
    static final class Batch {
        private final Connection c;
        private final String head;
        private final List<String> rows = new ArrayList<>();

        Batch(Connection c, String head) {
            this.c = c;
            this.head = head;
        }

        void row(Object... values) throws Exception {
            StringJoiner j = new StringJoiner(", ", "(", ")");
            for (Object v : values) {
                j.add(String.valueOf(v));
            }
            rows.add(j.toString());
            if (rows.size() >= 1000) {
                flush();
            }
        }

        void flush() throws Exception {
            if (rows.isEmpty()) {
                return;
            }
            try (var st = c.createStatement()) {
                st.execute(head + String.join(", ", rows));
            }
            rows.clear();
        }
    }

    private static String q(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ── 采样与报告 ──────────────────────────────────────────

    private static Actor viewer(ProcessFixture f, Random rnd) {
        return new Actor(new TenantContext(f.account, f.workspace), Optional.of(PrincipalRef.member("mb_" + rnd.nextInt(MEMBERS))), Optional.empty(),
                Set.of("g_" + rnd.nextInt(GROUPS)), Set.of(), Set.of(), Set.of(),
                new java.util.HashSet<>(List.of("site:S" + rnd.nextInt(SCOPES), "site:S" + rnd.nextInt(SCOPES), "site:S" + rnd.nextInt(SCOPES))));
    }

    private static long ms(long[] nanos, int pct) {
        long[] s = nanos.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.ceil(pct / 100.0 * s.length) - 1)] / 1_000_000;
    }

    private static String p(long[] nanos, int pct) {
        long[] s = nanos.clone();
        Arrays.sort(s);
        return String.format("%.1f", s[Math.min(s.length - 1, (int) Math.ceil(pct / 100.0 * s.length) - 1)] / 1e6);
    }

    private static String analyze(DataSource ds, String sql, Object[] params) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement st = c.prepareStatement("ANALYZE FORMAT=JSON " + sql)) {
            for (int i = 0; i < params.length; i++) {
                st.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    /** 包一层 DataSource：待办 SQL（带候选 EXISTS 的列表查询）记下文本与参数。 */
    private static DataSource recording(DataSource inner) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, args) -> {
            Object r = m.invoke(inner, args);
            if (!(r instanceof Connection conn)) {
                return r;
            }
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (p2, m2, a2) -> {
                if ("commit".equals(m2.getName())) {
                    commits.incrementAndGet();
                }
                if ("prepareStatement".equals(m2.getName()) || "createStatement".equals(m2.getName())) {
                    statements.incrementAndGet();
                }
                Object r2 = m2.invoke(conn, a2);
                if ("prepareStatement".equals(m2.getName()) && a2 != null && a2[0] instanceof String sql0 && r2 instanceof PreparedStatement ps0
                        && !sql0.contains("ORDER BY CASE WHEN t.due_at_ms")) {
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (p3, m3, a3) -> {
                        long b = System.nanoTime();
                        try {
                            return m3.invoke(ps0, a3);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        } finally {
                            if (m3.getName().startsWith("execute")) {
                                sqlTime.merge(shorten(sql0), System.nanoTime() - b, Long::sum);
                            }
                        }
                    });
                }
                if ("prepareStatement".equals(m2.getName()) && a2 != null && a2[0] instanceof String sql && sql.contains("ORDER BY CASE WHEN t.due_at_ms")
                        && r2 instanceof PreparedStatement ps) {
                    Object[] params = new Object[64];
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (p3, m3, a3) -> {
                        if (m3.getName().startsWith("set") && a3 != null && a3.length >= 2 && a3[0] instanceof Integer idx && idx <= params.length) {
                            params[idx - 1] = a3[1];
                        }
                        if (m3.getName().startsWith("execute")) {
                            int n = (int) sql.chars().filter(ch -> ch == '?').count();
                            lastTodoSql.set(new Object[] {sql, Arrays.copyOf(params, n)});
                        }
                        long b = System.nanoTime();
                        try {
                            return m3.invoke(ps, a3);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        } finally {
                            if (m3.getName().startsWith("execute")) {
                                sqlTime.merge("待办列表 SQL", System.nanoTime() - b, Long::sum);
                            }
                        }
                    });
                }
                return r2;
            });
        });
    }

    // ── 服务装配（与 ProcessQueryServiceTest 相同） ──────────────

    record Services(InstanceAppService instances, ProcessQueryService q) {
    }

    private static Services services(ProcessFixture f) throws IOException {
        ProcessDefinitionAppService defs = new ProcessDefinitionAppService(f.store.definitions(), f.audit, f.clock, f.tx);
        defs.publish(f.system(), f.workspace, TaskAppServiceTest.dispatching(), null, "API");
        Directory dir = new Directory() {
            @Override
            public List<String> membersOf(TenantContext t, String g) {
                return List.of();
            }

            @Override
            public Optional<Integer> claimTimeoutSec(TenantContext t, String g) {
                return Optional.empty();
            }

            @Override
            public boolean isQueue(TenantContext t, String g) {
                return g.equals("field-ops");
            }
        };
        BizNoGenerator ids = new BizNoGenerator(f.clock);
        TaskAppService tasks = new TaskAppService(f.store.tasks(), f.store.participants(), dir, (n, id, at) -> { }, ev -> { }, ids, f.clock, f.tx);
        AtomicReference<InstanceAppService> ref = new AtomicReference<>();
        ApprovalAppService approvals = new ApprovalAppService(defs, f.store.approvals(), f.store.tasks(), f.store.instances(), f.store.participants(),
                f.store.logs(), tasks, ref::get, dir, ev -> { }, ids, f.clock, f.tx);
        InstanceAppService inst = new InstanceAppService(defs, f.store.instances(), f.store.participants(), f.store.scopes(), f.store.logs(),
                List.of(tasks, approvals), Optional.of(tasks), ev -> { }, ids, f.clock, f.tx);
        ref.set(inst);
        return new Services(inst, new ProcessQueryService(defs, f.store.queries(), dir));
    }
}
