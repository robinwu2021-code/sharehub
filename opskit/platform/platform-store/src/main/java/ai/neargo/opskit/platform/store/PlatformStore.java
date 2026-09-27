package ai.neargo.opskit.platform.store;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.jdbc.Dialects;
import ai.neargo.opskit.platform.store.dao.AccountDao;
import ai.neargo.opskit.platform.store.dao.AppDao;
import ai.neargo.opskit.platform.store.dao.AuditDao;
import ai.neargo.opskit.platform.store.dao.CalendarDao;
import ai.neargo.opskit.platform.store.dao.GroupDao;
import ai.neargo.opskit.platform.store.dao.GroupMemberDao;
import ai.neargo.opskit.platform.store.dao.IdempotencyDao;
import ai.neargo.opskit.platform.store.dao.IdpDao;
import ai.neargo.opskit.platform.store.dao.MemberAttrDao;
import ai.neargo.opskit.platform.store.dao.MemberDao;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import ai.neargo.opskit.platform.store.dao.RoleBindingDao;
import ai.neargo.opskit.platform.store.dao.RoleDao;
import ai.neargo.opskit.platform.store.dao.WebhookDao;
import ai.neargo.opskit.platform.store.dao.WorkspaceDao;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 平台全部 DAO 的工厂。core 的装配只把数据源交进来 —— JDBC 的类型（JdbcClient 等）不出 store 模块（架构测试 A5）。
 */
public record PlatformStore(AccountDao accounts, WorkspaceDao workspaces, AppDao apps, MemberDao members, GroupDao groups,
        GroupMemberDao groupMembers, RoleDao roles, RoleBindingDao roleBindings, CalendarDao calendars, OutboxDao outbox,
        WebhookDao webhooks, IdempotencyDao idempotency, AuditDao audit, IdpDao idps, MemberAttrDao memberAttrs) {

    public static PlatformStore create(DataSource dataSource) {
        JdbcClient j = JdbcClient.create(dataSource);
        Dialect d = Dialects.detect(dataSource);
        return new PlatformStore(new AccountDao(j), new WorkspaceDao(j), new AppDao(j), new MemberDao(j, d), new GroupDao(j, d),
                new GroupMemberDao(j, d), new RoleDao(j, d), new RoleBindingDao(j, d), new CalendarDao(j, d), new OutboxDao(j, d),
                new WebhookDao(j, d), new IdempotencyDao(j, d), new AuditDao(j, d), new IdpDao(j, d), new MemberAttrDao(j, d));
    }
}
