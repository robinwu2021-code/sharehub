package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.row.AppRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class AppDao {

    private static final RowMapper<AppRow> MAPPER = (rs, i) -> new AppRow(
            rs.getString("account_id"), rs.getString("app_id"), rs.getString("name"), rs.getString("credential_hash"),
            rs.getString("credential_hint"), rs.getInt("all_workspaces") == 1, rs.getString("callback_base_url"),
            rs.getString("signing_secret_enc"), rs.getString("status"), rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"));

    private final JdbcClient jdbc;

    public AppDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AccountId accountId, AppRow r) {
        jdbc.sql("INSERT INTO plat_app (account_id, app_id, name, credential_hash, credential_hint, all_workspaces, callback_base_url, "
                        + "signing_secret_enc, status, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(accountId.value(), r.appId(), r.name(), r.credentialHash(), r.credentialHint(), r.allWorkspaces() ? 1 : 0,
                        r.callbackBaseUrl(), r.signingSecretEnc(), r.status(), r.createdAtMs(), r.updatedAtMs())
                .update();
    }

    public Optional<AppRow> find(AccountId accountId, String appId) {
        return jdbc.sql("SELECT * FROM plat_app WHERE account_id = ? AND app_id = ?").params(accountId.value(), appId).query(MAPPER).optional();
    }

    /** 按名称找（启动引导按名称识别部署方声明的应用；同名多个时取最早的）。 */
    public Optional<AppRow> findByName(AccountId accountId, String name) {
        return jdbc.sql("SELECT * FROM plat_app WHERE account_id = ? AND name = ? ORDER BY id").params(accountId.value(), name).query(MAPPER).list()
                .stream().findFirst();
    }

    /**
     * 按凭证哈希找应用 —— 认证入口，此时还不知道账户，是全表唯一的例外（凭证哈希全局唯一，uk_cred）。
     * 第一个参数用 {@link AccountId#UNKNOWN} 占位，让「这里没有租户条件」在调用处显形。
     */
    public Optional<AppRow> findByCredentialHash(AccountId unknown, String credentialHash) {
        if (!AccountId.UNKNOWN.equals(unknown)) {
            throw new IllegalArgumentException("credential lookup happens before the account is known");
        }
        return jdbc.sql("SELECT * FROM plat_app WHERE credential_hash = ?").param(credentialHash).query(MAPPER).optional();
    }

    /** 轮换：只有旧哈希仍是当前值时才换（并发两次轮换只一次生效）。 */
    public int rotateCredential(AccountId accountId, String appId, String oldHash, String newHash, String newHint, String newSecretEnc, long now) {
        return jdbc.sql("UPDATE plat_app SET credential_hash = ?, credential_hint = ?, signing_secret_enc = ?, updated_at_ms = ? "
                        + "WHERE account_id = ? AND app_id = ? AND credential_hash = ?")
                .params(newHash, newHint, newSecretEnc, now, accountId.value(), appId, oldHash).update();
    }

    public void replaceWorkspaces(AccountId accountId, String appId, Collection<String> workspaceIds) {
        // plat_app_workspace 没有 account_id 列：先按账户确认应用存在，再按应用写
        if (find(accountId, appId).isEmpty()) {
            throw new IllegalStateException("app not in account: " + appId);
        }
        jdbc.sql("DELETE FROM plat_app_workspace WHERE app_id = ?").param(appId).update();
        for (String ws : workspaceIds) {
            jdbc.sql("INSERT INTO plat_app_workspace (app_id, workspace_id) VALUES (?, ?)").params(appId, ws).update();
        }
    }

    public List<String> workspaces(AccountId accountId, String appId) {
        return jdbc.sql("SELECT aw.workspace_id FROM plat_app_workspace aw JOIN plat_app a ON a.app_id = aw.app_id "
                        + "WHERE a.account_id = ? AND aw.app_id = ? ORDER BY aw.workspace_id")
                .params(accountId.value(), appId).query(String.class).list();
    }
}
