package ai.neargo.opskit.process.store;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.jdbc.Dialects;
import ai.neargo.opskit.process.store.dao.DefinitionDao;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 流程全部 DAO 的工厂（JDBC 类型不出 store，A5）。随 M2 各任务逐个加 DAO。 */
public record ProcessStore(DefinitionDao definitions) {

    public static ProcessStore create(DataSource dataSource) {
        JdbcClient j = JdbcClient.create(dataSource);
        Dialect d = Dialects.detect(dataSource);
        return new ProcessStore(new DefinitionDao(j, d));
    }
}
