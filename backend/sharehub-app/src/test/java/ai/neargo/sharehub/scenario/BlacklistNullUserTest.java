package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拉黑端点的「字符串 null」—— 裸 {@code Map} 请求体最典型的那种坑。
 *
 * <p>原先控制器写 {@code String.valueOf(body.get("cUserNo"))}：键缺席、或前端拼成
 * {@code userNo}（这个仓库里 {@code userNo} / {@code cUserNo} 两种写法都有，见字典项那次），
 * {@code String.valueOf(null)} 给的是<b>字符串 {@code "null"}</b>。
 * 而 {@code UserBlacklistServiceImpl.block} 不校验业务键 —— 于是：
 * <ul>
 *   <li>库里多一条 {@code c_user_no = 'null'} 的 ACTIVE 拉黑记录；</li>
 *   <li>调用方拿到 {@code {ok:true}}。</li>
 * </ul>
 * <b>运营以为拉黑了某人，而没有任何人被拉黑</b>，且那条垃圾记录会一直留在风控列表里。
 *
 * <p>换成 record 之后键缺席就是 {@code null}，配一个判空即 400。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlacklistNullUserTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @AfterAll
    void cleanup() {
        // 保险：万一回归了，别把垃圾行留给下一次跑
        jdbc.update("DELETE FROM usr_blacklist WHERE c_user_no IN ('null', '')");
    }

    @Test
    @DisplayName("★★ 缺 cUserNo 要 400，而不是给字符串「null」建一条拉黑记录")
    void missingUserNoIsRejected() {
        String admin = login("ADMIN");
        int before = countNullRows();

        Map<String, Object> body = new HashMap<>();
        body.put("blacklisted", true);
        body.put("reason", "缺业务键的请求");
        var resp = post("/internal/user/credit/blacklist", body, admin);

        assertThat(resp.code()).as("应当是业务失败（响应：%s）", resp.msg()).isNotZero();
        assertThat(countNullRows())
                .as("不该多出 c_user_no='null' 的拉黑记录 —— 那条记录谁也匹配不上，"
                        + "只会一直躺在风控列表里，而运营以为拉黑生效了")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("键拼错（userNo 而不是 cUserNo）同样要 400")
    void misspelledKeyIsRejected() {
        String admin = login("ADMIN");
        int before = countNullRows();

        Map<String, Object> body = new HashMap<>();
        body.put("userNo", "CU-0001");     // 拼错：正确的是 cUserNo
        body.put("blacklisted", true);
        var resp = post("/internal/user/credit/blacklist", body, admin);

        assertThat(resp.code()).as("拼错的键不该被当成「没传」就放过（响应：%s）", resp.msg()).isNotZero();
        assertThat(countNullRows()).isEqualTo(before);
    }

    private int countNullRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usr_blacklist WHERE c_user_no IN ('null', '')", Integer.class);
        return n == null ? 0 : n;
    }
}
