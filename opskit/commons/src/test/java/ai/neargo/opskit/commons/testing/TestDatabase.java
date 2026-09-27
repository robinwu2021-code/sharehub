package ai.neargo.opskit.commons.testing;

import ai.neargo.opskit.commons.jdbc.Dialect;
import javax.sql.DataSource;

/** 一个可用于测试的数据库。{@link #toString()} 是名字，让参数化测试的报告直接显示是哪个库。 */
public record TestDatabase(String name, DataSource dataSource, Dialect dialect) {
    @Override
    public String toString() {
        return name;
    }
}
