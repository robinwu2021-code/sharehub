package ai.neargo.opskit.commons.testing;

import ai.neargo.opskit.commons.jdbc.Dialects;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 三库测试基座（21/07 O0-4）：MariaDB（生产）· MySQL 8 · PostgreSQL 16 · H2（快速档）。
 *
 * <p>用法：{@code @ParameterizedTest @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")}。
 * 容器在同一个测试 JVM 内只启动一次；每次 {@link #all()} 返回的是各库里一个**新建的空库**，
 * 用例之间互不污染，也就不需要清表。
 *
 * <p>系统属性 {@code opskit.test.databases}（逗号分隔）可缩小范围，如只跑 {@code h2}。
 * H2 只是快速档，**不作为通过依据**（21/07 §八）。
 */
public final class TestDatabases {

    public static final String PROPERTY = "opskit.test.databases";
    private static final List<String> DEFAULT = List.of("mariadb", "mysql", "postgresql", "h2");

    private static final Map<String, JdbcDatabaseContainer<?>> CONTAINERS = new LinkedHashMap<>();

    private TestDatabases() {
    }

    public static Stream<TestDatabase> all() {
        return selected().stream().map(TestDatabases::freshDatabase);
    }

    static List<String> selected() {
        String value = System.getProperty(PROPERTY, "").trim();
        return value.isEmpty() ? DEFAULT : Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    public static TestDatabase freshDatabase(String name) {
        String db = "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataSource ds = switch (name) {
            case "h2" -> dataSource("org.h2.Driver",
                    "jdbc:h2:mem:" + db + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            case "mariadb", "mysql", "postgresql" -> createDatabase(container(name), db);
            default -> throw new IllegalArgumentException("unknown test database: " + name);
        };
        return new TestDatabase(name, ds, Dialects.detect(ds));
    }

    private static synchronized JdbcDatabaseContainer<?> container(String name) {
        return CONTAINERS.computeIfAbsent(name, n -> {
            JdbcDatabaseContainer<?> c = switch (n) {
                // 压低内存：本机容器虚拟机常只有 2GiB，MySQL 8.4 默认配置会被 OOM 杀掉（退出码 137，实测）
                case "mariadb" -> new MariaDBContainer("mariadb:11.4").withUsername("root").withPassword("test")
                        .withCommand("--innodb-buffer-pool-size=64M", "--performance-schema=OFF", "--max-connections=200");
                case "mysql" -> new MySQLContainer("mysql:8.4").withUsername("root").withPassword("test")
                        .withCommand("--innodb-buffer-pool-size=64M", "--performance-schema=OFF", "--max-connections=200");
                case "postgresql" -> new PostgreSQLContainer("postgres:16-alpine");
                default -> throw new IllegalArgumentException(n);
            };
            c.start();
            // JVM 退出时主动停掉：本机实测 Ryuk 看守容器会消失，遗留的整套数据库把 2GiB 的容器虚拟机挤爆（MySQL 被 OOM 杀掉）
            Runtime.getRuntime().addShutdownHook(new Thread(c::stop, "stop-" + n));
            return c;
        });
    }

    private static DataSource createDatabase(JdbcDatabaseContainer<?> c, String db) {
        DataSource admin = dataSource(c.getDriverClassName(), c.getJdbcUrl(), c.getUsername(), c.getPassword());
        try (var conn = admin.getConnection(); var st = conn.createStatement()) {
            boolean pg = c instanceof PostgreSQLContainer;
            st.execute(pg ? "CREATE DATABASE " + db
                          : "CREATE DATABASE " + db + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot create test database on " + c.getDockerImageName(), e);
        }
        String url = c.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
        return dataSource(c.getDriverClassName(), url, c.getUsername(), c.getPassword());
    }

    private static DataSource dataSource(String driver, String url, String user, String password) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends java.sql.Driver> type = (Class<? extends java.sql.Driver>) Class.forName(driver);
            return new SimpleDriverDataSource(type.getDeclaredConstructor().newInstance(), url, user, password);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot load driver " + driver, e);
        }
    }
}
