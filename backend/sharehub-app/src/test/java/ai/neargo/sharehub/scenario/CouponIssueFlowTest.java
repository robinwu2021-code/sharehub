package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定向发券在真后端**整条不通** —— 一个动作上叠了三处。
 *
 * <ol>
 *   <li><b>入参键对不上</b>：端点读 {@code cUserNos}，而前端发的是人群规格
 *       （{@code targetType/targetValue/quantity}）。于是 {@code issue(tplNo, null, null)}
 *       走到「{@code cUserNos} 为空就 {@code return List.of()}」——
 *       <b>一张券都不发，HTTP 200</b>。</li>
 *   <li><b>出参形状对不上</b>：后端返 {@code List<UserCouponVO>}，前端 onSuccess 读
 *       {@code r.record.quantity} / {@code r.coupon} → 直接抛 TypeError。
 *       对齐卡口按类型名配对，{@code CouponIssueResult} 与 {@code UserCouponVO} 名字毫不相干，
 *       被判成「另一个投影」而跳过（与 MessageItem/Message 同一个盲区）。</li>
 *   <li><b>发放记录从没被写过</b>：{@code usr_coupon_issue} 在本域只被读
 *       （{@code pageIssueRecords}），所以运营端「发放记录」页永远是空的 ——
 *       而它是「谁在什么时候给谁发了多少张」的唯一凭证。</li>
 * </ol>
 *
 * <p>人群里只有 {@code USER_LIST} 能真发：后端至今没有「人群 → 用户列表」的解析
 * （推送侧也只拼了个人群<b>标签</b>，{@code mkt_segment} 表根本不存在）。
 * 其余三种<b>显式拒绝</b>而不是发 0 张 —— 后者会让运营以为发出去了。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponIssueFlowTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> tpls = new ArrayList<>();
    final List<String> users = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String t : tpls) {
            jdbc.update("DELETE FROM usr_coupon WHERE tpl_no=?", t);
            jdbc.update("DELETE FROM usr_coupon_issue WHERE coupon_no=?", t);
            jdbc.update("DELETE FROM coupon_tpl WHERE tpl_no=?", t);
        }
        for (String u : users) jdbc.update("DELETE FROM usr_user WHERE c_user_no=?", u);
    }

    @Test
    @DisplayName("★★ 指定用户号发券：券真的到人手里，发放记录落库，出参是前端读的那个形状")
    void issueToUserListWorks() {
        String admin = login("ADMIN");
        String tpl = tpl();
        String u1 = user(), u2 = user();

        JsonNode r = post("/api/user/coupons/" + tpl + "/issue",
                Map.of("targetType", "USER_LIST", "targetValue", u1 + "," + u2, "quantity", 5),
                admin).okData();

        // 出参形状：{coupon, record} —— 前端 onSuccess 读的就是这两个
        assertThat(r.path("coupon").path("couponNo").asText()).isEqualTo(tpl);
        assertThat(r.path("record").path("quantity").asInt())
                .as("此前后端返的是列表，前端读 r.record.quantity 直接抛 TypeError").isEqualTo(2);
        assertThat(r.path("record").path("operatorName").asText())
                .as("发放人按会话回填").isNotBlank();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_coupon WHERE tpl_no=?", Integer.class, tpl))
                .as("券要真的发到人手里 —— 此前一张都不发而 HTTP 200").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_coupon_issue WHERE coupon_no=?",
                Integer.class, tpl))
                .as("发放记录是「谁给谁发了多少张」的唯一凭证 —— 此前那张表只被读、从没被写")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("quantity 是上限：勾了 3 个人只发 2 张，记录里记实发张数")
    void quantityCapsTheIssue() {
        String admin = login("ADMIN");
        String tpl = tpl();
        String list = user() + "," + user() + "," + user();

        JsonNode r = post("/api/user/coupons/" + tpl + "/issue",
                Map.of("targetType", "USER_LIST", "targetValue", list, "quantity", 2), admin).okData();

        assertThat(r.path("record").path("quantity").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_coupon WHERE tpl_no=?", Integer.class, tpl))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 没有解析能力的人群显式拒绝，不是发 0 张 —— 后者让运营以为发出去了")
    void unsupportedAudienceIsRejectedLoudly() {
        String admin = login("ADMIN");
        String tpl = tpl();

        for (String type : List.of("ALL", "MEMBER_LEVEL", "SEGMENT")) {
            var resp = post("/api/user/coupons/" + tpl + "/issue",
                    Map.of("targetType", type, "targetValue", "x", "quantity", 1), admin);
            assertThat(resp.code()).as("人群 %s 应当被拒（响应：%s）", type, resp.msg()).isNotZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_coupon WHERE tpl_no=?", Integer.class, tpl))
                .as("拒绝就不该留下半张券").isZero();
    }

    // —— 夹具 ——

    private String tpl() {
        String no = "CTPLIS" + rnd();
        tpls.add(no);
        jdbc.update("INSERT INTO coupon_tpl (tpl_no, tenant_id, name, type, value, threshold, currency,"
                + " stock, issued, status) VALUES (?, 'MAIN', ?, 'CUT', 5, 0, 'AED', 0, 0, 'ACTIVE')",
                no, "发券链路测试 " + no);
        return no;
    }

    private String user() {
        String no = "CU-IS" + rnd();
        users.add(no);
        jdbc.update("INSERT INTO usr_user (c_user_no, tenant_id, nickname, status) VALUES (?, 'MAIN', ?, 'ACTIVE')",
                no, "发券测试 " + no);
        return no;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
