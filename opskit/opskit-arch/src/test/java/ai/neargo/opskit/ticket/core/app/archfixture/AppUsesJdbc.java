package ai.neargo.opskit.ticket.core.app.archfixture;

/** 违反 A5：store 之外用 JdbcClient。 */
public class AppUsesJdbc {
    org.springframework.jdbc.core.simple.JdbcClient jdbc;
}
