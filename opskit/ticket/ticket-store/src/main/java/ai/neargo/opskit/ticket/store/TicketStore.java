package ai.neargo.opskit.ticket.store;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.jdbc.Dialects;
import ai.neargo.opskit.ticket.store.dao.DefinitionDao;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 工单全部 DAO 的工厂（JDBC 类型不出 store，A5）。随 M3 各任务逐个加 DAO。 */
public record TicketStore(DefinitionDao definitions) {

    public static TicketStore create(DataSource dataSource) {
        JdbcClient j = JdbcClient.create(dataSource);
        Dialect d = Dialects.detect(dataSource);
        return new TicketStore(new DefinitionDao(j, d));
    }
}
