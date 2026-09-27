package ai.neargo.opskit.commons.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/** 按连接元数据选方言。只认三种；其他数据库直接拒绝，而不是用某个方言「凑合」。 */
public final class Dialects {

    private Dialects() {
    }

    public static Dialect detect(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            return forProductName(c.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new IllegalStateException("cannot detect database dialect", e);
        }
    }

    public static Dialect forProductName(String productName) {
        String p = productName.toLowerCase(Locale.ROOT);
        if (p.contains("mysql") || p.contains("mariadb")) {
            return new MySqlDialect();
        }
        if (p.contains("postgres")) {
            return new PostgresDialect();
        }
        if (p.equals("h2")) {
            return new H2Dialect();
        }
        throw new IllegalStateException("unsupported database: " + productName);
    }
}
