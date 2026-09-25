package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **属主标记不接受客户端传入。**
 *
 * <h2>它是什么</h2>
 * {@code agt_account.is_owner} 按 [ADR-030 §5.1] 是<b>主体属主</b>：
 * {@code AgentIdentityPort} 的注释写着「<b>全站点全权限、不进授权表</b>」，
 * 而 {@code AgentLoginServiceImpl:129} 确实把它喂进身份链路。
 * 换句话说这一位是 1，这个账号就绕过整套授权表。
 *
 * <h2>补之前的问题</h2>
 * {@code AgtAccount} 实体直接当 {@code @RequestBody}，而
 * {@code beforeUpdate} 只锁了 {@code agentNo}（归属）与 {@code credRef}（凭据引用）——
 * <b>{@code isOwner} 没锁</b>。于是：
 *
 * <pre>{@code  POST /api/agent/accounts/{no}   {"isOwner": 1}}</pre>
 *
 * 一次普通的"编辑账号"就能把一个账号提成属主。
 * 同理 {@code principalNo}（这账号是哪个自然人的）也没锁 —— 改了等于把账号转给别人。
 *
 * <h2>为什么要查库断言</h2>
 * 出参 VO {@code AgentAccount} <b>不回传 isOwner</b>（构造里那一位传的是 username/status…），
 * 所以从接口响应上看不出有没有被改。**看不见的提权比看得见的危险** ——
 * 这也是它一直没被发现的原因。
 */
class AgentAccountOwnerFlagTest extends ApiTestSupport {

    @Autowired
    private DataSource dataSource;

    private static String uniq() {
        return "acc_probe_" + System.nanoTime();
    }

    private String createAccount(String admin, String username) {
        Map<String, Object> m = new HashMap<>();
        m.put("agentNo", "AG001");
        m.put("username", username);
        m.put("displayName", "属主探针");
        return post("/api/agent/accounts", m, admin)
                .okData().path("accountNo").asText();
    }

    private int ownerFlag(String accountNo) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(is_owner, 0) FROM agt_account WHERE account_no = ?")) {
            ps.setString(1, accountNo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("前提：账号 %s 应当存在", accountNo).isTrue();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("★★ 编辑账号不能把自己提成属主（is_owner = 全站点全权限）")
    void is_owner_cannot_be_set_through_the_save_endpoint() throws SQLException {
        String admin = login("ADMIN");
        String no = createAccount(admin, uniq());
        assertThat(ownerFlag(no)).as("前提：新建的账号不是属主").isZero();

        Map<String, Object> evil = new HashMap<>();
        evil.put("displayName", "改个显示名而已");
        evil.put("isOwner", 1);
        post("/api/agent/accounts/" + no, evil, admin);

        assertThat(ownerFlag(no))
                .as("属主只能由入驻审核的那个事务派生，不能靠编辑账号拿到")
                .isZero();
    }

    @Test
    @DisplayName("建号时传 isOwner 同样不生效——白名单在建和改两条路上都得成立")
    void is_owner_cannot_be_set_at_creation_either() throws SQLException {
        String admin = login("ADMIN");
        Map<String, Object> m = new HashMap<>();
        m.put("agentNo", "AG001");
        m.put("username", uniq());
        m.put("displayName", "建号即属主？");
        m.put("isOwner", 1);
        String no = post("/api/agent/accounts", m, admin)
                .okData().path("accountNo").asText();

        assertThat(ownerFlag(no)).isZero();
    }

    @Test
    @DisplayName("锁 isOwner 不能把正常编辑一起锁死")
    void ordinary_edits_still_work() {
        String admin = login("ADMIN");
        String no = createAccount(admin, uniq());

        Map<String, Object> m = new HashMap<>();
        m.put("displayName", "改过的显示名");
        post("/api/agent/accounts/" + no, m, admin).okData();

        // displayName 不在 VO 里，用状态这条能看见的路径确认保存没被整体挡掉
        assertThat(findInPages("/api/agent/accounts", "accountNo", no, admin))
                .as("账号仍在列表里，保存没被整体拒").isNotNull();
    }
}
