package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 触达拉黑：**谁拉的黑，不接受客户端传**。
 *
 * <h2>这张表本来守得很好，只漏了这一处</h2>
 * {@code NotifyBlacklistServiceImpl} 是手写的，而且是**隐式白名单**：
 * {@code update} 只受理 {@code channel} 与 {@code reason}；
 * {@code block} 强制 {@code status}/{@code releasedAt}/{@code releasedBy}/{@code blockNo}，
 * 还把 {@code target} 脱敏后再存，并写明「改掉等于换了一个人被拉黑，
 * 而从掩码上看不出换没换」。
 *
 * <p>唯独 {@code blockedBy} 没有回填 —— 列注释写的是「操作人(employee_no 或 SYSTEM)」，
 * 而它整条路径上没有一处 {@code setBlockedBy}，客户端传什么就是什么。
 * 拉黑会挡掉一个人收所有通知（含欠费催缴、退款通知），
 * 「谁拉的」是事后唯一能追的线索。
 *
 * <p>同一形状本轮已经撞过一次：公告的 {@code publishedBy}（2026-09-25）。
 */
class BlacklistBlockedByTest extends ApiTestSupport {

    private static String uniqPhone() {
        return "+9715" + (System.nanoTime() % 100000000L);
    }

    @Test
    @DisplayName("★★ 拉黑人由服务端回填——传别人的工号不算数")
    void blocked_by_cannot_be_forged() {
        String admin = login("ADMIN");

        Map<String, Object> evil = new HashMap<>();
        evil.put("target", uniqPhone());
        evil.put("channel", "SMS");
        evil.put("reason", "MANUAL");
        evil.put("blockedBy", "EMP_SOMEONE_ELSE");

        String blockNo = post("/api/platform/notify-blacklist", evil, admin)
                .okData().path("blockNo").asText();

        var row = findInPages("/api/platform/notify-blacklist", "blockNo", blockNo, admin);
        assertThat(row).as("前提：拉黑记录应当建出来").isNotNull();
        assertThat(row.path("blockedBy").asText(""))
                .as("拉黑会挡掉一个人收所有通知，「谁拉的」是事后唯一能追的线索")
                .isNotEqualTo("EMP_SOMEONE_ELSE");
    }

    @Test
    @DisplayName("正常拉黑仍然可用，且 target 按掩码存")
    void blocking_still_works_and_target_is_masked() {
        String admin = login("ADMIN");
        String phone = uniqPhone();

        Map<String, Object> m = new HashMap<>();
        m.put("target", phone);
        m.put("channel", "SMS");
        m.put("reason", "MANUAL");
        String blockNo = post("/api/platform/notify-blacklist", m, admin)
                .okData().path("blockNo").asText();

        var row = findInPages("/api/platform/notify-blacklist", "blockNo", blockNo, admin);
        assertThat(row.path("target").asText())
                .as("脱敏存储，与 notify_log 同口径 —— 库里不该留完整号码")
                .isNotEqualTo(phone);
        assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
    }
}
