package ai.neargo.opskit.ticket.store;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.jdbc.Dialects;
import ai.neargo.opskit.ticket.store.dao.AssignCursorDao;
import ai.neargo.opskit.ticket.store.dao.CommentDao;
import ai.neargo.opskit.ticket.store.dao.DefinitionDao;
import ai.neargo.opskit.ticket.store.dao.FieldIndexDao;
import ai.neargo.opskit.ticket.store.dao.TicketDao;
import ai.neargo.opskit.ticket.store.dao.TicketScopeDao;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 工单全部 DAO 的工厂（JDBC 类型不出 store，A5）。随 M3 各任务逐个加 DAO。 */
public record TicketStore(DefinitionDao definitions, TicketDao tickets, FieldIndexDao fieldIndex, TicketScopeDao scopes, CommentDao comments,
        AssignCursorDao cursors) {

    public static TicketStore create(DataSource dataSource) {
        JdbcClient j = JdbcClient.create(dataSource);
        Dialect d = Dialects.detect(dataSource);
        return new TicketStore(new DefinitionDao(j, d), new TicketDao(j, d), new FieldIndexDao(j), new TicketScopeDao(j), new CommentDao(j),
                new AssignCursorDao(j, d));
    }
}
