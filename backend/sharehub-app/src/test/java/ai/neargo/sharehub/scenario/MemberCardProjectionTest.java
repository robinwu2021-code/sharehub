package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会员卡列表 —— 「注释描述了正确行为，代码没做」的第四例。
 *
 * <p>{@code MemberServiceImpl.pageCards} 无条件 {@code return new PageResult<>(List.of(), 0L)}，
 * 上面配着一行「会员卡由 usr_membership 投影；本服务只读它」—— 读起来像已经读了。
 * 两处因此一直是空的：
 * <ul>
 *   <li>运营端「会员卡」列表；</li>
 *   <li><b>用户 360 档案</b>里的 {@code cards} —— 更难发现，因为同一个响应里的
 *       「会员」块是真的（走 {@code MembershipService.get}），只有「会员卡」是空的，
 *       看起来像这个人没开过卡。</li>
 * </ul>
 *
 * <p>另一半同样重要：360 档案对 risk / blacklist 都做了「按业务键精确二次过滤」
 * （keyword 是 LIKE），唯独 cards 那行没有。<b>不过滤一直没出事，只因为它恒返空</b> ——
 * 让它真的返数据，{@code CU-0001} 的档案就会带出 {@code CU-00012} 的卡。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberCardProjectionTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> members = new ArrayList<>();
    final List<String> users = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String m : members) jdbc.update("DELETE FROM usr_membership WHERE mbr_no=?", m);
        for (String u : users) jdbc.update("DELETE FROM usr_user WHERE c_user_no=?", u);
    }

    @Test
    @DisplayName("开了会员卡，运营端「会员卡」列表查得到")
    void opsListShowsTheCard() {
        String cUser = user();
        String mbrNo = card(cUser, "GOLD");

        JsonNode rows = get("/api/user/member-cards?page=1&size=50&keyword=" + cUser, login("ADMIN")).okData();
        assertThat(rows.path("total").asLong()).as("查不到就等于这一页从来没上线过").isPositive();
        JsonNode row = rows.path("list").path(0);
        assertThat(row.path("mbrNo").asText()).isEqualTo(mbrNo);
        assertThat(row.path("level").asText()).isEqualTo("GOLD");
        assertThat(row.path("autoRenew").asBoolean()).as("TINYINT(1) 要出成布尔，不是 1/0").isTrue();
    }

    @Test
    @DisplayName("360 档案里的会员卡只能是他自己的 —— keyword 是 LIKE，业务键要精确再过一遍")
    void profileDoesNotLeakAnotherUsersCard() {
        // 故意造成子串关系：短号是长号的前缀
        String shortNo = "CU-MC" + rnd();
        String longNo = shortNo + "9";
        users.add(shortNo);
        users.add(longNo);
        jdbc.update("INSERT INTO usr_user (c_user_no, tenant_id, nickname, status) VALUES (?, 'MAIN', ?, 'ACTIVE')",
                shortNo, "会员卡测试 " + shortNo);
        card(shortNo, "SILVER");
        String hisCard = card(longNo, "PLATINUM");

        JsonNode profile = get("/api/user/users/" + shortNo + "/profile", login("ADMIN")).okData();
        List<String> cards = new ArrayList<>();
        for (JsonNode c : profile.path("cards")) cards.add(c.path("mbrNo").asText());

        assertThat(cards).as("自己的卡要在").isNotEmpty();
        assertThat(cards).as("别人的卡不能出现在我的档案里（%s 是 %s 的子串）", shortNo, longNo)
                .doesNotContain(hisCard);
    }

    // —— 夹具 ——

    private String card(String cUserNo, String level) {
        String no = "UMC" + rnd();
        members.add(no);
        jdbc.update("INSERT INTO usr_membership (mbr_no, tenant_id, c_user_no, plan_no, level, points,"
                        + " start_at, end_at, auto_renew, status)"
                        + " VALUES (?, 'MAIN', ?, 'MBRP-TEST', ?, 120, ?, ?, 1, 'ACTIVE')",
                no, cUserNo, level, LocalDate.now().minusDays(1).toString(), LocalDate.now().plusMonths(1).toString());
        return no;
    }

    private String user() {
        String no = "CU-MC" + rnd();
        users.add(no);
        jdbc.update("INSERT INTO usr_user (c_user_no, tenant_id, nickname, status) VALUES (?, 'MAIN', ?, 'ACTIVE')",
                no, "会员卡测试 " + no);
        return no;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
