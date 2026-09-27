package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.row.AccountRow;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class AccountDao {

    private static final RowMapper<AccountRow> MAPPER = (rs, i) -> new AccountRow(
            rs.getString("account_id"), rs.getString("name"), rs.getString("kind"), rs.getString("plan"),
            rs.getString("status"), rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"));

    private final JdbcClient jdbc;

    public AccountDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AccountId accountId, AccountRow row) {
        jdbc.sql("INSERT INTO plat_account (account_id, name, kind, plan, status, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(accountId.value(), row.name(), row.kind(), row.plan(), row.status(), row.createdAtMs(), row.updatedAtMs())
                .update();
    }

    public Optional<AccountRow> find(AccountId accountId) {
        return jdbc.sql("SELECT * FROM plat_account WHERE account_id = ?").param(accountId.value()).query(MAPPER).optional();
    }
}
