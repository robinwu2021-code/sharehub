package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客服手工建单的用户号。
 *
 * <h2>后端自己前后不一致</h2>
 * {@code CsTicketVO} 出参叫 {@code userNo}，而 {@code TicketCreateReq} 入参叫 {@code cUserNo} ——
 * 同一个字段，读一个名、写另一个名。运营端是照着出参写的（列表那一列、表单那一格都叫 userNo），
 * 于是**建单时「用户」这一栏存不进去**：@RequestBody 静默忽略不认识的键，
 * 接口照常 200，单子建出来了，只是没有用户。
 *
 * <p>这一格是可空的（来电的人未必报得出账号，DTO 注释解释过），
 * 所以「空着」看起来完全正常 —— 没有任何症状，除了事后查「这单是谁报的」时答不上来。
 *
 * <h2>为什么改入参而不是改出参</h2>
 * 本仓库的分层口径是 <b>DTO / API 层用 {@code userNo}，实体与库列用 {@code cUserNo}</b>
 * （{@code c_user_no}）。同域的写入 DTO {@code ComplaintCreateReq}、{@code RefundApplyReq}
 * 都是 {@code userNo}。所以 {@code TicketCreateReq.cUserNo} 是把列名漏到 API 上的异类，
 * 改它只影响这一个端点；改出参要连带前端类型与两处列定义，而且方向是错的。
 */
class CsTicketCreateTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String someUser() {
        return jdbc.queryForObject("SELECT c_user_no FROM usr_user ORDER BY id LIMIT 1", String.class);
    }

    private JsonNode createWith(String admin, String userNo, String issue) {
        Map<String, Object> m = new HashMap<>();
        m.put("userNo", userNo);          // 运营端发的就是这个键
        m.put("issue", issue);
        m.put("channel", "电话");
        return post("/api/ops/cs/tickets", m, admin).okData();
    }

    @Test
    @DisplayName("★★ 手工建单时传的用户号要落下来——运营端发的键是 userNo")
    void userNoSurvivesManualCreate() {
        String admin = login("ADMIN");
        String u = someUser();
        assertThat(u).as("前提：库里得有个 C 端用户").isNotBlank();

        JsonNode t = createWith(admin, u, "用户号落地探针");
        String no = t.path("ticketNo").asText();
        assertThat(no).as("前提：单子建出来了").isNotBlank();

        try {
            assertThat(t.path("userNo").asText(""))
                    .as("建单响应里就该带上用户号，否则客服看不出登记对没对")
                    .isEqualTo(u);
            assertThat(jdbc.queryForObject("SELECT c_user_no FROM cs_ticket WHERE ticket_no=?", String.class, no))
                    .as("库里也要真落下来 —— 出参对了但列是空的同样查不到人")
                    .isEqualTo(u);
        } finally {
            jdbc.update("DELETE FROM cs_ticket WHERE ticket_no=?", no);
        }
    }

    @Test
    @DisplayName("用户号仍可留空——来电的人未必报得出账号")
    void userNoStaysOptional() {
        String admin = login("ADMIN");
        Map<String, Object> m = new HashMap<>();
        m.put("issue", "匿名来电探针");
        m.put("channel", "电话");
        JsonNode t = post("/api/ops/cs/tickets", m, admin).okData();
        String no = t.path("ticketNo").asText();
        try {
            assertThat(no).as("不带用户号也要能登记下来 —— 「登记不下来」比「缺个账号」糟得多").isNotBlank();
            assertThat(jdbc.queryForObject("SELECT c_user_no FROM cs_ticket WHERE ticket_no=?", String.class, no))
                    .as("留空落 NULL，不落空串 —— 空串会让「没报账号」和「报了个空」在报表里混成一类")
                    .isNull();
        } finally {
            jdbc.update("DELETE FROM cs_ticket WHERE ticket_no=?", no);
        }
    }
}
