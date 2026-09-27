package ai.neargo.opskit.process.core.domain.archfixture;

/** 违反 A4：领域层碰 JDBC。 */
public class DomainUsesSql {
    java.sql.Connection connection;
}
