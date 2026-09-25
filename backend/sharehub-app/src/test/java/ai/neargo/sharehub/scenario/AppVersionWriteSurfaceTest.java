package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 端版本下发的写入面。
 *
 * <h2>为什么盯这张表</h2>
 * 它管**强制更新**：C 端每次启动拿 {@code /mp/app-version/check} 问一句，
 * 命中就弹「必须升级」。写错一行，影响的是所有装着 App 的人。
 *
 * <h2>已经守住的一条（不是本次加的，是本次确认的）</h2>
 * {@code status} 早在 2026-09-23 的批量赋值加固里就锁了，理由写在
 * {@code AppVersionServiceImpl.beforeUpdate}：不锁的话「回滚」形同虚设 ——
 * 回滚把 status 置 ROLLBACK，而更新接口能原样改回 RELEASED。
 *
 * <p>顺带确认了它**不能绕着 rolloutPercent 破**：C 端的查询同时要求
 * {@code status = RELEASED} 且 {@code rolloutPercent > 0}，状态那一关先拦住。
 *
 * <h2>本次要守的：业务键与 platform 不能脱钩</h2>
 * {@code versionId} 是复合自然键 {@code platform + "-" + versionNo}（建单时拼）。
 * 而 {@code beforeUpdate} 只锁了 status —— <b>编辑时改 platform，行还在，
 * 键却不再自洽</b>：一条 {@code versionId=IOS-…} 的记录 platform 变成 ANDROID 之后，
 * C 端按 platform 查会把它选出来，下发的是<b>另一个平台的安装包地址</b>。
 * 症状不是报错，是「安卓用户点更新下到一个 ipa」。
 */
class AppVersionWriteSurfaceTest extends ApiTestSupport {

    private static String uniqVersion() {
        return "9." + (System.nanoTime() % 100000) + ".0";
    }

    private String create(String admin, String platform, String versionNo) {
        Map<String, Object> m = new HashMap<>();
        m.put("platform", platform);
        m.put("versionNo", versionNo);
        m.put("buildNo", 1000);
        m.put("downloadUrl", "https://example.test/" + platform.toLowerCase() + ".pkg");
        return post("/api/platform/app-versions", m, admin).okData().path("versionId").asText();
    }

    private JsonNode row(String admin, String versionId) {
        return findInPages("/api/platform/app-versions", "versionId", versionId, admin);
    }

    @Test
    @DisplayName("★★ 编辑不能改 platform——业务键是 平台-版本号，改了键就不自洽了")
    void platform_cannot_be_changed_after_creation() {
        String admin = login("ADMIN");
        String no = uniqVersion();
        String id = create(admin, "IOS", no);
        assertThat(id).startsWith("IOS-");

        Map<String, Object> evil = new HashMap<>();
        evil.put("platform", "ANDROID");
        evil.put("downloadUrl", "https://example.test/android.apk");
        post("/api/platform/app-versions/" + id, evil, admin);

        assertThat(row(admin, id).path("platform").asText())
                .as("versionId 仍是 IOS-…，platform 就不能变成 ANDROID —— "
                        + "否则 C 端按 platform 查会把它选出来，下发另一个平台的包")
                .isEqualTo("IOS");
    }

    @Test
    @DisplayName("状态仍然只能由回滚/发布改（2026-09-23 已守，此处防回退）")
    void status_still_cannot_be_set_through_save() {
        String admin = login("ADMIN");
        String id = create(admin, "ANDROID", uniqVersion());
        assertThat(row(admin, id).path("status").asText()).isEqualTo("DRAFT");

        Map<String, Object> evil = new HashMap<>();
        evil.put("status", "RELEASED");
        evil.put("rolloutPercent", 100);
        post("/api/platform/app-versions/" + id, evil, admin);

        assertThat(row(admin, id).path("status").asText())
                .as("一次普通保存不该把草稿变成已发布")
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("灰度与强更仍可正常编辑——白名单不该把正事挡掉")
    void rollout_and_force_update_are_still_editable() {
        String admin = login("ADMIN");
        String id = create(admin, "ANDROID", uniqVersion());

        Map<String, Object> m = new HashMap<>();
        m.put("rolloutPercent", 30);
        m.put("forceUpdate", true);
        m.put("releaseNote", "灰度 30%");
        post("/api/platform/app-versions/" + id, m, admin).okData();

        JsonNode r = row(admin, id);
        assertThat(r.path("rolloutPercent").asDouble()).isEqualTo(30.0);
        assertThat(r.path("forceUpdate").asBoolean()).isTrue();
    }
}
