package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注销要能反悔（PDPL 冷静期，C-AC-05）。
 *
 * <h2>为什么这条用例比「能提交」更要紧</h2>
 * {@code UserLogoffService} 一直实现着 {@code current} / {@code cancel}，
 * 而控制器<b>只接出了 apply</b>。少了另外两个口，冷静期在产品上就是一句说法：
 * 用户点完注销既看不到「几号生效」，也无从反悔 —— <b>那是一扇单向门</b>，
 * 而且它不会报错，只会在某一天把账号删掉。
 *
 * <h2>为什么撤销之后还要再查一次</h2>
 * 撤销接口自己的返回值是「我说我撤了」；再查一次 {@code current} 才是
 * 「库里确实不是 PENDING 了」。前者能在事务没提交时也返回成功。
 */
class ConsumerLogoffTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    /** 每次用新号，避免撞上「同一用户至多一条 PENDING」而把本用例变成偶发红。 */
    private static String freshPhone() {
        return "+9715009" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
    }

    private String consumerToken(String phone) {
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    @Test
    @DisplayName("★★ 注销：提交后查得到冷静期，且期内能撤销——缺一个口，注销就是单向门")
    void logoff_can_be_seen_and_revoked_within_the_cooling_period() {
        String token = consumerToken(freshPhone());

        assertThat(get("/mp/user/logoff", token).okData().isNull())
                .as("还没申请过，当前注销申请应当为空").isTrue();

        JsonNode applied = post("/mp/user/logoff", Map.of(), token).okData();
        assertThat(applied.path("status").asText()).isEqualTo("PENDING");
        assertThat(applied.path("coolingUntil").asText())
                .as("冷静期截止时间必须返回——不给这个，用户不知道哪天会真删").isNotBlank();

        JsonNode seen = get("/mp/user/logoff", token).okData();
        assertThat(seen.path("status").asText())
                .as("提交之后要查得到自己的申请，否则「期内可撤销」无从知晓").isEqualTo("PENDING");

        post("/mp/user/logoff/cancel", Map.of(), token).okData();

        // 撤销接口自己的返回值只是「我说我撤了」，再查一次才是库里的事实
        JsonNode after = get("/mp/user/logoff", token).okData();
        assertThat(after.isNull() || !"PENDING".equals(after.path("status").asText()))
                .as("撤销之后不该还是 PENDING —— 否则到期照样会被清除，而用户以为自己已经反悔了")
                .isTrue();
    }

    @Test
    @DisplayName("没有申请时撤销 → 400，而不是假装成功")
    void cancelling_without_a_request_is_refused() {
        String token = consumerToken(freshPhone());
        assertThat(post("/mp/user/logoff/cancel", Map.of(), token).status)
                .as("无 PENDING 申请却撤销成功，会让人以为自己撤过了").isEqualTo(400);
    }

    @Test
    @DisplayName("★ 冷静期过了就不许撤销——那时候清除作业可能已经在删了")
    void cancelling_after_the_cooling_period_is_refused() {
        String phone = freshPhone();
        String token = consumerToken(phone);
        post("/mp/user/logoff", Map.of(), token).okData();

        // 把冷静期改到过去。不能靠等 15 天，也不该为了可测把冷静期做成可配 ——
        // 那是让生产配置迁就测试。直接改库里那一行，改完立刻验断言。
        String me = get("/mp/user/profile", token).okData().path("cUserNo").asText();
        int updated = jdbc.update("UPDATE usr_logoff SET cooling_until = ? "
                        + "WHERE c_user_no = ? AND status = 'PENDING'",
                "2000-01-01 00:00:00", me);
        assertThat(updated).as("前置：应当改到那条 PENDING 申请").isEqualTo(1);

        assertThat(post("/mp/user/logoff/cancel", Map.of(), token).status)
                .as("冷静期已过还能撤销，等于对用户说了假话：数据可能已经开始清除")
                .isEqualTo(400);
    }
}
