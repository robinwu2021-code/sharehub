package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.row.IdpRow;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class IdpDao {

    private static final RowMapper<IdpRow> MAPPER = (rs, i) -> new IdpRow(
            rs.getString("account_id"), rs.getString("issuer"), rs.getString("kind"), JsonColumns.readMap(rs.getString("config")),
            JsonColumns.readMap(rs.getString("claim_mapping")), rs.getInt("jit_enabled") == 1, rs.getString("status"), rs.getLong("updated_at_ms"));

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public IdpDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** issuer 全局唯一（uk_issuer）：已被别的账户占用时，upsert 会改到别人的行 —— 所以先查归属再写（见 FederationAppService）。 */
    public void upsert(AccountId account, IdpRow r) {
        String sql = dialect.upsert("plat_idp", List.of("issuer"),
                List.of("account_id", "issuer", "kind", "config", "claim_mapping", "jit_enabled", "status", "updated_at_ms"),
                List.of("kind", "config", "claim_mapping", "jit_enabled", "status", "updated_at_ms"), Set.of("config", "claim_mapping"));
        jdbc.sql(sql).params(account.value(), r.issuer(), r.kind(), JsonColumns.write(r.config()), JsonColumns.write(r.claimMapping()),
                r.jitEnabled() ? 1 : 0, r.status(), r.updatedAtMs()).update();
    }

    /** 认证入口：此时还不知道账户，issuer 本身全局唯一。 */
    public Optional<IdpRow> findByIssuer(AccountId unknown, String issuer) {
        requireUnknown(unknown);
        return jdbc.sql("SELECT * FROM plat_idp WHERE issuer = ?").param(issuer).query(MAPPER).optional();
    }

    /** 全部内省类 IdP（按令牌前缀路由用；量小，由调用方缓存）。 */
    public List<IdpRow> introspectionIdps(AccountId unknown) {
        requireUnknown(unknown);
        return jdbc.sql("SELECT * FROM plat_idp WHERE kind = 'INTROSPECT' AND status = 'ACTIVE'").query(MAPPER).list();
    }

    private static void requireUnknown(AccountId a) {
        if (!AccountId.UNKNOWN.equals(a)) {
            throw new IllegalArgumentException("idp lookup happens before the account is known");
        }
    }
}
