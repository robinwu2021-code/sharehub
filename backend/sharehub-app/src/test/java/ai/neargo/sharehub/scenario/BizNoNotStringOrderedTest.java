package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取号必须按**数字序**取最大，不能按字符串序 —— 否则一个脏号就把取号锁死。
 *
 * <h2>这个形状已经爆过一次</h2>
 * 2026-09-28 实测：{@code PowerbankServiceImpl.nextNo} 按
 * {@code orderByDesc(powerbankNo)} 取「最大号」，而库里存量号是
 * {@code PBDFECE7C9E0F} 这种十六进制形 —— <b>字符串序下它永远排最前</b>，
 * 于是每次都算出同一个号，<b>连续建第二只充电宝必定 500</b>（撞唯一键）。
 * 第一只能建成功，只是因为那个号恰好还没被占。
 *
 * <p>同一份代码形状还在 {@code OrderSupport.nextNo}（投诉 / 异常 / 退款三张表）。
 * 那三张表目前号还齐整，所以它是**潜伏版本** —— 而潜伏的判据是「有没有脏号」，
 * 不是「写得对不对」：哪天导一批历史数据进来，第二次建单就开始 500。
 *
 * <h2>为什么用「投毒」而不是等它自然发生</h2>
 * 等一个非等宽/非纯数字的号自然出现，等于等缺陷上生产。
 * 这一条**自己造一个**：先插一行字符串序最大、数字序无意义的号，
 * 再连着建两单 —— 取号正确就该跳过它，两单拿到不同的号。
 *
 * <p>脏号刻意用 {@code CPLZZZZZZ}：Z 在数字与字母里都排最后，
 * 字符串序必然把它顶到第一位；而 {@code CAST(... AS UNSIGNED)} 把它算作 0，
 * 于是正确实现完全看不见它。
 */
class BizNoNotStringOrderedTest extends ApiTestSupport {

    private static final String POISON = "CPLZZZZZZ";
    private static final String MARK = "取号脏数据测试";

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM ord_complaint WHERE complaint_no = ? OR description = ?", POISON, MARK);
    }

    @Test
    @DisplayName("★★ 库里混进一个字符串序最大的脏号之后，仍然建得出第二单（取号不按字符串序）")
    void a_dirty_no_does_not_freeze_the_sequence() {
        String t = login("CS");

        jdbc.update("""
                INSERT INTO ord_complaint (complaint_no, tenant_id, order_no, c_user_no,
                                           issue_type, description, status, submitted_at)
                VALUES (?, 'MAIN', 'ORD500000', 'U3000', 'OTHER', ?, 'PENDING', NOW())
                """, POISON, MARK);

        String first = create(t);
        String second = create(t);

        assertThat(first)
                .as("第一单没建出来 —— 脏号把取号卡死了？")
                .isNotBlank();
        assertThat(second)
                .as("""
                        第二单和第一单拿到了同一个号 —— 取号按字符串序取了那个脏号，
                        每次都算出同一个值。真实后果是**第二次建单直接 500**（撞唯一键），
                        而第一次成功会让人以为功能是好的。
                        """)
                .isNotEqualTo(first);
    }

    private String create(String token) {
        Map<String, Object> req = new HashMap<>();
        req.put("orderNo", "ORD500000");
        req.put("userNo", "U3000");
        req.put("issueType", "OTHER");
        req.put("description", MARK);
        var r = post("/api/trade/complaints", req, token);
        assertThat(r.status).as("建投诉失败：%s", r.msg()).isEqualTo(200);
        return r.okData().path("complaintNo").asText();
    }
}
